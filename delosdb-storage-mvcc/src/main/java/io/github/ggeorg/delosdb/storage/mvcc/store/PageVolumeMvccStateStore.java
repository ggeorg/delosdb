/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore

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

package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVacuumPlan;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVacuumResult;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;


/**
 * Page-volume backed committed-state store for the inherited MVCC conglomerate.
 *
 * <p>MODULE11A keeps Derby integration as the gate: callers still enter through
 * the caller's adapter/provider boundary. This class only replaces the MODULE9A ad-hoc
 * snapshot file as the committed-row reload authority with the existing Delos
 * page-volume backed MVCC table.</p>
 */
public final class PageVolumeMvccStateStore<T> {
    private static final String ROW_KEY_PREFIX = "row:";
    private static final MvccCommitSequence LATEST_COMMITTED = new MvccCommitSequence(Long.MAX_VALUE);

    private final String storageId;
    private final RowCodec<T> rowCodec;
    private final Path pageFile;
    private final Path pageMutationLogFile;
    private final PageVolumeMvccWriteAheadLog writeAheadLog;
    private final PageVolumeMvccCheckpointStore checkpointStore;
    private final PageBackedMvccTable table;
    private PageVolumeMvccCheckpointStore.Status checkpointStatus;
    private long nextTransactionId;
    private long nextCommitSequence;

    private PageVolumeMvccStateStore(
            String storageId,
            RowCodec<T> rowCodec,
            Path pageFile,
            Path pageMutationLogFile,
            PageVolumeMvccWriteAheadLog writeAheadLog,
            PageVolumeMvccCheckpointStore checkpointStore,
            PageBackedMvccTable table,
            PageVolumeMvccCheckpointStore.Status checkpointStatus) {
        this.storageId = storageId;
        this.rowCodec = Objects.requireNonNull(rowCodec, "rowCodec");
        this.pageFile = pageFile;
        this.pageMutationLogFile = pageMutationLogFile;
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.table = table;
        this.checkpointStatus = Objects.requireNonNull(checkpointStatus, "checkpointStatus");
        long nextSequence = 1L;
        if (table != null) {
            nextSequence = Math.max(nextSequence, table.physicalVersionCount() + 1L);
        }
        this.nextTransactionId = nextSequence;
        this.nextCommitSequence = nextSequence;
    }

    public static <T> PageVolumeMvccStateStore<T> open(
            Path databaseDirectory,
            String storageId,
            RowCodec<T> rowCodec) {
        Path pageFile = PageVolumeMvccPaths.pageFile(databaseDirectory, storageId);
        if (pageFile == null || storageId == null || storageId.isBlank()) {
            return disabled(rowCodec);
        }
        try {
            Path pageMutationLog = PageVolumeMvccPaths.pageMutationLogFileFor(pageFile);
            PageVolumeMvccWriteAheadLog writeAheadLog = PageVolumeMvccWriteAheadLog.open(databaseDirectory, storageId);
            PageVolumeMvccCheckpointStore checkpointStore = PageVolumeMvccCheckpointStore.open(databaseDirectory, storageId);
            PageBackedMvccTable table = PageBackedMvccTable.open(pageFile, pageMutationLog);
            PageVolumeMvccCheckpointStore.Status checkpointStatus = checkpointStore.validate(
                    pageFile,
                    PageBackedMvccTable.rowDirectoryPath(pageFile),
                    pageMutationLog,
                    writeAheadLog.path(),
                    table.durableRowDirectoryHeads(),
                    table.physicalVersionCount(),
                    table.logicalRowCount(),
                    table.durableRowDirectoryHeads().keySet().stream()
                            .mapToLong(io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId::value)
                            .max()
                            .orElse(0L) + 1L);
            return new PageVolumeMvccStateStore<>(
                    storageId,
                    rowCodec,
                    pageFile,
                    pageMutationLog,
                    writeAheadLog,
                    checkpointStore,
                    table,
                    checkpointStatus);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open MVCC page-volume state for " + storageId, e);
        }
    }

    public static <T> PageVolumeMvccStateStore<T> disabled(RowCodec<T> rowCodec) {
        return new PageVolumeMvccStateStore<>(
                "disabled",
                rowCodec,
                null,
                null,
                PageVolumeMvccWriteAheadLog.disabled(),
                PageVolumeMvccCheckpointStore.disabled("disabled"),
                null,
                PageVolumeMvccCheckpointStore.Status.DISABLED);
    }

