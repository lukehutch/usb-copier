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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.oled.OLEDDriver;
import aobtk.ui.element.ProgressBar;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.screen.Screen;
import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor.TaskResult;
import main.Main;
import util.DriveInfo;

public class DoWipeScreen extends Screen {
    private final DriveInfo selectedDrive;
    private volatile boolean selectedDriveIsMounted;

    private boolean isQuick;

    private volatile ProgressBar progressBar;

    private volatile TaskResult<Integer> ddCommandTask;

    private void exceptionThrown(Exception e) {
        e.printStackTrace();
        setUI(new VLayout(new TextElement(Main.FONT.newStyle(), Msg.ERROR)));
        waitThenGoToParentScreen(3000);

        // Try remounting drive, if it is unmounted
        if (!selectedDriveIsMounted) {
            try {
                remount();
            } catch (Exception e2) {
                // Made best effort -- ignore
                System.out.println("Couldn't remount drive " + selectedDrive.partitionDevice + ": " + e2);
            }
        }
    }

    private void mkfs() throws CommandException, InterruptedException, CancellationException {
        // Format partition as vfat
        Command.command("sudo /sbin/mkfs.vfat -F32 " + selectedDrive.partitionDevice);
    }

    private void unmount() throws CommandException, InterruptedException, CancellationException {
        // Unmount partition
        Command.command("sudo udisksctl unmount --no-user-interaction -f -b " + selectedDrive.partitionDevice);
        selectedDriveIsMounted = false;
    }

    private void remount() throws CommandException, InterruptedException, CancellationException {
        // Remount the partition after it has been wiped
        Command.command("sudo udisksctl mount --no-user-interaction -b " + selectedDrive.partitionDevice);
        selectedDriveIsMounted = true;
    }

    private void sync() throws CommandException, InterruptedException, CancellationException {
        Command.command("sudo sync");
    }

    private TaskResult<Integer> ddWipe(DriveInfo selectedDrive) throws CommandException {
        return Command.commandWithConsumer(
                "sudo dd if=/dev/zero of=" + selectedDrive.partitionDevice + " bs=4096 status=progress", line -> {
                    if (!line.isEmpty() && !line.contains("records in")) {
                        // Show progress percentage
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx < 0) {
                            spaceIdx = line.length();
                        }
                        long bytesProcessed = Long.parseLong(line.substring(0, spaceIdx));
                        int percent = (int) ((bytesProcessed * 100.0f) / selectedDrive.size + 0.5f);
                        progressBar.setProgress(percent, 100);
                        repaint();
                    }
                }, /* consumeStderr = */ true);
    }

    public DoWipeScreen(Screen parentScreen, DriveInfo selectedDrive, boolean isQuick) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;
        if (selectedDrive == null) {
            goToParentScreen();
            return;
        }

        this.isQuick = isQuick;

        VLayout layout = new VLayout();
        layout.add(new TextElement(Main.FONT.newStyle(), new Str(Msg.ERASING, selectedDrive.port)));
        layout.addSpace(4);
        layout.add(progressBar = new ProgressBar(OLEDDriver.DISPLAY_WIDTH * 8 / 10, 10));
        setUI(layout);
    }

    @Override
    public void open() {
        // Run the wipe in a different thread, so the status line can update as the wipe proceeds
        taskExecutor.submit(() -> {
            boolean canceled = false;

            progressBar.setProgress(0, 100);
            repaint();

            // First check if drive is originally even mounted (this allows non-mounted drives to be formatted,
            // which is important if a drive needs to be formatted after a previous format failed).
            selectedDriveIsMounted = selectedDrive.isMounted();

            if (selectedDriveIsMounted) {
                // Unmount the partition
                try {
                    // Unmount the partition
                    unmount();
                } catch (CommandException | InterruptedException | CancellationException e1) {
                    // If partition could not be unmounted, do not proceed (later steps will fail anyway)
                    exceptionThrown(e1);
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
                        throw new CommandException("dd returned non-zero exit code " + ddCommandExitCode);
                    }

                } catch (CommandException | ExecutionException e2) {
                    System.out.println("dd failed");
                    e2.printStackTrace();
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

            // Quick format using mkfs, followed by sync and remount
            try {
                // Format the partition (whether or not low-level format was canceled)
                mkfs();

            } catch (CommandException e4) {
                // Error occurred during mkfs
                e4.printStackTrace();
                // Fall through, and try sync and remount

            } catch (InterruptedException | CancellationException e5) {
                canceled = true;
            }

            // Quick format using mkfs, followed by sync and remount
            try {
                // sync
                sync();

            } catch (CommandException e6) {
                // Should not happen, but fall through if so

            } catch (InterruptedException | CancellationException e7) {
                canceled = true;
            }

            // Remount drive
            try {
                // Remount the partition
                remount();

            } catch (CommandException e8) {
                // Error occurred during remount, so probably the drive is messed up now
                exceptionThrown(e8);
                return;

            } catch (InterruptedException | CancellationException e9) {
                canceled = true;
            }

            // Mark the partition as changed now that the drive is remounted
            selectedDrive.contentsChanged();

            // Finished -- briefly show 100% progress at end of job
            progressBar.setProgress(100, 100);
            repaint();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e10) {
                canceled = true;
            }

            if (!canceled) {
                // Show completed status
                setUI(new VLayout(new TextElement(Main.FONT.newStyle(), Msg.COMPLETED)));
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
                ddCommandTask.cancel();
                // Prevent double attempt to cancel
                ddCommandTask = null;
                // Show canceled text
                setUI(new VLayout(new TextElement(Main.FONT.newStyle(), Msg.CANCELED)));
                // Go back to parent
                // (N.B. WIPE_EXECUTOR will still be trying to run mkfs, sync, and mount
                // in the background, to get the drive back to a legible state)
                waitThenGoToParentScreen(2000);
            }
        }
    }
}
