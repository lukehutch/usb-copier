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
package main;

import java.math.BigInteger;

import aobtk.font.Font;
import aobtk.font.FontStyle;
import aobtk.hw.Bonnet;
import aobtk.hw.HWButton;
import aobtk.oled.Display;
import aobtk.oled.OLEDDriver;
import aobtk.ui.element.TextElement;
import aobtk.ui.element.VLayout;
import aobtk.ui.screen.Screen;

public class SavePleaseWaitScreen {
    // The result of running this program to save the "Please Wait" screen as a bitmap
    private static final String SAVED_SCREEN_BIT_BUFFER = //
            "fc4444444438000002fe000000e090909090e0002090909090e00060909010102000e090909090e0"
                    + "000000000000f00000e00000f0002090909090e0000010f60000002020fc20200000000000000000"
                    + "00000000000000000000000000000000000000000000000000000000000000000000000000000000"
                    + "00000000000000000f00000000000008080f0808000708080808040007080808040f000408080909"
                    + "0600070808080804000000000000070808070808070007080808040f0008080f0808000000070808"
                    + "00000000000000000000000000000000000000000000000000000000000000000000000000000000"
                    + "0000000000001010101010f0000000fc00f0101010101010100000fc80808000909090909090f000"
                    + "00404040fc00000004048444649c040000008080402038408000000000fe0000000000c030000000"
                    + "0000fc00000038448282828282443800000000000000000000000000000000000000000000000000"
                    + "00000000000000000000000000001010080403000000007f00070404040404040400007f00000000"
                    + "0f08080808080800000202027f0004040505047c04040504040000000000007c50505050507d0004"
                    + "040201000102040000007f002020202020203c202020202020000000000000000000000000000000"
                    + "00000000000000000000000000000000000000000000000000000000000830000010505050fc5050"
                    + "50100000088888c8b888888848404040fc4040000000000000800810600000000000000020108c88"
                    + "98a888e8908c8898a808080000402010cc00202020fc202020200000000000000000000000000000"
                    + "0000000000000000000000000000000000000000000000000000000000000000000000000101ff80"
                    + "4001fd5555555555fd01010000ff00ff00ff00ff00041800ff00000040300e0000ff0000000000e0"
                    + "010638000424242464a424272424f42424240400080402ff000929c9090909ff0909090000000000"
                    + "00000000000000000000000000000000000000000000000000000000000000000000000000000000"
                    + "00000000000001000000030000000102010000000003000000000203000001020100000000000000"
                    + "00000101010101010000000000000000000100000102010000000000000000030000000000010201"
                    + "00000000000000000000000000000000000000000000000000000000";

    /** Get the saved "Please Wait" screen as a bit buffer. */
    public static byte[] getSavedBitBuffer() {
        byte[] arr = new BigInteger(SAVED_SCREEN_BIT_BUFFER, 16).toByteArray();
        // BigInteger strips off leading zeros, so need to pad the left side of the array
        byte[] bitBuffer = new byte[OLEDDriver.DISPLAY_WIDTH * OLEDDriver.DISPLAY_HEIGHT / 8];
        int offset = bitBuffer.length - arr.length;
        for (int i = offset; i < bitBuffer.length; i++) {
            bitBuffer[i] = arr[i - offset];
        }
        return bitBuffer;
    }

    public static void main(String[] args) throws Exception {
        // Initialize root screen
        Screen.init(new Screen(null) {
            @Override
            public void open() {
                VLayout layout = new VLayout();
                FontStyle style = Font.GNU_Unifont_16().newStyle();
                layout.add(new TextElement(style, "Please wait"));
                layout.addSpace(2);
                layout.add(new TextElement(style, "기다려주십시오"));
                layout.addSpace(2);
                layout.add(new TextElement(style, "请耐心等待"));
                setUI(layout);
            }

            @Override
            public void buttonDown(HWButton button) {
            }
        });

        Thread.sleep(5000);

        byte[] bitBuffer = Display.newBitBuffer();

        String screenContents = new BigInteger(bitBuffer).toString(16);

        for (int i = 0; i < screenContents.length(); i += 80) {
            System.out.println((i > 0 ? "+ " : "") + "\""
                    + screenContents.substring(i, Math.min(i + 80, screenContents.length())) + "\"");
        }

        Bonnet.shutdown();
    }
}
