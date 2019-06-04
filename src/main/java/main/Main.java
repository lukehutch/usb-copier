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

import aobtk.font.Font;
import aobtk.hw.Bonnet;
import aobtk.ui.screen.Screen;
import screen.ChooseLangScreen;
import util.DiskMonitor;

public class Main {

    public static DiskMonitor diskMonitor;
    
    // Main font should have Latin1, CJK ideograph, and Hangeul support
    // (choices: Font.WQY_Song_16(), Font.GNU_Unifont_16, Font.WQY_Unibit_16)
    public static Font FONT = Font.WQY_Song_16();

    public static void main(String[] args) throws Exception {
        // Start drive listener
        diskMonitor = new DiskMonitor();

        // Initialize the Button class, and register the GPIO event listeners
        Bonnet.init();

        // Initialize root screen
        Screen.init(new ChooseLangScreen());

        // Perform clean shutdown on Ctrl-C
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Stop monitoring for changes in plugged drives
                diskMonitor.shutdown();
            }
        });

        // Keep program running until termination
        for (;;) {
            try {
                Thread.sleep(1000_000);
            } catch (InterruptedException e) {
                // You can also kill the program by interrupting the main thread
                // (not currently used)
                System.out.println("Main thread was interrupted -- shutting down");
                System.exit(1);
            }
        }
    }
}