    public boolean enabled() {
        return table != null;
    }

    public Path pageFile() {
        return pageFile;
    }

    public Path rowDirectoryFile() {
        return pageFile == null ? null : PageBackedMvccTable.rowDirectoryPath(pageFile);
    }

    public Path pageMutationLogFile() {
        return pageMutationLogFile;
    }

    public Path writeAheadLogFile() {
        return writeAheadLog.path();
    }

    public Path checkpointFile() {
        return checkpointStore.path();
    }

    public String checkpointStatus() {
        return checkpointStatus.name();
    }

    public boolean hasDurableState() {
        return enabled() && table.physicalVersionCount() > 0;
    }

    public int physicalVersionCount() {
        return enabled() ? table.physicalVersionCount() : 0;
    }

    public int logicalRowCount() {
        return enabled() ? table.logicalRowCount() : 0;
    }

    public VacuumOutcome vacuumSafely(boolean hasRetainedInheritedSnapshot) {
        if (!enabled()) {
            return VacuumOutcome.disabled();
        }
        if (hasRetainedInheritedSnapshot) {
            return VacuumOutcome.skipped(
                    "retained inherited MVCC transaction or scan",
                    table.physicalVersionCount(),
                    table.logicalRowCount());
        }
        try {
            MvccVacuumResult result = table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            rewriteCheckpoint();
            return VacuumOutcome.completed(result);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not vacuum inherited MVCC page-volume state " + pageFile, e);
        }
    }

    public List<PersistedRow<T>> loadVisibleRows() {
        if (!enabled()) {
            return List.of();
        }
        try {
            List<PersistedRow<T>> rows = new ArrayList<>();
            for (MvccRowPayload payload : table.visibleRows(LATEST_COMMITTED)) {
                rows.add(new PersistedRow<>(rowIdFromKey(payload.key()), rowCodec.decode(payload.value())));
            }
            rows.sort(java.util.Comparator.comparingLong(PersistedRow::rowId));
            return List.copyOf(rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode MVCC page-volume state " + pageFile, e);
        }
    }

    public long nextInheritedRowId() {
        if (!enabled()) {
            return 1L;
        }
        long maxRowId = 0L;
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            maxRowId = Math.max(maxRowId, rowIdFromKey(head.key()));
        }
        return maxRowId + 1L;
    }

    public Optional<MvccRowDirectoryStore.RowHeadRecord> rowHeadForInheritedRowId(long rowId) {
        if (!enabled() || rowId <= 0L) {
            return Optional.empty();
        }
        return table.rowDirectoryHeadForRowId(new MvccRowId(rowId));
    }

