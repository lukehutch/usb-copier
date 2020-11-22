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
package util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for udevil devmon output. */
class DevMonParser implements Consumer<String> {
    private String partitionDevice;
    private String usage;
    private String label;
    private String isMounted;
    private String hasMedia;

    private static final Pattern removedPattern = Pattern.compile("removed: (.*)");
    private static final Pattern fieldPattern = Pattern.compile("(    )?(.*): *\\[(.*)\\]");

    // /etc/mtab escapes in octal(!) -- e.g. space is \040
    private static String unescapeOctal(String path) {
        if (path.indexOf('\\') < 0) {
            return path;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' && i < path.length() - 4 && path.charAt(i + 1) == '0') {
                buf.append((char) ((path.charAt(i + 2) - '0') * 8 + (path.charAt(i + 3) - '0')));
                i += 3;
            } else {
                buf.append(c);
            }
        }
        return buf.toString();
    }

    @Override
    public void accept(String line) {
        Matcher removedMatcher = removedPattern.matcher(line);
        if (removedMatcher.matches()) {
            // e.g.:
            // removed:   /org/freedesktop/UDisks/devices/sdb1

            // N.B. uses a udisks device node -- need to convert to e.g. /dev/sdb1
            String udevDevice = removedMatcher.group(1);
            String partitionDevice = "/dev/" + udevDevice.substring(udevDevice.lastIndexOf("/") + 1);

            // Mark drive as unmounted (since it was removed)
            DiskMonitor.driveUnplugged(partitionDevice);

        } else {
            // e.g.:
            // device: [/dev/sdb1]
            //     systeminternal: [0]
            //     usage:          [filesystem]
            //     type:           [vfat]
            //     label:          [FREEDOS]
            //     ismounted:      [1]
            //     nopolicy:       [0]
            //     hasmedia:       [1]
            //     opticaldisc:    []
            //     numaudiotracks: []
            //     blank:          []
            //     media:          []
            //     partition:      [1]
            Matcher fieldMatcher = fieldPattern.matcher(line);
            if (fieldMatcher.matches()) {
                boolean isIndented = fieldMatcher.group(1) != null;
                String key = fieldMatcher.group(2);
                String val = fieldMatcher.group(3);

                if (!isIndented) {
                    // First line is not indented
                    if (key.equals("device")) {
                        // Remember partition device for when the metadata lines are subsequently read
                        partitionDevice = val;
                        usage = null;
                        label = null;
                        isMounted = null;
                        hasMedia = null;
                    }
                } else {
                    // Collect vals from indented lines
                    switch (key) {
                    case "usage":
                        usage = val;
                        break;
                    case "label":
                        label = val;
                        break;
                    case "ismounted":
                        isMounted = val;
                        break;
                    case "hasmedia":
                        hasMedia = val;
                        break;
                    }
                }
                // When all indented fields are available
                if (partitionDevice != null && usage != null && label != null && isMounted != null
                        && hasMedia != null) {
                    // If drive contains a filesystem
                    if (usage.equals("filesystem")) {
                        if (hasMedia.equals("1")) {
                            if (isMounted.equals("1")) {
                                // Drive is mounted, and all the metadata has been read.
                                // Need to separately find the mountpoint in /etc/mtab, since the "Mount"
                                // line is not present in the devmon output if device is plugged on startup.
                                // https://github.com/IgnorantGuru/udevil/issues/99
                                String mountPoint = null;
                                try {
                                    Optional<String> mtabLine = Files.readAllLines(Paths.get("/etc/mtab")).stream()
                                            .filter(l -> l.startsWith(partitionDevice + " ")).findFirst();
                                    if (mtabLine.isPresent()) {
                                        String[] parts = mtabLine.get().split(" ");
                                        if (parts.length >= 2) {
                                            mountPoint = unescapeOctal(parts[1]);
                                        }
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                                if (mountPoint != null) {
                                    DiskMonitor.driveMounted(partitionDevice, mountPoint, label);
                                } else {
                                    System.out.println("Could not read /etc/mtab");
                                }
                            } else {
                                // Drive is not mounted
                                DiskMonitor.driveUnmounted(partitionDevice);
                            }
                        } else {
                            DiskMonitor.driveUnplugged(partitionDevice);
                        }
                    }
                    partitionDevice = null;
                }
            }
        }
    }
}