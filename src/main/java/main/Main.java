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

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import aobtk.font.Font;
import aobtk.hw.Bonnet;
import aobtk.ui.screen.Screen;
import exec.Exec;
import screen.ChooseLangScreen;
import sun.misc.Signal;
import util.DiskMonitor;

public class Main {

    public static DiskMonitor diskMonitor;

    // Font for filenames that may include Korean / Chinese chars.
    // Needs to be a font with Latin1, CJK ideograph, and Hangeul support.
    // Choices: Font.WQY_Song_16(), Font.GNU_Unifont_16, Font.WQY_Unibit_16
    public static volatile Font CJK = Font.WQY_Song_16();

    // Main UI font
    public static volatile Font UI_FONT = Font.WQY_Song_16();

    static {
        // Enable logging
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT %1$tL] [%4$-7s] %5$s %n");
        Logger root = Logger.getLogger("");
        root.setLevel(Level.ALL);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.ALL);
        }
    }

    public static void main(String[] args) throws Exception {
        // Processes started with sudo can't be stopped by Java for some reason:
        // https://stackoverflow.com/questions/65111915
        // Exec.prependCommand = new String[] { "sudo" };

        // Start by immediately displaying a "Please Wait" screen
        // (it takes time to load the fonts, start the disk monitor, etc.)
        Bonnet.scheduleDraw(Bonnet.render(SavePleaseWaitScreen.getSavedBitBuffer()));

        // Start drive listener
        diskMonitor = new DiskMonitor();

        // Initialize root screen
        Screen.init(new ChooseLangScreen());

        // Perform clean shutdown on Ctrl-C
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("Main shutdown hook");
                shutdown();
            }
        });

        Signal.handle(new Signal("INT"), signal -> {
            shutdown();
            System.exit(0);
        });

        // Keep program running until termination
        for (;;) {
            try {
                Thread.sleep(1000_000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public static void shutdown() {
        // Stop monitoring for changes in plugged drives
        DiskMonitor.shutdown();
        // Shut down all task executors
        Exec.shutdown();
        // Shut down hardware
        Bonnet.shutdown();
    }
}
