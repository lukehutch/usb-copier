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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import aobtk.util.Command;
import aobtk.util.TaskExecutor;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor.TaskResult;

public class DiskMonitor {

    private TaskResult<Integer> monitorJob;

    private final Map<String, DriveInfo> partitionDeviceToDriveInfo = new ConcurrentHashMap<>();

    private final Map<String, Long> partitionDeviceToUsed = new ConcurrentHashMap<>();

    private static List<DriveInfo> currDriveList = Collections.emptyList();

    private static final Set<DrivesChangedListener> drivesChangedListeners = new HashSet<>();

    private static final Object drivesChangedListnerLock = new Object();

    private static final TaskExecutor monitoringExecutor = new TaskExecutor();

    public interface DrivesChangedListener {
        public void drivesChanged(List<DriveInfo> currDrives);
    }

    public void addDrivesChangedListener(DrivesChangedListener listener) {
        synchronized (drivesChangedListnerLock) {
            drivesChangedListeners.add(listener);

            // Call listener with initial list of drives
            listener.drivesChanged(currDriveList);
        }
    }

    public void removeDrivesChangedListener(DrivesChangedListener listener) {
        synchronized (drivesChangedListnerLock) {
            drivesChangedListeners.remove(listener);
        }
    }

    public DiskMonitor() {
        try {
            // Start the "udisksctl monitor" thread first (to prevent a race condition between monitoring for changes,
            // plugging or unplugging a drive, and calling "udiskctl dump". (Run it in a separate executor since it
            // will run until the program terminates.)
            UDisksCtlParser monitorParser = new UDisksCtlParser(this, partitionDeviceToDriveInfo);
            monitorJob = Command.commandWithConsumer(new String[] { "sudo", "udisksctl", "monitor" },
                    monitoringExecutor, /* consumeStderr = */ false, monitorParser);

            // Next call "udisksctl dump" once to get the initial list of plugged-in drives.
            try {
                // Create another parser that works on the same deviceNodeToDriveInfo map, but preserves its own state,
                // so that its lines don't end up interleaved with the lines of the other parser above
                UDisksCtlParser dumpParser = new UDisksCtlParser(this, partitionDeviceToDriveInfo);
                for (String line : Command.command(new String[] { "sudo", "udisksctl", "dump" })) {
                    dumpParser.accept(line);
                }
            } catch (InterruptedException e) {
                // Should not happen
                throw new CommandException(e);
            }

        } catch (CommandException e) {
            throw new RuntimeException("Error when attempting to start \"udisksctl monitor\": " + e);
        }
    }

    void driveUnmounted(String partitionDevice) {
        partitionDeviceToUsed.remove(partitionDevice);
    }

    void driveUnplugged(String partitionDevice) {
        System.out.println("Drive unplugged: " + partitionDevice);
        partitionDeviceToDriveInfo.remove(partitionDevice);
        partitionDeviceToUsed.remove(partitionDevice);
    }

    public void drivePlugged(String partitionDevice) {
        System.out.println("Drive plugged in: " + partitionDevice);
    }

    long getUsed(String partitionDevice) {
        Long used = partitionDeviceToUsed.get(partitionDevice);
        return used == null ? -1L : used;
    }

    void setUsed(String partitionDevice, long used) {
        if (used == -1L) {
            partitionDeviceToUsed.remove(partitionDevice);
        } else {
            partitionDeviceToUsed.put(partitionDevice, used);
        }
    }

    /** Notify listeners that a change in drives has been detected. */
    public void drivesChanged() {
        synchronized (drivesChangedListnerLock) {
            // Get current list of drives and sort them into increasing order of port, then decreasing order of size 
            List<DriveInfo> newDriveList = new ArrayList<>(partitionDeviceToDriveInfo.values());
            Collections.sort(newDriveList);

            // Pick the biggest partition for each drive, and skip drives that are not currently mounted
            Set<String> partitionAlreadyAdded = new HashSet<>();
            List<DriveInfo> newDriveListFiltered = new ArrayList<>(newDriveList.size());
            for (DriveInfo di : newDriveList) {
                // Get only the largest partition (first in sorted order for a given port number) 
                if (partitionAlreadyAdded.add(di.driveDevice)) {
                    newDriveListFiltered.add(di);
                }
            }

            // Copy over cached file list and size information from items in old list if available,
            // to prevent having to repeat this work. This is O(N^2), but N should be small.
            for (DriveInfo newDriveInfo : newDriveListFiltered) {
                int oldDriveInfoIdx = currDriveList.indexOf(newDriveInfo);
                if (oldDriveInfoIdx >= 0) {
                    DriveInfo oldDriveInfo = currDriveList.get(oldDriveInfoIdx);
                    newDriveInfo.transferCacheFrom(oldDriveInfo);
                }
            }

            // Set current drive list to new
            currDriveList = newDriveListFiltered;

            // Notify listeners
            for (DrivesChangedListener listener : drivesChangedListeners) {
                listener.drivesChanged(currDriveList);
            }
        }
    }

    public void shutdown() {
        // Shut down "udisksctl monitor" process
        monitorJob.cancel();

        // Clear listener list
        drivesChangedListeners.clear();
    }
}
