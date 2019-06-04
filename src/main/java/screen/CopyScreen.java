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
import java.util.concurrent.ExecutionException;

import aobtk.font.Font;
import aobtk.font.FontStyle.Highlight;
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
import i18n.Msg;
import main.Main;
import util.DriveInfo;
import util.FileInfo;

public class CopyScreen extends DrivesChangedListenerScreen {
    private final DriveInfo selectedDrive;
    private volatile List<DriveInfo> otherDrives;
    private volatile int numFiles;

    private TaskResult<Void> setupTask;

    public CopyScreen(Screen parentScreen, DriveInfo selectedDrive) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;

        // Initial status line to display while recursively reading files
        setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), new Str(Msg.READING, selectedDrive.port))));
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

        if (setupTask != null) {
            // Still working on previous task -- cancel it
            setupTask.cancel();
        }

        setupTask = taskExecutor.submit(() -> {
            // Check if drive is mounted, and if not, mount it
            if (!selectedDrive.isMounted()) {
                int mountResultCode;
                try {
                    mountResultCode = Command.commandWithConsumer(
                            "sudo udisksctl mount --no-user-interaction -b " + selectedDrive.partitionDevice,
                            /* consumeStdErr = */ true, System.out::println).get();
                } catch (CommandException | ExecutionException e) {
                    e.printStackTrace();
                    mountResultCode = 1;
                }
                if (mountResultCode != 0) {
                    // Disk was not successfully mounted
                    System.out.println("Could not mount disk " + selectedDrive.partitionDevice);
                    setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.ERROR)));
                    waitThenGoToParentScreen(3000);
                    throw new IllegalArgumentException("Failed to mount drive");
                }
            }

            // Start the file listing task for the drive, and block on the result 
            List<FileInfo> fileList = selectedDrive.getFileListTask().get();
            numFiles = fileList.size();

            if (!selectedDrive.isMounted() || fileList.isEmpty()) {
                // Nothing to copy -- tell user drive is empty
                setUI(new VLayout(
                        new TextElement(Main.UI_FONT.newStyle(), new Str(Msg.EMPTY, selectedDrive.port))));
                waitThenGoToParentScreen(2000);
                return null;
            }

            // Get space used on selected drive
            long selectedDriveUsed = selectedDrive.getUsed();

            /// Set up drive list table
            TableLayout driveTable = new TableLayout(8, 2);
            driveTable.add(0, new TextElement(Main.UI_FONT.newStyle(), Msg.DEST),
                    new TextElement(Main.UI_FONT.newStyle(), Msg.FREE),
                    new TextElement(Main.UI_FONT.newStyle(), Msg.NEEDED));
            int row = 1;
            List<DriveInfo> _otherDrives = new ArrayList<>();
            for (DriveInfo di : driveInfoList) {
                if (!di.equals(selectedDrive) && di.isMounted()) {
                    long diFree = di.getFree();
                    long insufficientSpace = Math.max(0, selectedDriveUsed - diFree);
                    String insufficientSpaceStr = insufficientSpace == 0L ? "--"
                            : DriveInfo.getInHumanUnits(insufficientSpace);
                    driveTable.add(row, new TextElement(Font.PiOLED_5x8().newStyle(), "#" + di.port),
                            new TextElement(Font.PiOLED_5x8().newStyle(),
                                    di.getFreeInHumanUnits(/* showTotSize = */ false)),
                            new TextElement(Font.PiOLED_5x8().newStyle(), insufficientSpaceStr));
                    _otherDrives.add(di);
                    row++;
                }
            }
            this.otherDrives = _otherDrives;

            // Must have at least one other drive mounted to copy to 
            if (_otherDrives.size() < 1) {
                // Tell user at least one more drive needs to be plugged in
                setUI(new VLayout(new TextElement(Main.UI_FONT.newStyle(), Msg.NEED_2_DRIVES)));
                return null;
            }

            // Create drive list UI
            VLayout layout = new VLayout();
            layout.add(driveTable, VAlign.TOP);

            // Add "button" at bottom indicating that button B will start copying
            layout.add(new TextElement(Main.UI_FONT.newStyle().setHighlight(Highlight.BLOCK),
                    new Str(Msg.START_COPYING, selectedDrive.port)), VAlign.BOTTOM);
            layout.addSpace(1, VAlign.BOTTOM);

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
            if (selectedDrive != null) {
                TaskResult<List<FileInfo>> getFileListTask = selectedDrive.getFileListTask();
                if (getFileListTask != null) {
                    getFileListTask.cancel();
                }
            }
            if (setupTask != null) {
                setupTask.cancel();
            }

            // Move up to parent screen
            goToParentScreen();

        } else if (button == HWButton.B && setupTask.isDone()) {
            List<DriveInfo> _otherDrives = this.otherDrives;
            if (otherDrives != null && !otherDrives.isEmpty()) {
                setCurrScreen(new DoCopyScreen(parentScreen, selectedDrive, numFiles, _otherDrives));
            }
        }
    }
}
