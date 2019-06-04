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

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import aobtk.font.Font;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.oled.OLEDDriver;
import aobtk.ui.element.ProgressBar;
import aobtk.ui.element.TableLayout;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.element.VLayout.VAlign;
import aobtk.ui.screen.Screen;
import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor;
import aobtk.util.TaskExecutor.TaskResult;
import i18n.Msg;
import main.Main;
import util.DriveInfo;

public class DoCopyScreen extends Screen {
    private final DriveInfo selectedDrive;
    private volatile List<DriveInfo> otherDrives;
    private final List<ProgressBar> progressBars;

    private TaskExecutor[] taskExecutors;
    private List<TaskResult<Integer>> rsyncTaskResults;
    private final AtomicBoolean canceled = new AtomicBoolean(false);

    public DoCopyScreen(Screen parentScreen, DriveInfo selectedDrive, int numFiles, List<DriveInfo> otherDrives) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;
        this.otherDrives = otherDrives;

        VLayout layout = new VLayout();

        layout.add(new TextElement(Main.UI_FONT.newStyle(), new Str(Msg.COPYING, selectedDrive.port)),
                VAlign.CENTER);
        layout.addSpace(2, VAlign.CENTER);
        layout.add(new TextElement(Main.UI_FONT.newStyle(), new Str(Msg.FILE_COUNT, numFiles)), VAlign.CENTER);
        layout.addSpace(6, VAlign.CENTER);

        // Create table of progress bars
        progressBars = new ArrayList<>(otherDrives.size());
        TableLayout driveTable = new TableLayout(4, 4);
        for (DriveInfo otherDrive : otherDrives) {
            ProgressBar progressBar = new ProgressBar(OLEDDriver.DISPLAY_WIDTH * 8 / 10, 10);
            progressBars.add(progressBar);
            driveTable.add(new TextElement(Font.PiOLED_5x8().newStyle(), "->#" + otherDrive.port), progressBar);
        }

        layout.add(driveTable, VAlign.CENTER);

        setUI(layout);
    }

    @Override
    public void open() {
        taskExecutors = new TaskExecutor[otherDrives.size()];
        rsyncTaskResults = new ArrayList<>(otherDrives.size());
        AtomicBoolean succeeded = new AtomicBoolean(true);
        for (int i = 0; i < otherDrives.size(); i++) {
            DriveInfo otherDrive = otherDrives.get(i);
            ProgressBar progressBar = progressBars.get(i);

            // Spawn a separate rsync task per drive, each in a separate TaskExecutor (since TaskExecutor
            // can only run one task at once). This parallelizes copying to multiple drives, which could
            // load the USB hub pretty heavily, but it has the advantage of reusing the cache for the read
            // files, assuming the destination drives have approximately the same write speed.
            taskExecutors[i] = new TaskExecutor();
            try {
                TaskResult<Integer> commandResult = Command.commandWithConsumer("rsync -rlpt --info=progress2 "
                        // End source dir in "/" to copy contents of dir, not dir itself
                        + selectedDrive.mountPoint + "/ " //
                        + otherDrive.mountPoint, //
                        taskExecutors[i], /* consumeStderr = */ false, progressLine -> {
                            // Get progress percentage for rsync transfer
                            if (progressLine.contains("xfr#") && progressLine.contains("to-chk=")) {
                                StringTokenizer tok = new StringTokenizer(progressLine);
                                tok.nextToken();
                                String percentage = tok.nextToken();
                                if (percentage.endsWith("%")) {
                                    try {
                                        int percentageInt = Integer
                                                .parseInt(percentage.substring(0, percentage.length() - 1));
                                        // Update progress bar with rsync progress percentage
                                        progressBar.setProgress(percentageInt, 100);
                                        repaint();
                                    } catch (NumberFormatException e) {
                                    }
                                }
                            }
                        });
                rsyncTaskResults.add(commandResult);
            } catch (CommandException e) {
                e.printStackTrace();
                succeeded.set(false);
            }
        }

        // Wait for all rsync tasks to terminate
        taskExecutor.submit(() -> {
            for (TaskResult<Integer> result : rsyncTaskResults) {
                try {
                    // Wait for copy operation to complete
                    Integer resultCode = result.get();
                    System.out.println("Got result code: " + resultCode);
                    if (resultCode != 0) {
                        succeeded.set(false);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    // A copy operation failed
                    succeeded.set(false);
                }
            }

            // Run sync, shut down task executors, and mark drives as changed
            stopCopying();

            if (!canceled.get() && succeeded.get()) {
                // Set all progress bars to 100% at end
                for (int i = 0; i < otherDrives.size(); i++) {
                    ProgressBar progressBar = progressBars.get(i);
                    if (progressBar != null) {
                        progressBar.setProgress(100, 100);
                    }
                }
                repaint();
                // Sleep for a moment to display 100% progress
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                }
            }

            // Set completion or error message
            setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(),
                    canceled.get() ? Msg.CANCELED : succeeded.get() ? Msg.COMPLETED : Msg.ERROR)));
            repaint();

            // Go to parent screen after a timeout
            waitThenGoToParentScreen(succeeded.get() ? 2000 : 3000);
        });
    }

    private void stopCopying() {
        for (int i = 0; i < otherDrives.size(); i++) {
            rsyncTaskResults.get(i).cancel();
            if (taskExecutors[i] != null) {
                taskExecutors[i].shutdown();
                taskExecutors[i] = null;
            }
            // Mark drive contents as changed, so that size and file listing will be re-generated
            otherDrives.get(i).contentsChanged();
        }
        try {
            // Sync so drive can be pulled out
            Command.command("sudo sync");
        } catch (CancellationException | CommandException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        stopCopying();
    }

    @Override
    public boolean acceptsButtonA() {
        return true;
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.A) {
            // Tell the other thread that errors are due to cancellation
            canceled.set(true);

            // Cancel file listing operation if it hasn't finished yet
            stopCopying();
        }
    }
}
