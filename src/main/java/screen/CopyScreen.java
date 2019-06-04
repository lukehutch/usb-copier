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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import aobtk.font.Font;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.ui.element.TableLayout;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.element.VLayout.VAlign;
import aobtk.ui.screen.Screen;
import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor.TaskResult;
import main.Main;
import util.DriveInfo;
import util.FileInfo;

public class CopyScreen extends DrivesChangedListenerScreen {
    private TextElement start;

    private final DriveInfo selectedDrive;
    private final Set<String> mountPointsToWipe = Collections
            .newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private TaskResult<Void> fileListingTask;

    public CopyScreen(Screen parentScreen, DriveInfo selectedDrive) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;

        // Initial status line to display while recursively reading files
        setUI(new VLayout(
                new TextElement(Main.FONT.newStyle(), new Str(Msg.READING, selectedDrive.port))));
    }

    @Override
    public void drivesChanged(List<DriveInfo> driveInfoList) {
        // Look for drive in list that has the same partition device as the selected drive
        // (can't use equals() because that checks the mount point, and the drive might
        // not be mounted)
        DriveInfo foundDrive = null;
        for (DriveInfo driveInfo : driveInfoList) {
            if (driveInfo.partitionDevice.equals(selectedDrive.partitionDevice)) {
                foundDrive = driveInfo;
                break;
            }
        }
        // If the drive is no longer plugged in -- go to parent screen
        if (foundDrive == null) {
            goToParentScreen();
            return;
        }

        if (fileListingTask != null) {
            // Still working on previous task -- cancel it
            fileListingTask.cancel();
        }

        fileListingTask = taskExecutor.submit(() -> {
            // Check if drive is mounted, and if not, mount it
            if (!selectedDrive.isMounted()) {
                int mountResultCode;
                try {
                    mountResultCode = Command.commandWithConsumer(
                            "sudo udisksctl mount --no-user-interaction -b " + selectedDrive.partitionDevice,
                            System.out::println, /* consumeStdErr = */ true).get();
                } catch (CommandException | ExecutionException e) {
                    e.printStackTrace();
                    mountResultCode = 1;
                }
                if (mountResultCode != 0) {
                    // Disk was not successfully mounted
                    System.out.println("Could not mount disk " + selectedDrive.partitionDevice);
                    setUI(new VLayout(new TextElement(Main.FONT.newStyle(), Msg.ERROR)));
                    waitThenGoToParentScreen(3000);
                    throw new IllegalArgumentException("Failed to mount drive");
                }
            }

            // Start the file listing task for the drive, and block on the result 
            List<FileInfo> fileList = selectedDrive.getFileListTask().get();

            if (fileList.isEmpty()) {
                // Nothing to copy
                setUI(new VLayout(
                        new TextElement(Main.FONT.newStyle(), new Str(Msg.EMPTY, selectedDrive.port))));
                waitThenGoToParentScreen(2000);
                return null;
            }

            int numOtherDrives = driveInfoList.size() - (driveInfoList.contains(selectedDrive) ? 1 : 0);
            if (numOtherDrives < 1) {
                // Two or more drives need to be plugged in
                setUI(new VLayout(new TextElement(Main.FONT.newStyle(), Msg.NEED_2_DRIVES)));
                return null;
            }

            VLayout layout = new VLayout();

            layout.add(new TextElement(Main.FONT.newStyle(),
                    new Str(Msg.NUM_FILES, selectedDrive.port, fileList.size())), VAlign.TOP);

            TableLayout driveTable = new TableLayout(4, 1);
            driveTable.add(0, new TextElement(Main.FONT.newStyle(), Msg.DEST),
                    new TextElement(Main.FONT.newStyle(), Msg.FREE),
                    new TextElement(Main.FONT.newStyle(), Msg.WIPEQ));
            int row = 1;
            for (DriveInfo di : driveInfoList) {
                if (!di.equals(selectedDrive)) {
                    driveTable.add(row, new TextElement(Font.PiOLED_5x8().newStyle(), "#" + di.port),
                            new TextElement(Font.PiOLED_5x8().newStyle(),
                                    di.getFreeInHumanUnits(/* showTotSize = */ false)),
                            new TextElement(Font.PiOLED_5x8().newStyle(),
                                    mountPointsToWipe.contains(di.mountPoint) ? "*" : "-"));

                    row++;
                }
            }
            layout.add(driveTable, VAlign.TOP);

            // Add button to start copying
            layout.add(start = new TextElement(Main.FONT.newStyle(),
                    new Str(Msg.COPY_FROM, selectedDrive.port)), VAlign.BOTTOM);

            setUI(layout);
            return null;
        });
    }

    @Override
    public boolean acceptsButtonA() {
        return true;
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.A) {
            // Cancel file listing operation, if it hasn't finished yet
            fileListingTask.cancel();

            // Move up to parent screen
            goToParentScreen();

        } else if (fileListingTask.isDone()) {
            //            // Handle directional buttons only if file listing operation is finished
            //            if (button == HWButton.U && viewLineIdx > 0) {
            //                viewLineIdx--;
            //            } else if (button == HWButton.D && viewLineIdx < dispLines.size() - NUM_DISP_ROWS) {
            //                viewLineIdx++;
            //            } else if (button == HWButton.L && viewX < 0) {
            //                viewX += VIEW_X_STEP;
            //            } else if (button == HWButton.R) {
            //                viewX -= VIEW_X_STEP;
            //            }

            // TODO: before starting copying, set isCopying to true
            // TODO: start copying when button pressed
            // TODO: run sync() after copying has finished

        }
    }
}
