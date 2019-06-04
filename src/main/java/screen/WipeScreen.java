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

import java.util.List;

import aobtk.font.Font;
import aobtk.font.FontStyle;
import aobtk.hw.HWButton;
import aobtk.ui.element.Menu;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.element.VLayout.VAlign;
import aobtk.ui.screen.Screen;
import util.DriveInfo;

public class WipeScreen extends DrivesChangedListenerScreen {
    private final DriveInfo selectedDrive;
    private volatile boolean isWiping;

    private Menu wipeMenu;
    private TextElement warningText;

    public WipeScreen(Screen parentScreen, DriveInfo selectedDrive) {
        super(parentScreen);

        this.selectedDrive = selectedDrive;

        VLayout layout = new VLayout();

        // Add drive info at top
        layout.addSpace(1, VAlign.TOP);
        layout.add(new TextElement(Font.PiOLED_5x8().newStyle(), selectedDrive.toStringShort()), VAlign.TOP);

        // "Wipe Method:"
        layout.add(new TextElement(Font.GNU_Unifont_16().newStyle(), Msg.WIPE_METHOD), VAlign.TOP);
        layout.addSpace(1, VAlign.TOP);
        // "Quick" / "Secure"
        wipeMenu = new Menu(Font.GNU_Unifont_16().newStyle(), 4, /* hLayout = */ true, Msg.QUICK, Msg.SECURE);
        layout.add(wipeMenu, VAlign.TOP);

        // Add "ERASE ALL?" warning text at bottom, initially hidden
        warningText = new TextElement(Font.GNU_Unifont_16().newStyle().setHighlight(FontStyle.Highlight.HALO), Msg.ERASE_ALL_WARNING);
        warningText.hide(true);
        layout.add(warningText, VAlign.BOTTOM);

        setUI(layout);
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.L) {
            wipeMenu.decSelectedIdx();
        } else if (button == HWButton.R) {
            wipeMenu.incSelectedIdx();
        } else if ((button == HWButton.B || button == HWButton.C)) {
            if (warningText.isHidden()) {
                // Have to confirm twice -- the first time just show a warning
                warningText.hide(false);
            } else {
                // The second time, start the wipe
                isWiping = true;
                setCurrScreen(new DoWipeScreen(
                        // Make DoWipeScreen's parent screen the parent of this,
                        // so that after DoWipeScreen has finished, it goes back
                        // to the main menu, rather than this screen
                        parentScreen, //
                        selectedDrive, /* isQuick = */wipeMenu.getSelectedIdx() == 0));
            }
        }
    }

    @Override
    public void drivesChanged(List<DriveInfo> drivesList) {
        // Have to check if wiping has started, otherwise there's a race condition where the replacement
        // DoWipeScreen triggers drivesChanged on this screen before this screen is replaced.
        if (!isWiping && !drivesList.contains(selectedDrive)) {
            // The selected drive was unplugged
            goToParentScreen();
        }
    }
}
