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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor;
import aobtk.util.TaskExecutor.TaskResult;

public class DriveInfo implements Comparable<DriveInfo> {
    /** The partition device, e.g. "/dev/sda1". */
    public final String partitionDevice;

    /** The raw drive device, e.g. "/dev/sda". */
    public volatile String rawDriveDevice = "";

    /** True if the drive is plugged in, whether or not it's mounted. */
    public volatile boolean isPluggedIn;

    /** True if the drive is mounted. */
    public volatile boolean isMounted;

    /** The mount point, e.g. "/media/pi/USB_16GB". */
    public volatile String mountPoint = "";

    /** The drive label, e.g. "USB_16GB". */
    public volatile String label = "";

    /** The drive size in bytes. */
    public volatile long diskSize = -1L;

    /** The disk space used in bytes. */
    public volatile long diskSpaceUsed = -1L;

    /** The USB port the drive is plugged into (1-4). */
    public volatile int port;

    private final TaskExecutor recursiveListExecutor = new TaskExecutor();
    private final Object fileListingLock = new Object();

    private volatile TaskResult<List<FileInfo>> fileListingTaskResult;

    public DriveInfo(String partitionDevice) {
        this.partitionDevice = partitionDevice;

        rawDriveDevice = partitionDevice;
        while (rawDriveDevice.length() > 1
                && Character.isDigit(rawDriveDevice.charAt(rawDriveDevice.length() - 1))) {
            rawDriveDevice = rawDriveDevice.substring(0, rawDriveDevice.length() - 1);
        }
    }

    /**
     * Recursively list files for drive. Result is cached. {@link TaskResult#get()} will throw an
     * {@link ExecutionException} if something went wrong.
     */
    public TaskResult<List<FileInfo>> getFileListTask() {
        if (fileListingTaskResult == null) {
            synchronized (fileListingLock) {
                // Prevent race condition -- check fileListingFuture again inside synchronized block 
                if (fileListingTaskResult == null) {
                    if (mountPoint.isEmpty()) {
                        // Drive is not mounted
                        fileListingTaskResult = recursiveListExecutor.completed(Collections.emptyList());
                    } else {
                        // Recursively read files from drive
                        AtomicReference<TaskResult<Integer>> taskResult = new AtomicReference<>();
                        fileListingTaskResult = recursiveListExecutor.submit(new Callable<List<FileInfo>>() {
                            @Override
                            public List<FileInfo> call() throws Exception {
                                // Walk directory tree, listing files and getting filesizes
                                Path dir = Paths.get(mountPoint);
                                System.out.println("Scanning files for mount point " + dir);
                                boolean canRead = dir.toFile().canRead();
                                if (!canRead) {
                                    throw new IOException("Cannot read dir " + dir);
                                }

                                List<FileInfo> fileListing = new ArrayList<>();
                                try {
                                    taskResult.set(Command.commandWithConsumer(
                                            new String[] { "sudo", "find", mountPoint, "-type", "f", "-printf",
                                                    "%s\\t%P\\n" },
                                            /* consumeStderr = */ false, /* cmdConsumer = */ line -> {
                                                int tabIdx = line.indexOf("\t");
                                                if (tabIdx > 0) {
                                                    fileListing
                                                            .add(new FileInfo(Paths.get(line.substring(tabIdx + 1)),
                                                                    Long.parseLong(line.substring(0, tabIdx))));
                                                }
                                            }));
                                    // Await result
                                    if (taskResult.get().get() != 0) {
                                        throw new CommandException(
                                                "Got non-zero return code from " + new String[] { "sudo", "find",
                                                        mountPoint, "-type", "f", "-printf", "%s\\t%p\\n" });
                                    }
                                    Collections.sort(fileListing);
                                } catch (InterruptedException | CancellationException e) {
                                    fileListingTaskResult = null;
                                } catch (CommandException e) {
                                    e.printStackTrace();
                                    fileListingTaskResult = null;
                                }
                                return fileListing;
                            }
                        }).onCancel(() -> {
                            // If file listing task is canceled, cancel the find task it depends upon
                            if (taskResult.get() != null) {
                                taskResult.get().cancel();
                            }
                        });
                    }
                }
            }
        }
        return fileListingTaskResult;
    }

    public long getUsed() {
        return diskSpaceUsed;
    }

    public long getFree() {
        return Math.max(0, diskSize - diskSpaceUsed);
    }

    public boolean isPluggedIn() {
        return isPluggedIn;
    }

    public boolean isMounted() {
        return isMounted;
    }

    public void unmount() throws InterruptedException, ExecutionException {
        DiskMonitor.taskExecutor.submitCommand("sudo", "devmon", "--unmount", partitionDevice).get();
    }

    public void mount() throws InterruptedException, ExecutionException {
        DiskMonitor.taskExecutor.submitCommand("sudo", "devmon", "--mount", partitionDevice).get();
        updateDriveSizes();
    }

    public void remount() throws InterruptedException, ExecutionException {
        unmount();
        mount();
    }

