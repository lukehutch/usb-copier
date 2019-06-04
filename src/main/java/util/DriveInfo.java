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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import aobtk.util.Command;
import aobtk.util.Command.CommandException;
import aobtk.util.TaskExecutor;
import aobtk.util.TaskExecutor.TaskResult;
import main.Main;

public class DriveInfo implements Comparable<DriveInfo> {
    /** The partition device, e.g. "/dev/sda1". */
    public final String partitionDevice;
    /** The drive device, e.g. "/dev/sda". */
    public volatile String driveDevice = "";
    /** The drive label, e.g. "USB_16GB". */
    public volatile String label = "";
    /** The mount point, e.g. "/media/pi/USB_16GB". */
    public volatile String mountPoint = "";
    /** The drive size in bytes. */
    public volatile long size;
    /** The USB port the drive is plugged into (1-4). */
    public volatile int port;

    private final TaskExecutor dfExecutor = new TaskExecutor();
    private final TaskExecutor recursiveListExecutor = new TaskExecutor();
    private final Object fileListingLock = new Object();

    private volatile TaskResult<List<FileInfo>> fileListingTaskResult;

    public DriveInfo(String partitionDevice) {
        this.partitionDevice = partitionDevice;
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
                                AtomicBoolean canceled = new AtomicBoolean();
                                List<FileInfo> fileListing = new ArrayList<>();
                                Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                                    @Override
                                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                            throws IOException {
                                        if (Thread.currentThread().isInterrupted()) {
                                            // Check for thread interruption at each dir
                                            canceled.set(true);
                                            return FileVisitResult.TERMINATE;
                                        }
                                        return FileVisitResult.CONTINUE;
                                    }

                                    @Override
                                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                            throws IOException {
                                        long size = attrs.size();
                                        fileListing.add(new FileInfo(dir.relativize(file), size));
                                        return FileVisitResult.CONTINUE;
                                    }
                                });

                                // If file listing was canceled, the returned list will be truncated,
                                // so set fileListingTaskResult to null so that the result is not cached,
                                // and listing will be restarted on the next call
                                if (canceled.get()) {
                                    fileListingTaskResult = null;
                                    throw new InterruptedException();
                                }

                                // Sort files in lexicographic order, and return the list
                                Collections.sort(fileListing);
                                return fileListing;
                            }
                        });
                    }
                }
            }
        }
        return fileListingTaskResult;
    }

    public long getUsed() {
        if (Main.diskMonitor == null) {
            System.out.println("Called getUsed() before initialization");
            // Not yet initialized
            return -1L;
        }
        long used = Main.diskMonitor.getUsed(partitionDevice);
        if (used != -1L) {
            // Size is already known from a previous call to df -- return the size
            return used;
        }

        // Schedule a df job, then return -1 as the size for now.
        // Once the size is known and cached, a drive change event is generated.
        // If there's not a df job already scheduled for this drive
        dfExecutor.submit(() -> {
            try {
                // Get the number of used kB on the partition using df
                List<String> lines = Command.command("sudo df " + partitionDevice);
                if (lines.size() == 2) {
                    String line = lines.get(1);
                    if (line.startsWith(partitionDevice + " ") || line.startsWith(partitionDevice + "\t")) {
                        StringTokenizer tok = new StringTokenizer(line);
                        tok.nextToken();
                        tok.nextToken();
                        String usedTok = tok.nextToken();

                        // Cache value to avoid running df again
                        long usedVal = Long.parseLong(usedTok) * 1024L;
                        Main.diskMonitor.setUsed(partitionDevice, usedVal);

                        // Generate drives changed event
                        Main.diskMonitor.drivesChanged();
                    } else {
                        // For some reason "sudo df /dev/sda1" returns a line for devtmpfs, if the drive
                        // is still being mounted
                        System.out.println("Got wrong drive info back from df: expected " + partitionDevice
                                + ", got: " + line);
                        // Try again after a couple of seconds
                        Thread.sleep(2000);
                        dfExecutor.submit(() -> getUsed());
                    }
                }
            } catch (CommandException e) {
                System.out.println("Could not get size of " + partitionDevice + " : " + e);
            } catch (InterruptedException | CancellationException e) {
                // Interrupted -- leave as -1
            }
        });
        return -1L;
    }

    public long getFree() {
        return Math.max(0, size - getUsed());
    }

    public boolean isMounted() {
        return !mountPoint.isEmpty() && getUsed() >= 0L;
    }

    public void contentsChanged() {
        // Clear previous file listing
        if (fileListingTaskResult != null) {
            synchronized (fileListingLock) {
                if (fileListingTaskResult != null) {
                    fileListingTaskResult = null;
                }
            }
        }
        // Mark used as unknown
        Main.diskMonitor.setUsed(partitionDevice, -1L);
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
        System.out.println(getInHumanUnits(4294967295L, 32*1024*1024*1024L, false));
    }

    public String getUsedInHumanUnits(boolean showTotSize) {
        return getInHumanUnits(Math.min(getUsed(), size), size, showTotSize);
    }

    public String getFreeInHumanUnits(boolean showTotSize) {
        long used = getUsed();
        return getInHumanUnits(used < 0 ? -1L : Math.max(0L, size - used), size, showTotSize);
    }

    public String toStringShort() {
        System.out.println("#" + port + " -> " + getUsed()); //@@
        
        return "#" + port + " : " + getUsedInHumanUnits(true);
    }

    @Override
    public String toString() {
        return "Port " + port + " : " + partitionDevice + " -> " + mountPoint + " : " + getUsedInHumanUnits(true);
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
     * Sort into increasing order of port, then decreasing order of partition size.
     */
    @Override
    public int compareTo(DriveInfo other) {
        int portDiff = port - other.port;
        if (portDiff != 0) {
            return portDiff;
        }
        // Tiebreaker (should not be needed)
        long sizeDiff = other.size - size;
        return sizeDiff < 0L ? -1 : sizeDiff > 0L ? 1 : 0;
    }

    @Override
    public int hashCode() {
        return partitionDevice.hashCode() ^ mountPoint.hashCode() ^ label.hashCode() ^ port;
    }
}