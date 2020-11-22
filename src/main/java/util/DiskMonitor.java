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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor;
import aobtk.util.TaskExecutor.TaskResult;

public class DiskMonitor {

    private static final TaskExecutor monitoringExecutor = new TaskExecutor();
    private static TaskResult<Integer> monitorJob;

    static {
        try {
            // Start the devmon thread (run in a separate executor since it will run until the program terminates.)
            monitorJob = Command.commandWithConsumer(new String[] { "sudo", "-u", "pi", "devmon" },
                    monitoringExecutor, /* consumeStderr = */ false, new DevMonParser());
        } catch (CommandException e) {
            throw new RuntimeException("Error when attempting to start devmon: " + e);
        }
    }

    private static final Map<String, DriveInfo> partitionDeviceToDriveInfo = new ConcurrentHashMap<>();

    private static volatile List<DriveInfo> currDriveList = Collections.emptyList();

    private static final Set<DrivesChangedListener> drivesChangedListeners = new HashSet<>();

    private static final Object drivesChangedListnerLock = new Object();

    static final TaskExecutor taskExecutor = new TaskExecutor();

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

    private static DriveInfo getOrCreateDriveInfo(String partitionDevice) {
        return partitionDeviceToDriveInfo.computeIfAbsent(partitionDevice, p -> new DriveInfo(partitionDevice));
    }

    /** Drive is mounted and drive metadata has been read. */
    static void driveMounted(String partitionDevice, String mountPoint, String label) {
        DriveInfo driveInfo = getOrCreateDriveInfo(partitionDevice);
        driveInfo.mountPoint = mountPoint;
        driveInfo.label = label;
        driveInfo.isPluggedIn = true;
        driveInfo.isMounted = true;

        // Use device letter as port. TODO: get USB port from /proc
        driveInfo.port = driveInfo.rawDriveDevice.charAt(driveInfo.rawDriveDevice.length() - 1) - 'a';

        // Call df to get drive sizes (updated asynchronously)
        driveInfo.diskSize = -1L;
        driveInfo.diskSpaceUsed = -1L;
        driveInfo.updateDriveSizes(); // Calls drivesChanged() if successful

        System.out.println("Drive mounted: " + driveInfo);
    }

    /** Drive was unplugged */
    static void driveUnplugged(String partitionDevice) {
        DriveInfo driveInfo = getOrCreateDriveInfo(partitionDevice);
        driveInfo.mountPoint = "";
        driveInfo.label = "";
        driveInfo.port = 0;
        driveInfo.isPluggedIn = false;
        driveInfo.isMounted = false;
        driveInfo.diskSize = -1L;
        driveInfo.diskSpaceUsed = -1L;
        drivesChanged();

        System.out.println("Drive unplugged: " + driveInfo);
    }

    /** Drive was unmounted */
    static void driveUnmounted(String partitionDevice) {
        DriveInfo driveInfo = getOrCreateDriveInfo(partitionDevice);
        driveInfo.isMounted = false;
        driveInfo.mountPoint = "";
        // Leave diskSize and diskSpaceUsed with the values they had before drive was unmounted,
        // so that drive can be unmounted after a copy (to prevent the dirty bit being set if the
        // drive is unplugged), but sizes can still be shown.
        drivesChanged();

        System.out.println("Drive unmounted: " + driveInfo);
    }

    public static void remountAll() {
        sync();
        unmountAll();
        mountAll();
    }

    public static void sync() {
        try {
            Command.command("sync");
        } catch (InterruptedException | CommandException e) {
            System.out.println("Could not sync: " + e);
        }
    }

    public static void unmountAll() {
        try {
            Command.command("sudo", "devmon", "--unmount-all");
        } catch (CommandException e) {
            System.out.println("Could not unmount all drives: " + e);
        } catch (InterruptedException | CancellationException e) {
        }
    }

    public static void mountAll() {
        try {
            Command.command("sudo", "-u", "pi", "devmon", "--mount-all");
        } catch (CommandException e) {
            // Ignore -- for some reason this always returns exit code 3
            // https://github.com/IgnorantGuru/udevil/issues/100
        } catch (InterruptedException | CancellationException e) {
        }
    }

    /** Notify listeners that a change in drives has been detected. */
    static void drivesChanged() {
        synchronized (drivesChangedListnerLock) {
            // Get current list of drives and sort them into increasing order of port, then decreasing order of size 
            List<DriveInfo> newDriveList = new ArrayList<>(partitionDeviceToDriveInfo.values());
            Collections.sort(newDriveList);

            // Pick the biggest partition for each drive, and skip drives that are not currently mounted
            Set<String> partitionAlreadyAdded = new HashSet<>();
            List<DriveInfo> newDriveListFiltered = new ArrayList<>(newDriveList.size());
            for (DriveInfo di : newDriveList) {
                System.out.println("Drive: " + di);
                // Only add drives that are plugged in
                if (di.isPluggedIn()) {
                    // Get only the largest partition (first in sorted order for a given port number) 
                    if (partitionAlreadyAdded.add(di.rawDriveDevice)) {
                        newDriveListFiltered.add(di);
                        System.out.println("  Drive plugged in: " + di);
                    }
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

    public static void shutdown() {
        // Shut down devmon process
        monitorJob.cancel();

        // Clear listener list
        drivesChangedListeners.clear();

        monitoringExecutor.shutdown();
        taskExecutor.shutdown();
    }
}