    public void updateDriveSizes() {
        // Schedule a df job to get size and free space for drive
        DiskMonitor.taskExecutor.submit(() -> {
            try {
                // Get the number of used kB on the partition using df
                boolean ok = false;
                List<String> lines = Command.command(new String[] { "df", "-B", "1", partitionDevice });
                if (lines.size() == 2) {
                    String line = lines.get(1);
                    System.out.println(line);
                    if (line.startsWith(partitionDevice + " ") || line.startsWith(partitionDevice + "\t")) {
                        try {
                            StringTokenizer tok = new StringTokenizer(line);
                            tok.nextToken();
                            diskSize = Long.parseLong(tok.nextToken());
                            diskSpaceUsed = Long.parseLong(tok.nextToken());
                            System.out.println(
                                    "Disk size for " + partitionDevice + " : " + diskSpaceUsed + " / " + diskSize);
                            ok = true;
                        } catch (NumberFormatException | NoSuchElementException e) {
                            e.printStackTrace();
                        }
                    }
                }
                if (ok) {
                    // Mark drives as changed if df succeeds
                    DiskMonitor.drivesChanged();
                } else {
                    System.out.println("Got bad output from df:\n" + String.join("\n", lines));
                }
            } catch (CommandException e) {
                System.out.println("Could not get size of " + partitionDevice + " : " + e);
            } catch (InterruptedException | CancellationException e) {
            }
        });
    }

    public void clearListing() {
        // Clear previous file listing
        if (fileListingTaskResult != null) {
            synchronized (fileListingLock) {
                if (fileListingTaskResult != null) {
                    fileListingTaskResult = null;
                }
            }
        }
        updateDriveSizes();
    }

    protected void transferCacheFrom(DriveInfo oldDriveInfo) {
        if (this.fileListingTaskResult == null) {
            this.fileListingTaskResult = oldDriveInfo.fileListingTaskResult;
        }
    }

    private static long divRoundUp(long val, long denom) {
        return (val + denom - 1L) / denom;
    }

    private static String decFrac(long numer, long denom) {
        if (numer == 0) {
            return "0";
        } else {
            // Round up to next decimal
            long decFrac = divRoundUp(10 * numer, denom);
            if (decFrac >= 1 && decFrac <= 9) {
                // Use 1dp if value is in [0.1, 1.0) 
                return "0." + decFrac;
            } else {
                return Long.toString(divRoundUp(numer, denom));
            }
        }
    }

    private static final long _1k = 1024L;
    private static final long _1M = 1024L * _1k;
    private static final long _1G = 1024L * _1M;
    private static final String UNK = "??";

    public static String getInHumanUnits(long size) {
        if (size == 0) {
            return "0B";
        } else if (size < _1k) {
            return size + "B";
        } else if (size < _1M) {
            return decFrac(size, _1k) + "kB";
        } else if (size < _1G) {
            return decFrac(size, _1M) + "MB";
        } else {
            return decFrac(size, _1G) + "GB";
        }
    }

    private static String getInHumanUnits(long numer, long denom, boolean showDenom) {
        if (denom == 0) {
            return "0B";
        } else if (denom < _1k) {
            return (numer >= 0 ? numer : UNK) + (showDenom ? "/" + denom : "") + "B";
        } else if (denom < _1M) {
            return (numer >= 0 ? decFrac(numer, _1k) : UNK) + (showDenom ? "/" + decFrac(denom, _1k) : "") + "kB";
        } else if (denom < _1G) {
            return (numer >= 0 ? decFrac(numer, _1M) : UNK) + (showDenom ? "/" + decFrac(denom, _1M) : "") + "MB";
        } else {
            return (numer >= 0 ? decFrac(numer, _1G) : UNK) + (showDenom ? "/" + decFrac(denom, _1G) : "") + "GB";
        }
    }

    public static void main(String[] args) {
        System.out.println(getInHumanUnits(4294967295L, 32 * 1024 * 1024 * 1024L, false));
    }

    public String getUsedInHumanUnits(boolean showTotSize) {
        return getInHumanUnits(Math.min(diskSpaceUsed, diskSize), diskSize, showTotSize);
    }

    public String getFreeInHumanUnits(boolean showTotSize) {
        return getInHumanUnits(diskSpaceUsed < 0 ? -1L : Math.max(0L, diskSize - diskSpaceUsed), diskSize,
                showTotSize);
    }

    public String toStringShort() {
        return "#" + port + " : " + getUsedInHumanUnits(true);
    }

    @Override
    public String toString() {
        return partitionDevice + " -> " + mountPoint + " : " + getUsedInHumanUnits(true);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (!(obj instanceof DriveInfo)) {
            return false;
        }
        DriveInfo other = (DriveInfo) obj;
        return partitionDevice.equals(other.partitionDevice) //
                && mountPoint.equals(other.mountPoint) //
                && port == other.port;
    }

    /**
     * Sort into increasing order of raw device, then decreasing order of partition size.
     */
    @Override
    public int compareTo(DriveInfo other) {
        int portDiff = rawDriveDevice.compareTo(other.rawDriveDevice);
        if (portDiff != 0) {
            return portDiff;
        }
        // Tiebreaker (should not be needed)
        long sizeDiff = other.diskSize - diskSize;
        return sizeDiff < 0L ? -1 : sizeDiff > 0L ? 1 : 0;
    }

    @Override
    public int hashCode() {
        return partitionDevice.hashCode() ^ mountPoint.hashCode() ^ label.hashCode() ^ port;
    }
}