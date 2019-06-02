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

import java.util.Map;
import java.util.function.Consumer;

/** Stateful parser for udisksctl output. */
class UDisksCtlParser implements Consumer<String> {
    private DiskMonitor diskMonitor;
    private Map<String, DriveInfo> deviceNodeToDriveInfo;

    private DriveInfo driveInfo = null;
    private int numFieldsReadSinceLastRecordStart = 0;

    private static final String DEVICE_NODE_PREFIX = "/org/freedesktop/UDisks2/block_devices/sd";
    private static final String USB_PORT_PREFIX = ".usb-usb-0:1.";

    public UDisksCtlParser(DiskMonitor diskMonitor, Map<String, DriveInfo> deviceNodeToDriveInfo) {
        this.diskMonitor = diskMonitor;
        this.deviceNodeToDriveInfo = deviceNodeToDriveInfo;
    }

    private static String getFieldVal(String line) {
        int i = line.indexOf(':') + 1;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i == line.length() ? "" : line.substring(i);
    }

    private void endOfDriveChangedRecord() {
        // If drive was not removed
        if (driveInfo != null) {
            // Update drive info
            deviceNodeToDriveInfo.put(driveInfo.partitionDevice, driveInfo);

            // If mount point is unknown, drive is unmounted, so remove size info
            if (driveInfo.mountPoint.isEmpty()) {
                diskMonitor.driveUnmounted(driveInfo.partitionDevice);
            }

            // Reset to look for next drive info record
            driveInfo = null;
        }

        // Notify listeners that drives have changed
        diskMonitor.drivesChanged();

        // Reset to wait for next record
        numFieldsReadSinceLastRecordStart = 0;
    }

    @Override
    public void accept(String line) {
        // System.out.println("UDISKSCTL MONITOR: " + line);
        if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
            // This is a new record start --
            // if there were any fields changed in the previous record, flush the changes.
            // The device /dev/sda1 is removed before /dev/sda, so there will always be another
            // record following immediately after the /dev/sda1 unmount record, causing this
            // code to be reached even though there is no end-of-record marker.
            if (driveInfo != null && numFieldsReadSinceLastRecordStart > 0) {
                // The only way this state can be reached is if the "MountPoints" field was hit
                // during a "Properties Changed" event (it is the only one of the required fields
                // that is present in this case). Copy over the other fields from the old driveInfo
                // object to the new object.
                DriveInfo oldDriveInfo = deviceNodeToDriveInfo.get(driveInfo.partitionDevice);
                if (oldDriveInfo != null) {
                    driveInfo.label = oldDriveInfo.label;
                    driveInfo.size = oldDriveInfo.size;
                    driveInfo.port = oldDriveInfo.port;
                    endOfDriveChangedRecord();
                }
            }

            // Check if this record contains a device node string
            // (if so, it is probably an "Added" / "Removed" / "Properties Changed" record)
            int prefixIdx = line.indexOf(DEVICE_NODE_PREFIX);
            if (prefixIdx >= 0) {
                String partitionDeviceSuffix = line.substring(prefixIdx + DEVICE_NODE_PREFIX.length());
                int colonIdx = partitionDeviceSuffix.indexOf(':');
                if (colonIdx >= 0) {
                    // Strip off ": org.freedesktop.UDisks2.Filesystem: Properties Changed",
                    // which is a separate entry generated once mount operation is complete
                    partitionDeviceSuffix = partitionDeviceSuffix.substring(0, colonIdx);
                }
                // Ignore raw drives, e.g. "/dev/sda", and only allow partitions, e.g. "/dev/sda1"
                if (partitionDeviceSuffix.length() > 1) {
                    // e.g. /dev/sda1
                    String partitionDevice = "/dev/sd" + partitionDeviceSuffix;

                    if (line.contains(": Removed ")) {
                        // Remove device size information
                        diskMonitor.driveUnplugged(partitionDevice);
                        // Force update of drives with record removed
                        // (without setting driveInfo to null here, endOfDriveChangedRecord()
                        // would just add it back again)
                        driveInfo = null;
                        // There are no fields that need reading within this record,
                        // can publish the drive changed event immediately
                        endOfDriveChangedRecord();
                        
                    } else {
                        // Otherwise create a new DeviceInfo instance for the fields.
                        
                        if (line.contains(": Added ")) {
                            // Remove device size information
                            diskMonitor.drivePlugged(partitionDevice);
                        }
                        
                        // Create new DriveInfo object
                        driveInfo = new DriveInfo(partitionDevice);

                        // e.g. /dev/sda
                        String rawDeviceNode = "/dev/sd" + partitionDeviceSuffix.charAt(0);
                        driveInfo.driveDevice = rawDeviceNode;
                    }
                }
            }
        }
        if (driveInfo != null) {
            // Look for drive info fields
            if (line.startsWith("    IdLabel:")) {
                driveInfo.label = getFieldVal(line);
                numFieldsReadSinceLastRecordStart++;
            }
            if (line.startsWith("    MountPoints:") || line.startsWith("  MountPoints:")) { // TODO: this is plural?
                // If drive is not yet mounted (right after drive has been plugged in, but before the
                // udisks job has finished mounting the drive, or after the drive has been unmounted),
                // the mountpoint will be "".
                driveInfo.mountPoint = getFieldVal(line);
                numFieldsReadSinceLastRecordStart++;
            }
            if (line.startsWith("    Size:")) {
                driveInfo.size = Long.valueOf(getFieldVal(line));
                numFieldsReadSinceLastRecordStart++;
            }
            int usbIdx = line.indexOf(USB_PORT_PREFIX);
            if (usbIdx > 0) {
                int startIdx = usbIdx + USB_PORT_PREFIX.length();
                int endIdx = line.indexOf(':', startIdx + 1);
                if (endIdx < 0) {
                    endIdx = line.length();
                }
                driveInfo.port = Integer.valueOf(line.substring(startIdx, endIdx));
                numFieldsReadSinceLastRecordStart++;
            }
            if (numFieldsReadSinceLastRecordStart == 4) {
                // There is no end record marker, so once all 5 of the above fields have
                // been read, flush out the changes
                endOfDriveChangedRecord();
            }
        }
    }
}