/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.file.Path;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;

/**
 * Thread-local measurement scope for one MVCC transaction durability path.
 *
 * <p>The scope is observability-only. It counts successful force calls and the
 * logical bytes covered by those calls; it does not add, remove, defer, or
 * reorder any durability operation. A writable transaction combines the
 * ACTIVE-status force captured at begin with the forces captured during
 * commit.</p>
 */
public final class MvccCommitDurabilityMetrics {
    private static final ThreadLocal<Accumulator> ACTIVE = new ThreadLocal<>();

    private MvccCommitDurabilityMetrics() {
    }

    public static Scope begin(boolean enabled) {
        if (!enabled) {
            return Scope.disabled();
        }
        Accumulator previous = ACTIVE.get();
        Accumulator accumulator = new Accumulator();
        ACTIVE.set(accumulator);
        return new Scope(accumulator, previous);
    }

    static void recordFileForce(Path path, long bytes) {
        Accumulator accumulator = ACTIVE.get();
        if (accumulator != null) {
            accumulator.recordFileForce(path, Math.max(0L, bytes));
        }
    }

    static void recordDirectoryForce() {
        Accumulator accumulator = ACTIVE.get();
        if (accumulator != null) {
            accumulator.directoryForceCount++;
        }
    }

    static void recordPageVolumeForce(long pages) {
        Accumulator accumulator = ACTIVE.get();
        if (accumulator == null) {
            return;
        }
        long coveredPages = Math.max(0L, pages);
        accumulator.pageVolumeForceCount++;
        accumulator.pageVolumePagesCovered += coveredPages;
        accumulator.pageVolumeBytesCovered += coveredPages * (long) DelosPage.PAGE_SIZE;
    }

    public record Snapshot(
            boolean observed,
            long transactionStatusForceCount,
            long transactionOutcomeForceCount,
            long writeAheadLogForceCount,
            long otherSidecarForceCount,
            long directoryForceCount,
            long pageVolumeForceCount,
            long pageVolumePagesCovered,
            long sidecarBytesCovered,
            long pageVolumeBytesCovered) {
        private static final Snapshot EMPTY =
                new Snapshot(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        public static Snapshot empty() {
            return EMPTY;
        }

        public long totalForceCount() {
            return transactionStatusForceCount
                    + transactionOutcomeForceCount
                    + writeAheadLogForceCount
                    + otherSidecarForceCount
                    + directoryForceCount
                    + pageVolumeForceCount;
        }

        public Snapshot plus(Snapshot other) {
            if (other == null) {
                return this;
            }
            return new Snapshot(
                    observed || other.observed,
                    transactionStatusForceCount + other.transactionStatusForceCount,
                    transactionOutcomeForceCount + other.transactionOutcomeForceCount,
                    writeAheadLogForceCount + other.writeAheadLogForceCount,
                    otherSidecarForceCount + other.otherSidecarForceCount,
                    directoryForceCount + other.directoryForceCount,
                    pageVolumeForceCount + other.pageVolumeForceCount,
                    pageVolumePagesCovered + other.pageVolumePagesCovered,
                    sidecarBytesCovered + other.sidecarBytesCovered,
                    pageVolumeBytesCovered + other.pageVolumeBytesCovered);
        }
    }

    public static final class Scope {
        private static final Scope DISABLED = new Scope(null, null, true);

        private Accumulator accumulator;
        private Accumulator previous;
        private Snapshot snapshot;
        private final boolean disabled;

        private Scope(Accumulator accumulator, Accumulator previous) {
            this(accumulator, previous, false);
        }

        private Scope(Accumulator accumulator, Accumulator previous, boolean disabled) {
            this.accumulator = accumulator;
            this.previous = previous;
            this.disabled = disabled;
        }

        private static Scope disabled() {
            return DISABLED;
        }

        public Snapshot finish() {
            if (disabled) {
                return Snapshot.empty();
            }
            if (snapshot != null) {
                return snapshot;
            }
            Accumulator current = accumulator;
            if (current == null) {
                snapshot = Snapshot.empty();
                return snapshot;
            }
            if (ACTIVE.get() == current) {
                if (previous == null) {
                    ACTIVE.remove();
                } else {
                    ACTIVE.set(previous);
                }
            }
            accumulator = null;
            previous = null;
            snapshot = current.snapshot();
            return snapshot;
        }
    }

    private static final class Accumulator {
        private long transactionStatusForceCount;
        private long transactionOutcomeForceCount;
        private long writeAheadLogForceCount;
        private long otherSidecarForceCount;
        private long directoryForceCount;
        private long pageVolumeForceCount;
        private long pageVolumePagesCovered;
        private long sidecarBytesCovered;
        private long pageVolumeBytesCovered;

        private void recordFileForce(Path path, long bytes) {
            String fileName = path == null || path.getFileName() == null
                    ? ""
                    : path.getFileName().toString();
            if (fileName.contains(".txstatus")) {
                transactionStatusForceCount++;
            } else if (fileName.contains(".txoutcome")) {
                transactionOutcomeForceCount++;
            } else if (fileName.contains(".wal")) {
                writeAheadLogForceCount++;
            } else {
                otherSidecarForceCount++;
            }
            sidecarBytesCovered += bytes;
        }

        private Snapshot snapshot() {
            return new Snapshot(
                    true,
                    transactionStatusForceCount,
                    transactionOutcomeForceCount,
                    writeAheadLogForceCount,
                    otherSidecarForceCount,
                    directoryForceCount,
                    pageVolumeForceCount,
                    pageVolumePagesCovered,
                    sidecarBytesCovered,
                    pageVolumeBytesCovered);
        }
    }
}
