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

import aobtk.font.Font;
import aobtk.hw.HWButton;
import aobtk.i18n.Str;
import aobtk.ui.element.Menu;
import aobtk.ui.element.VLayout;
import aobtk.ui.element.VLayout.VAlign;
import aobtk.ui.screen.Screen;
import main.Main;

public class ChooseLangScreen extends Screen {
    private volatile Menu langMenu;

    public ChooseLangScreen() {
        super(/* parentScreen = */ null);
    }

    @Override
    public void open() {
        VLayout layout = new VLayout();

        langMenu = new Menu(Font.GNU_Unifont_16().newStyle(), 2, /* hLayout = */ false, "English", "조선말", "中文");
        layout.add(langMenu, VAlign.CENTER);
        langMenu.setSelectedIdx(1);

        setUI(layout);
    }

    @Override
    public void buttonDown(HWButton button) {
        if (button == HWButton.U) {
            // Left
            langMenu.decSelectedIdx();
            repaint();
        } else if (button == HWButton.D) {
            // Right
            langMenu.incSelectedIdx();
            repaint();
        } else if ((button == HWButton.B || button == HWButton.C)) {
            // Select the language, and move to RootScreen
            Str.lang = langMenu.getSelectedIdx();
            if (Str.lang == 1) {
                // Switch to NeoDGM for Korean
                // Main.UI_FONT = Font.NeoDGM_16();
            } else if (Str.lang == 2) {
                // Chinese needs a little more space
                Main.UI_FONT.getDefaultStyle().setPadX(2);
                Main.UI_FONT.getDefaultStyle().setPadY(2);
            }
            setCurrScreen(new RootScreen());
        }
    }
}
