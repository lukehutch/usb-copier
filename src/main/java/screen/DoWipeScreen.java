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
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
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

    private Queue<String> ddStderrLines = new ConcurrentLinkedDeque<>();

    private TaskOutput mkfs() {
        // Format partition as vfat
        System.out.println("Formatting as vfat: " + selectedDrive.partitionDevice);
        return Exec.execWithTaskOutputSynchronous("/sbin/mkfs.vfat", selectedDrive.partitionDevice);
    }

    private Future<Integer> ddWipe(DriveInfo selectedDrive) {
        System.out.println("Performing deep wipe: " + selectedDrive.partitionDevice);
        ddStderrLines.clear();
        return Exec.execConsumingLines(null, stderrLine -> {
            ddStderrLines.add(stderrLine);
            if (!stderrLine.isEmpty() && !stderrLine.contains("records in")) {
                // Show progress percentage
                int spaceIdx = stderrLine.indexOf(' ');
                if (spaceIdx < 0) {
                    spaceIdx = stderrLine.length();
                }
                long bytesProcessed = Long.parseLong(stderrLine.substring(0, spaceIdx));
                int percent = (int) ((bytesProcessed * 100.0f) / selectedDrive.diskSize + 0.5f);
                progressBar.setProgress(percent, 100);
                repaint();
            }
        }, //
                "dd", "if=/dev/zero", "of=" + selectedDrive.partitionDevice, "bs=4096", "status=progress",
                "oflag=direct");
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

            // Unmount drive first, if mounted
            boolean unmounted = selectedDrive.unmount();
            if (!unmounted) {
                System.out.println("Could not unmount " + this.selectedDrive.partitionDevice);
                setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.ERROR)));
                waitThenGoToParentScreen(2000);
                return;
            }
            // Clear the partition listing
            selectedDrive.clearListing();

            // Deep-wipe the partition using dd if requested
            if (!isQuick) {
                try {
                    // Safe wipe of partition -- write zeroes to entire partition using dd
                    var task = ddCommandTask = ddWipe(selectedDrive);

                    // Wait for dd to finish
                    int ddCommandExitCode = task.get();

                    // Set ref to null so that A-button doesn't try to cancel it again
                    ddCommandTask = null;

                    if (ddCommandExitCode != 0) {
                        throw new IOException("dd returned non-zero exit code " + ddCommandExitCode + ": "
                                + String.join("\n", ddStderrLines));
                    }

                } catch (IOException | ExecutionException e) {
                    // dd failed
                    e.printStackTrace();
                    setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.ERROR)));
                    waitThenGoToParentScreen(3000);
                    return;

                } catch (InterruptedException | CancellationException e3) {
                    // Operation was cancelled, and child dd process was killed by Command.commandWithConsumer()
                    canceled = true;
                    // Fall through on cancelation so that mkfs still runs
                }
            } else {
                // If not doing a deep format, set progress to 50% before quick wipe starts so that it looks
                // like the job is partway through
                progressBar.setProgress(50, 100);
                repaint();
            }

            // Sync so that buffers from dd are flushed  
            DiskMonitor.sync();

            // Format the partition (whether or not low-level format was canceled)
            TaskOutput mkfsResult = mkfs();
            if (mkfsResult.exitCode != 0) {
                System.out.println(
                        "mkfs returned non-zero exit code " + mkfsResult.exitCode + ": " + mkfsResult.stderr);
            }

            // Sync again
            DiskMonitor.sync();

            // Remount the partition
            selectedDrive.mount();

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
            // Allow low-level wipe to be canceled only once
            if (ddCommandTask != null) {
                System.out.println("Canceling dd task");
                ddCommandTask.cancel(true);
                // Prevent double attempt to cancel
                ddCommandTask = null;
                // Show canceled text
                setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.CANCELING)));
            }
        }
    }
}
