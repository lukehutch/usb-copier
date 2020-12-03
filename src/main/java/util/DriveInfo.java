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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import exec.Exec;

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

    private final Object fileListingLock = new Object();

    private volatile Future<List<FileInfo>> fileListingFuture;

    public DriveInfo(String partitionDevice) {
        this.partitionDevice = partitionDevice;

        rawDriveDevice = partitionDevice;
        while (rawDriveDevice.length() > 1
                && Character.isDigit(rawDriveDevice.charAt(rawDriveDevice.length() - 1))) {
            rawDriveDevice = rawDriveDevice.substring(0, rawDriveDevice.length() - 1);
        }
    }

    /**
     * Recursively list files for drive. Result is cached. {@link Future#get()} will throw an
     * {@link ExecutionException} if something went wrong.
     */
    public Future<List<FileInfo>> getFileListTask() {
        if (fileListingFuture == null) {
            synchronized (fileListingLock) {
                // Prevent race condition -- check fileListingFuture again inside synchronized block 
                if (fileListingFuture == null) {
                    if (!isMounted) {
                        // If drive is not mounted for some reason, try mounting drive first
                        try {
                            mount();
                        } catch (InterruptedException | ExecutionException e) {
                            // Drive could not be mounted
                            fileListingFuture = CompletableFuture
                                    .failedFuture(new IOException("Not mounted: " + partitionDevice));
                        }
                    }
                    if (fileListingFuture == null) {
                        String mtPt = mountPoint;
                        if (mountPoint.isEmpty()) {
                            System.out.println("Tried listing non-mounted drive");
                            fileListingFuture = CompletableFuture
                                    .failedFuture(new IOException("Mount failed: " + partitionDevice));
                        }
                        fileListingFuture = Exec.thenMap(
                                Exec.execWithTaskOutput("find", mtPt, "-type", "f", "-printf", "%s\\t%P\\n"),
                                taskOutput -> {
                                    if (taskOutput.exitCode != 0) {
                                        throw new IOException("Could not list files: " + taskOutput.stderr);
                                    } else {
                                        Path dir = Paths.get(mtPt);
                                        System.out.println("Listing files in: " + dir);
                                        boolean canRead = dir.toFile().canRead();
                                        if (!canRead) {
                                            throw new IOException("Cannot read dir: " + dir);
                                        }
                                        List<FileInfo> fileListing = new ArrayList<>();
                                        for (String line : taskOutput.stdout.split("\n")) {
                                            int tabIdx = line.indexOf("\t");
                                            if (tabIdx > 0) {
                                                fileListing.add(new FileInfo(Paths.get(line.substring(tabIdx + 1)),
                                                        Long.parseLong(line.substring(0, tabIdx))));
                                            }
                                        }
                                        Collections.sort(fileListing);
                                        return fileListing;
                                    }
                                });
                    }
                }
            }
        }
        return fileListingFuture;
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
        // isMounted will be updated by DiskMonitor on unmount event
        var taskOutput = Exec.execWithTaskOutputSynchronous("sudo", "-u", "pi", "devmon", "--unmount",
                partitionDevice);
        if (taskOutput.exitCode != 0) {
            System.out.println("Unmount failed with exit code " + taskOutput.exitCode + " : " + taskOutput.stderr);
        }
        // Wait for DiskMonitor to detect that disk is unmounted
        for (int i = 0; i < 30; i++) {
            if (!isMounted) {
                return;
            }
            Thread.sleep(100);
        }
        System.out.println("Unmount failed after timeout");
    }

    public void mount() throws InterruptedException, ExecutionException {
        // isMounted will be updated by DiskMonitor on mount event
        var taskOutput = Exec.execWithTaskOutputSynchronous("sudo", "-u", "pi", "devmon", "--mount",
                partitionDevice);
        if (taskOutput.exitCode != 0) {
            System.out.println("Mount failed with exit code " + taskOutput.exitCode + " : " + taskOutput.stderr);
        }
        // Wait for DiskMonitor to detect that disk is mounted
        for (int i = 0; i < 30; i++) {
            if (isMounted) {
                return;
            }
            Thread.sleep(100);
        }
        System.out.println("Mount failed after timeout");
    }

    public void remount() throws InterruptedException, ExecutionException {
        unmount();
        mount();
    }

    public void updateDriveSizeAsync() {
        // Only update drive size if drive is mounted
        if (isMounted) {
            Exec.then(Exec.execWithTaskOutput("df", "-B", "1", partitionDevice), taskOutput -> {
                if (taskOutput.exitCode != 0) {
                    System.out.println("Bad exit code " + taskOutput.exitCode + " from df: " + taskOutput.stderr);
                } else {
                    String stdout = taskOutput.stdout;
                    String[] lines = stdout.split("\n");
                    if (lines.length == 2) {
                        String line = lines[1];
                        // System.out.println(line);
                        if (line.startsWith(partitionDevice + " ") || line.startsWith(partitionDevice + "\t")) {
                            try {
                                StringTokenizer tok = new StringTokenizer(line);
                                tok.nextToken();
                                diskSize = Long.parseLong(tok.nextToken());
                                diskSpaceUsed = Long.parseLong(tok.nextToken());
                                System.out.println("Disk size for " + partitionDevice + " : " + diskSpaceUsed
                                        + " / " + diskSize);
                            } catch (NumberFormatException | NoSuchElementException e) {
                                e.printStackTrace();
                            }
                        }
                        DiskMonitor.drivesChanged();
                    } else {
                        if (stdout.length() > 0 && stdout.charAt(stdout.length() - 1) == '\n') {
                            stdout = stdout.substring(0, stdout.length() - 1);
                        }
                        System.out.println("Got bad output from df:\n" + stdout);
                    }
                }
            });
        }
    }

    public void clearListing() {
        // Clear previous file listing
        if (fileListingFuture != null) {
            synchronized (fileListingLock) {
                if (fileListingFuture != null) {
                    fileListingFuture = null;
                }
            }
        }
    }

    protected void transferCacheFrom(DriveInfo oldDriveInfo) {
        if (this.fileListingFuture == null) {
            this.fileListingFuture = oldDriveInfo.fileListingFuture;
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
        if (numer < 0 || denom < 0) {
            return "??";
        }
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
        return partitionDevice + " -> " + mountPoint + " :" + (isPluggedIn ? " pluggedIn" : " unplugged")
                + (isMounted ? " mounted" : " unmounted") + " used:" + getUsedInHumanUnits(true);
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