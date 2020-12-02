/*
 * This file is part of the Adafruit OLED Bonnet Toolkit: a Java toolkit for the Adafruit 128x64 OLED bonnet,
 * with support for the screen, D-pad/buttons, UI layout, and task scheduling.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/Adafruit-OLED-Bonnet-Toolkit
 * 
 * This code is not associated with or endorsed by Adafruit. Adafruit is a trademark of Limor "Ladyada" Fried. 
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package screen;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.oled.OLEDDriver;
import aobtk.ui.element.ProgressBar;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.screen.Screen;
import exec.Exec;
import exec.Exec.TaskOutput;
import i18n.Msg;
import main.Main;
import util.DiskMonitor;
import util.DriveInfo;

public class DoWipeScreen extends Screen {
    private final DriveInfo selectedDrive;

    private boolean isQuick;

    private volatile ProgressBar progressBar;

    private volatile Future<Integer> ddCommandTask;

    private void exceptionThrown(Exception e) {
        e.printStackTrace();
        setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.ERROR)));
        waitThenGoToParentScreen(3000);

        // Try remounting drive, if it is unmounted
        DriveInfo selectedDrive = this.selectedDrive;
        if (selectedDrive != null && !selectedDrive.isMounted()) {
            try {
                selectedDrive.mount();
            } catch (InterruptedException | ExecutionException e1) {
                System.out.println("Could not mount drive after exception thrown: " + e1);
            }
        }
    }

    private TaskOutput mkfs() {
        // Format partition as vfat
        return Exec.execWithTaskOutputSynchronous(
                new String[] { "/sbin/mkfs.vfat", "-F32", selectedDrive.partitionDevice });
    }

    private Future<Integer> ddWipe(DriveInfo selectedDrive) {
        return Exec.execConsumingLines(line -> {
            if (!line.isEmpty() && !line.contains("records in")) {
                // Show progress percentage
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) {
                    spaceIdx = line.length();
                }
                long bytesProcessed = Long.parseLong(line.substring(0, spaceIdx));
                int percent = (int) ((bytesProcessed * 100.0f) / selectedDrive.diskSize + 0.5f);
                progressBar.setProgress(percent, 100);
                repaint();
            }
        }, "dd", "if=/dev/zero", "of=" + selectedDrive.partitionDevice, "bs=4096", "status=progress");
    }

    public DoWipeScreen(Screen parentScreen, DriveInfo selectedDrive, boolean isQuick) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;
        if (selectedDrive == null) {
            waitThenGoToParentScreen(3000);
            return;
        }

        this.isQuick = isQuick;

        VLayout layout = new VLayout();
        layout.add(new TextElement(Main.UI_FONT.newStyle(), new Str(Msg.ERASING, selectedDrive.port)));
        layout.addSpace(4);
        layout.add(progressBar = new ProgressBar(OLEDDriver.DISPLAY_WIDTH * 8 / 10, 10));
        setUI(layout);
    }

    @Override
    public void open() {
        // Run the wipe in a different thread, so the status line can update as the wipe proceeds
        Exec.executor.submit(() -> {
            boolean canceled = false;

            progressBar.setProgress(0, 100);
            repaint();

            DriveInfo selectedDrive = this.selectedDrive;
            if (selectedDrive == null) {
                goToParentScreen();
                return;
            }

            // First check if drive is originally even mounted (this allows non-mounted drives to be formatted,
            // which is important if a drive needs to be formatted after a previous format failed).
            if (!selectedDrive.isMounted()) {
                try {
                    selectedDrive.mount();

                } catch (Exception e) {
                    exceptionThrown(e);
                    return;
                }
            }

            // Deep-wipe the partition using dd if requested
            if (!isQuick) {
                try {
                    // Safe wipe of partition -- write zeroes to entire partition using dd
                    ddCommandTask = ddWipe(selectedDrive);

                    // Wait for dd to finish
                    int ddCommandExitCode = ddCommandTask.get();
                    // Set ref to null so that A-button doesn't try to cancel it again
                    ddCommandTask = null;
                    if (ddCommandExitCode != 0) {
                        throw new IOException("dd returned non-zero exit code " + ddCommandExitCode);
                    }

                } catch (IOException | ExecutionException e) {
                    System.out.println("dd failed");
                    e.printStackTrace();
                    // dd failed, but fall through and try mkfs anyway 

                } catch (InterruptedException | CancellationException e3) {
                    // Operation was cancelled, and child dd process was killed by Command.commandWithConsumer()
                    canceled = true;
                }
            } else {
                // If not doing a deep format, set progress to 50% before quick wipe starts so that it looks
                // like the job is partway through
                progressBar.setProgress(50, 100);
                repaint();
            }

            // Format the partition (whether or not low-level format was canceled)
            TaskOutput mkfsResult = mkfs();
            if (mkfsResult.exitCode != 0) {
                System.out.println(
                        "mkfs returned non-zero exit code " + mkfsResult.exitCode + ": " + mkfsResult.stderr);
            }

            // sync
            DiskMonitor.sync();

            // Remount drive
            try {
                // Remount the partition
                selectedDrive.remount();

            } catch (InterruptedException | CancellationException e) {
                canceled = true;

            } catch (Exception e) {
                // Error occurred during remount, so probably the drive is messed up now
                exceptionThrown(e);
                return;
            }

            // Mark the partition as changed now that the drive is remounted
            selectedDrive.clearListing();
            selectedDrive.updateDriveSizesAsync();

            // Finished -- briefly show 100% progress at end of job
            progressBar.setProgress(100, 100);
            repaint();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e10) {
                canceled = true;
            }

            if (!canceled) {
                // Show completed status
                setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.COMPLETED)));
                waitThenGoToParentScreen(2000);
            } else {
                // "Canceled" was already shown, and there was already a time delay during the mkfs call,
                // so go straight back to parent
                goToParentScreen();
            }
        });
    }

    @Override
    public void close() {
    }

    @Override
    public boolean acceptsButtonA() {
        // Allow for early termination by pressing button A (Cancel)
        return true;
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.A) {
            // Allow low-level wipe to be canceled
            if (ddCommandTask != null) {
                System.out.println("Canceling dd task");
                ddCommandTask.cancel(true);
                // Prevent double attempt to cancel
                ddCommandTask = null;
                // Show canceled text
                setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.CANCELED)));
                // Go back to parent
                // (N.B. WIPE_EXECUTOR will still be trying to run mkfs, sync, and mount
                // in the background, to get the drive back to a legible state)
                waitThenGoToParentScreen(2000);
            }
        }
    }
}