    public void persistVisibleRows(List<PersistedRow<T>> visibleRows) {
        Objects.requireNonNull(visibleRows, "visibleRows");
        if (!enabled()) {
            return;
        }
        Map<String, MvccRowDirectoryStore.RowHeadRecord> existingHeads = new LinkedHashMap<>();
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            existingHeads.put(head.key(), head);
        }
        Set<String> liveKeys = visibleRows.stream()
                .map(row -> keyFor(row.rowId()))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        long transactionId = nextTransactionId();
        long commitSequence = nextCommitSequence();
        boolean beganWalTransaction = false;
        try {
            for (PersistedRow<T> row : visibleRows) {
                String key = keyFor(row.rowId());
                byte[] encoded = rowCodec.encode(row.values());
                if (existingHeads.containsKey(key)) {
                    if (table.readPayload(key, LATEST_COMMITTED)
                            .map(payload -> !java.util.Arrays.equals(payload.value(), encoded))
                            .orElse(true)) {
                        if (!beganWalTransaction) {
                            writeAheadLog.appendBegin(transactionId);
                            beganWalTransaction = true;
                        }
                        DelosLogSequenceNumber pageLsn = writeAheadLog.appendUpdateVersion(transactionId, row.rowId());
                        table.updateCommitted(key, encoded, transactionId, commitSequence, pageLsn);
                    }
                } else {
                    if (!beganWalTransaction) {
                        writeAheadLog.appendBegin(transactionId);
                        beganWalTransaction = true;
                    }
                    DelosLogSequenceNumber pageLsn = writeAheadLog.appendInsertVersion(transactionId, row.rowId());
                    table.insertCommitted(key, encoded, transactionId, commitSequence, pageLsn);
                }
            }
            for (MvccRowDirectoryStore.RowHeadRecord head : existingHeads.values()) {
                if (!liveKeys.contains(head.key()) && !head.tombstone()) {
                    if (!beganWalTransaction) {
                        writeAheadLog.appendBegin(transactionId);
                        beganWalTransaction = true;
                    }
                    long rowId = rowIdFromKey(head.key());
                    DelosLogSequenceNumber pageLsn = writeAheadLog.appendDeleteVersion(transactionId, rowId);
                    table.deleteCommitted(head.key(), transactionId, commitSequence, pageLsn);
                }
            }
            if (beganWalTransaction) {
                writeAheadLog.appendCommit(transactionId, commitSequence);
            }
            rewriteCheckpoint();
        } catch (IOException e) {
            if (beganWalTransaction) {
                writeAheadLog.appendAbort(transactionId);
            }
            throw new UncheckedIOException("Could not persist inherited MVCC state to page volume " + pageFile, e);
        } catch (RuntimeException e) {
            if (beganWalTransaction) {
                writeAheadLog.appendAbort(transactionId);
            }
            throw e;
        }
    }

    public void drop() {
        if (!enabled()) {
            return;
        }
        try {
            table.close();
            Files.deleteIfExists(pageFile);
            Path rowDirectory = rowDirectoryFile();
            if (rowDirectory != null) {
                Files.deleteIfExists(rowDirectory);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete inherited MVCC page-volume state " + pageFile, e);
        }
    }

    public void close() {
        if (!enabled()) {
            return;
        }
        try {
            table.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not close inherited MVCC page-volume state " + pageFile, e);
        }
    }


    private void rewriteCheckpoint() {
        if (!enabled()) {
            return;
        }
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> heads = table.durableRowDirectoryHeads();
        checkpointStore.rewrite(
                pageFile,
                rowDirectoryFile(),
                pageMutationLogFile,
                writeAheadLog.path(),
                heads.values(),
                table.physicalVersionCount(),
                table.logicalRowCount(),
                heads.keySet().stream().mapToLong(MvccRowId::value).max().orElse(0L) + 1L);
        checkpointStatus = PageVolumeMvccCheckpointStore.Status.WRITTEN;
    }

    private long nextTransactionId() {
        return nextTransactionId++;
    }

    private long nextCommitSequence() {
        return nextCommitSequence++;
    }


    private static String keyFor(long rowId) {
        if (rowId <= 0L) {
            throw new IllegalArgumentException("inherited MVCC row id must be positive: " + rowId);
        }
        return ROW_KEY_PREFIX + rowId;
    }

    private static long rowIdFromKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith(ROW_KEY_PREFIX)) {
            throw new IllegalStateException("Unsupported inherited MVCC page-volume row key: " + key);
        }
        try {
            return Long.parseLong(key.substring(ROW_KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid inherited MVCC page-volume row key: " + key, e);
        }
    }

    public interface RowCodec<T> {
        byte[] encode(T values) throws IOException;

        T decode(byte[] encoded) throws IOException;
    }

    public record VacuumOutcome(
            boolean enabled,
            boolean skipped,
            String reason,
            int removedVersions,
            int removedLogicalRows,
            int remainingVersions,
            int remainingLogicalRows) {
        public static VacuumOutcome disabled() {
            return new VacuumOutcome(false, true, "disabled", 0, 0, 0, 0);
        }

        public static VacuumOutcome skipped(String reason, int remainingVersions, int remainingLogicalRows) {
            return new VacuumOutcome(true, true, reason, 0, 0, remainingVersions, remainingLogicalRows);
        }

        public static VacuumOutcome completed(MvccVacuumResult result) {
            return new VacuumOutcome(
                    true,
                    false,
                    "completed",
                    result.removedVersions(),
                    result.removedLogicalRows(),
                    result.remainingVersions(),
                    result.remainingLogicalRows());
        }
    }

    public record PersistedRow<T>(long rowId, T values) {
        public PersistedRow {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("MVCC row id must be positive: " + rowId);
            }
            values = Objects.requireNonNull(values, "values");
        }
    }
}
