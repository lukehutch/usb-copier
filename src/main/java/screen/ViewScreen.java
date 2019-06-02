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

import aobtk.font.Font;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.oled.Display;
import aobtk.oled.OLEDDriver;
import aobtk.oled.Display.Highlight;
import aobtk.ui.element.FullscreenUIElement;
import aobtk.ui.screen.Screen;
import aobtk.util.Command;
import aobtk.util.TaskExecutor.TaskResult;
import util.DriveInfo;
import util.FileInfo;

public class ViewScreen extends DrivesChangedListenerScreen {
    private final DriveInfo selectedDrive;

    private volatile List<String> textLines = new ArrayList<>();
    private volatile int viewLineIdx = 0;

    private volatile int viewX = 0;
    private static final int VIEW_X_STEP = Font.FONT_NEODGM.getOuterWidth(' ') * 4;
    private static final int NUM_SCREEN_ROWS = OLEDDriver.DISPLAY_HEIGHT / Font.FONT_NEODGM.getOuterHeight();

    private TaskResult<Void> fileListingTask;

    public ViewScreen(Screen parentScreen, DriveInfo selectedDrive) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;

        // Initial status line to display while recursively reading files
        textLines.add(
                new Str("Reading #" + selectedDrive.port + "⠤", "#" + selectedDrive.port + "를 읽고있다⠤").toString());

        // The screen drawing UIElement
        setUI(new FullscreenUIElement() {
            @Override
            public void renderFullscreen(Display display) {
                List<String> currLines = textLines;
                int currViewLineIdx = viewLineIdx;
                int currViewX = viewX;
                for (int i = 0, y = 0; i < NUM_SCREEN_ROWS; i++) {
                    int lineIdx = currViewLineIdx + i;
                    String line = lineIdx >= 0 && lineIdx < currLines.size() ? currLines.get(lineIdx) : "";
                    boolean isHeaderLine = lineIdx == 0;
                    y += Font.FONT_NEODGM.drawString(line, currViewX + (isHeaderLine ? 1 : 0), y, true,
                            // Highlight first line
                            isHeaderLine ? Highlight.BLOCK : Highlight.NONE, display).h;
                }
            }
        });
    }

    @Override
    public boolean acceptsButtonA() {
        return true;
    }

    private void updateTextLines(List<String> textLines) {
        this.textLines = textLines;
        repaint();
    }

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
        DriveInfo driveInfo = foundDrive;

        // If the drive is no longer plugged in -- go to parent screen
        if (foundDrive == null) {
            goToParentScreen();
            return;
        }

        if (fileListingTask != null) {
            // Still working on previous task -- cancel it
            fileListingTask.cancel();
        }

        fileListingTask = taskExecutor.submit( //
                // Check if drive is mounted, and if not, mount it
                () -> {
                    if (!driveInfo.isMounted()) {
                        TaskResult<Integer> mountResultCode = Command.commandWithConsumer(
                                "sudo udisksctl mount --no-user-interaction -b " + selectedDrive.partitionDevice,
                                System.out::println, /* consumeStdErr = */ true);
                        if (mountResultCode.get() != 0) {
                            // Disk was not successfully mounted
                            System.out.println("Could not mount disk " + selectedDrive.partitionDevice);
                            throw new IllegalArgumentException("Failed to mount drive");
                        }
                    }
                    return null;
                })

                // Then convert file listing into text lines
                .then(ignored -> {
                    // Recursively walk the directory tree for the drive 
                    List<FileInfo> fileListing = driveInfo.getFileListTask().get();

                    // Successfully got file listing -- insert header line with file count
                    List<String> lines = new ArrayList<>();
                    int numFiles = fileListing.size();
                    lines.add(0, "#" + driveInfo.port + ": " + new Str("", "파일 ") + numFiles
                            + new Str(numFiles == 1 ? " file" : " files", "개"));

                    // Add lines for each FileInfo object
                    for (FileInfo fi : fileListing) {
                        lines.add(fi.toString());
                    }

                    // Update UI
                    updateTextLines(lines);
                })

                // If either of the above tasks is canceled, then also cancel recursive file listing task
                .onCancel(() -> {
                    driveInfo.getFileListTask().cancel();
                })

                // Display error message if anything went wrong
                .onException(e -> {
                    e.printStackTrace();
                    List<String> lines = new ArrayList<>();
                    lines.add(new Str("Can't read #" + driveInfo.port, "#" + driveInfo.port + "를 읽을 수 없다")
                            .toString());
                    lines.add("(" + driveInfo.label + ")");

                    // Update UI
                    updateTextLines(lines);
                });
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.A) {
            // Move up to parent screen (will cancel currently-running tasks)
            goToParentScreen();

        } else if (fileListingTask != null && fileListingTask.isDone()) {
            // Handle directional buttons only if file listing operation is finished
            if (button == HWButton.U && viewLineIdx > 0) {
                viewLineIdx--;
            } else if (button == HWButton.D && viewLineIdx < textLines.size() - NUM_SCREEN_ROWS) {
                viewLineIdx++;
            } else if (button == HWButton.L && viewX < 0) {
                viewX += VIEW_X_STEP;
            } else if (button == HWButton.R) {
                viewX -= VIEW_X_STEP;
            }
        }
    }
}
