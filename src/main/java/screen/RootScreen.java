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

import aobtk.font.Font;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.ui.element.Menu;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.element.VLayout.VAlign;
import i18n.Msg;
import main.Main;
import util.DriveInfo;

public class RootScreen extends DrivesChangedListenerScreen {
    private volatile List<DriveInfo> drivesList = Collections.emptyList();
    private volatile DriveInfo selectedDrive = null;
    private volatile int lastKnownSelectedDriveIdx = -1;

    private volatile Menu driveMenu;
    private volatile TextElement noDrivesText;
    private volatile Menu actionMenu;

    public RootScreen() {
        super(/* parentScreen = */ null);

        VLayout layout = new VLayout();

        // Add drives menu
        layout.addSpace(1, VAlign.TOP);
        driveMenu = new Menu(Font.PiOLED_5x8().newStyle(), 2, /* hLayout = */ false, new Str[0]);
        layout.add(driveMenu, VAlign.TOP);

        // Add "Insert USB Drives" text (hidden by default)
        noDrivesText = new TextElement(Main.UI_FONT.newStyle(), Msg.NO_DRIVES);
        noDrivesText.hide(true);
        layout.add(noDrivesText, VAlign.CENTER);

        actionMenu = new Menu(Main.UI_FONT.newStyle(), 6, /* hLayout = */ true, new Str[0]);
        layout.add(actionMenu, VAlign.BOTTOM);
        layout.addSpace(1, VAlign.BOTTOM);

        setUI(layout);
    }

    @Override
    public void drivesChanged(List<DriveInfo> drivesList) {
        // Only update UI when not repainting
        synchronized (uiLock) {
            // Update UI with drives in list
            this.drivesList = drivesList;
            driveMenu.clear();
            if (!drivesList.isEmpty()) {
                // Add UI element for each drive
                boolean selectedDriveFound = false;
                for (int i = 0; i < drivesList.size(); i++) {
                    DriveInfo di = drivesList.get(i);
                    driveMenu.add(di.toStringShort());
                    if (di.equals(selectedDrive) || selectedDrive == null) {
                        // Previously selected drive was found in new drive list --
                        // remember its index in new list
                        selectDriveIdx(drivesList, i);
                        selectedDriveFound = true;
                    }
                }

                // Select selected drive, or closest item if it is no longer present
                if (!selectedDriveFound) {
                    if (lastKnownSelectedDriveIdx >= 0 && lastKnownSelectedDriveIdx < drivesList.size()) {
                        // If selected drive was not found, use last known selected drive index
                        // (so that selection stays in same place if drive is unplugged while
                        // it is selected)
                        selectDriveIdx(drivesList, lastKnownSelectedDriveIdx);
                    } else if (selectedDrive == null) {
                        // Select first drive if none was previously selected
                        if (drivesList != null && !drivesList.isEmpty()) {
                            selectDriveIdx(drivesList, 0);
                        }
                    }
                }

                // Only add Copy item to action menu if there are at least two drives plugged in

                Str prevSelectedAction = actionMenu.getSelectedItem();
                actionMenu.clear();
                if (drivesList.size() > 1) {
                    actionMenu.add(Msg.COPY);
                }
                actionMenu.add(Msg.VIEW);
                if (prevSelectedAction == Msg.VIEW) {
                    actionMenu.setSelectedItem(Msg.VIEW);
                }
                actionMenu.add(Msg.WIPE);
                if (prevSelectedAction == Msg.WIPE) {
                    actionMenu.setSelectedItem(Msg.WIPE);
                }

                // Hide "Insert USB Drive", and show action menu
                noDrivesText.hide(true);
                actionMenu.hide(false);

            } else {
                // No drives are plugged in
                selectDriveIdx(drivesList, -1);

                // Show "Insert USB Drive", and hide action menu
                noDrivesText.hide(false);
                actionMenu.hide(true);
            }
            repaint();
        }
    }

    private void selectDriveIdx(List<DriveInfo> currDrives, int idx) {
        synchronized (uiLock) {
            selectedDrive = idx >= 0 && idx < currDrives.size() ? currDrives.get(idx) : null;
            lastKnownSelectedDriveIdx = selectedDrive != null ? idx : -1;
            driveMenu.setSelectedIdx(lastKnownSelectedDriveIdx);
        }
    }

    private void selectNextOrPrevDrive(boolean next) {
        synchronized (uiLock) {
            // Take a snapshot of drives, since the drives list may be changed by another thread
            List<DriveInfo> currDrives = drivesList;

            if (currDrives == null || currDrives.isEmpty()) {
                selectDriveIdx(currDrives, -1);
            } else {
                int selectedDriveIdx = -1;
                if (selectedDrive != null) {
                    selectedDriveIdx = currDrives.indexOf(selectedDrive);
                    if (selectedDriveIdx < 0) {
                        // If a drive disappeared, reuse the last-known index
                        selectedDriveIdx = lastKnownSelectedDriveIdx;
                    }
                    if (selectedDriveIdx >= currDrives.size()) {
                        // Drive disappeared from end of list
                        selectedDriveIdx = currDrives.size() - 1;
                    }
                }
                int newSelectedDriveIdx;
                if (selectedDriveIdx < 0) {
                    // Select first or last drive in list if one could not be selected
                    newSelectedDriveIdx = next ? 0 : currDrives.size() - 1;
                } else {
                    // Select the next or previous drive in the list
                    newSelectedDriveIdx = selectedDriveIdx + (next ? 1 : -1);
                    // Don't go off top or bottom of the list
                    if (newSelectedDriveIdx < 0) {
                        newSelectedDriveIdx = 0;
                    } else if (newSelectedDriveIdx >= currDrives.size()) {
                        newSelectedDriveIdx = currDrives.size() - 1;
                    }
                }
                selectDriveIdx(currDrives, newSelectedDriveIdx);
            }
            repaint();
        }
    }

    @Override
    public void buttonDown(HWButton button) {
        // Otherwise handle single button presses
        if (button == HWButton.L) {
            // Left
            actionMenu.decSelectedIdx();
            repaint();
        } else if (button == HWButton.R) {
            // Right
            actionMenu.incSelectedIdx();
            repaint();
        } else if ((button == HWButton.B || button == HWButton.C) && selectedDrive != null) {
            // Either B or C work for select
            Str action = actionMenu.getSelectedItem();
            if (action == Msg.COPY) {
                setCurrScreen(new CopyScreen(this, selectedDrive));
            } else if (action == Msg.VIEW) {
                setCurrScreen(new ViewScreen(this, selectedDrive));
            } else if (action == Msg.WIPE) {
                setCurrScreen(new WipeScreen(this, selectedDrive));
            }
        } else if (button == HWButton.U) {
            // Up
            selectNextOrPrevDrive(/* next = */ false);
        } else if (button == HWButton.D) {
            // Down
            selectNextOrPrevDrive(/* next = */ true);
        }
    }
}
