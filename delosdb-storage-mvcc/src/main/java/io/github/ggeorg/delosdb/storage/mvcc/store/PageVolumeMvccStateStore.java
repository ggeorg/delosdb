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

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccDurableConsistencyCheck;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccOrderedIndexPageStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowDirectoryStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVacuumPlan;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccVacuumResult;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;
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
    private final Path transactionOutcomeLogFile;
    private final PageVolumeMvccWriteAheadLog writeAheadLog;
    private final PageVolumeMvccCheckpointStore checkpointStore;
    private final PageBackedMvccTable table;
    private final MvccOrderedIndexPageStore orderedIndexPageStore;
    private PageVolumeMvccCheckpointStore.Status checkpointStatus;
    private long nextTransactionId;
    private long nextCommitSequence;

    private PageVolumeMvccStateStore(
            String storageId,
            RowCodec<T> rowCodec,
            Path pageFile,
            Path pageMutationLogFile,
            Path transactionOutcomeLogFile,
            PageVolumeMvccWriteAheadLog writeAheadLog,
            PageVolumeMvccCheckpointStore checkpointStore,
            PageBackedMvccTable table,
            MvccOrderedIndexPageStore orderedIndexPageStore,
            PageVolumeMvccCheckpointStore.Status checkpointStatus) {
        this.storageId = storageId;
        this.rowCodec = Objects.requireNonNull(rowCodec, "rowCodec");
        this.pageFile = pageFile;
        this.pageMutationLogFile = pageMutationLogFile;
        this.transactionOutcomeLogFile = transactionOutcomeLogFile;
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.table = table;
        this.orderedIndexPageStore = orderedIndexPageStore;
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
            Path transactionOutcomeLog = PageVolumeMvccPaths.transactionOutcomeLogFileFor(pageFile);
            PageVolumeMvccWriteAheadLog writeAheadLog = PageVolumeMvccWriteAheadLog.open(databaseDirectory, storageId);
            PageVolumeMvccCheckpointStore checkpointStore = PageVolumeMvccCheckpointStore.open(databaseDirectory, storageId);
            PageBackedMvccTable table = PageBackedMvccTable.open(pageFile, pageMutationLog, transactionOutcomeLog);
            MvccOrderedIndexPageStore orderedIndexPageStore = MvccOrderedIndexPageStore.open(
                    PageBackedMvccTable.orderedIndexPagesPath(pageFile));
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
                    transactionOutcomeLog,
                    writeAheadLog,
                    checkpointStore,
                    table,
                    orderedIndexPageStore,
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
                null,
                PageVolumeMvccWriteAheadLog.disabled(),
                PageVolumeMvccCheckpointStore.disabled("disabled"),
                null,
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

    public Path reusablePageIndexFile() {
        return pageFile == null ? null : PageBackedMvccTable.reusablePageIndexPath(pageFile);
    }

    public Path freeSpaceMapFile() {
        return pageFile == null ? null : PageBackedMvccTable.freeSpaceMapPath(pageFile);
    }

    public Path visibilityMapFile() {
        return pageFile == null ? null : PageBackedMvccTable.visibilityMapPath(pageFile);
    }

    public Path purgeQueueFile() {
        return pageFile == null ? null : PageBackedMvccTable.purgeQueuePath(pageFile);
    }

    public Path orderedIndexPagesFile() {
        return pageFile == null ? null : PageBackedMvccTable.orderedIndexPagesPath(pageFile);
    }

    public Path pageMutationLogFile() {
        return pageMutationLogFile;
    }

    public Path transactionOutcomeLogFile() {
        return transactionOutcomeLogFile;
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

    public long pageCount() {
        if (!enabled()) {
            return 0L;
        }
        try {
            return table.pageCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC page count for " + pageFile, e);
        }
    }

    public long overflowPageCount() {
        if (!enabled()) {
            return 0L;
        }
        try {
            return table.overflowPageCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC overflow page count for " + pageFile, e);
        }
    }

    public long reusablePageCount() {
        return enabled() ? table.reusablePageCount() : 0L;
    }

    public long freeSpaceMapPageCount() {
        return enabled() ? table.freeSpaceMapPageCount() : 0L;
    }

    public int freeSpaceMapMaxFreeBytes() {
        return enabled() ? table.freeSpaceMapMaxFreeBytes() : 0;
    }

    public long freeSpaceMapLookupCount() {
        return enabled() ? table.freeSpaceMapLookupCount() : 0L;
    }

    public long freeSpaceMapHitCount() {
        return enabled() ? table.freeSpaceMapHitCount() : 0L;
    }

    public long freeSpaceMapNonLastHitCount() {
        return enabled() ? table.freeSpaceMapNonLastHitCount() : 0L;
    }

    public long freeSpaceMapMissCount() {
        return enabled() ? table.freeSpaceMapMissCount() : 0L;
    }

    public long freeSpaceMapStaleEntryCount() {
        return enabled() ? table.freeSpaceMapStaleEntryCount() : 0L;
    }

    public long freeSpaceMapUpdateCount() {
        return enabled() ? table.freeSpaceMapUpdateCount() : 0L;
    }

    public long freeSpaceMapRebuildCount() {
        return enabled() ? table.freeSpaceMapRebuildCount() : 0L;
    }

    public List<String> freeSpaceMapPageSummaries() {
        return enabled() ? table.freeSpaceMapPageSummaries() : List.of();
    }

    public long visibilityMapPageCount() {
        return enabled() ? table.visibilityMapPageCount() : 0L;
    }

    public long visibilityMapOldVersionPageCount() {
        return enabled() ? table.visibilityMapOldVersionPageCount() : 0L;
    }

    public long visibilityMapPrunablePageCount() {
        return enabled() ? table.visibilityMapPrunablePageCount() : 0L;
    }

    public long visibilityMapTombstonePageCount() {
        return enabled() ? table.visibilityMapTombstonePageCount() : 0L;
    }

    public long visibilityMapAllVisiblePageCount() {
        return enabled() ? table.visibilityMapAllVisiblePageCount() : 0L;
    }

    public long visibilityMapOverflowPageCount() {
        return enabled() ? table.visibilityMapOverflowPageCount() : 0L;
    }

    public long visibilityMapNeedsCheckerPageCount() {
        return enabled() ? table.visibilityMapNeedsCheckerPageCount() : 0L;
    }

    public long visibilityMapUpdateCount() {
        return enabled() ? table.visibilityMapUpdateCount() : 0L;
    }

    public long visibilityMapRebuildCount() {
        return enabled() ? table.visibilityMapRebuildCount() : 0L;
    }

    public List<String> visibilityMapPageSummaries() {
        return enabled() ? table.visibilityMapPageSummaries() : List.of();
    }

    public long pageLocalPruneAttemptCount() {
        return enabled() ? table.pageLocalPruneAttemptCount() : 0L;
    }

    public long pageLocalPruneSuccessCount() {
        return enabled() ? table.pageLocalPruneSuccessCount() : 0L;
    }

    public long pageLocalPruneFallbackCount() {
        return enabled() ? table.pageLocalPruneFallbackCount() : 0L;
    }

    public long pageLocalPruneRemovedVersionCount() {
        return enabled() ? table.pageLocalPruneRemovedVersionCount() : 0L;
    }

    public long pageMutationContextBeginCount() {
        return enabled() ? table.pageMutationContextBeginCount() : 0L;
    }

    public long pageMutationContextCommitCount() {
        return enabled() ? table.pageMutationContextCommitCount() : 0L;
    }

    public long pageMutationContextAbortCount() {
        return enabled() ? table.pageMutationContextAbortCount() : 0L;
    }

    public long pageMutationContextPageReservationCount() {
        return enabled() ? table.pageMutationContextPageReservationCount() : 0L;
    }

    public long pageMutationContextReservedBytes() {
        return enabled() ? table.pageMutationContextReservedBytes() : 0L;
    }

    public long pageMutationContextPageWriteCount() {
        return enabled() ? table.pageMutationContextPageWriteCount() : 0L;
    }

    public long pageMutationContextFreeSpaceMapUpdateCount() {
        return enabled() ? table.pageMutationContextFreeSpaceMapUpdateCount() : 0L;
    }

    public long pageMutationContextReusableIndexUpdateCount() {
        return enabled() ? table.pageMutationContextReusableIndexUpdateCount() : 0L;
    }

    public String lastPageMutationContextOperation() {
        return enabled() ? table.lastPageMutationContextOperation() : "none";
    }

    public long purgeQueuePendingCount() {
        return enabled() ? table.purgeQueuePendingCount() : 0L;
    }

    public long purgeQueueEnqueueCount() {
        return enabled() ? table.purgeQueueEnqueueCount() : 0L;
    }

    public long purgeQueueDrainCount() {
        return enabled() ? table.purgeQueueDrainCount() : 0L;
    }

    public long purgeQueueLastDrainCount() {
        return enabled() ? table.purgeQueueLastDrainCount() : 0L;
    }

    public List<String> purgeQueueEntrySummaries() {
        return enabled() ? table.purgeQueueEntrySummaries() : List.of();
    }

    public void rebuildOrderedIndexPages(List<OrderedIndexEntry> entries) {
        if (!enabled() || orderedIndexPageStore == null) {
            return;
        }
        try {
            List<MvccOrderedIndexPageStore.Entry> durableEntries = new ArrayList<>();
            for (OrderedIndexEntry entry : Objects.requireNonNull(entries, "entries")) {
                durableEntries.add(new MvccOrderedIndexPageStore.Entry(
                        entry.column(), entry.key(), entry.rowId()));
            }
            orderedIndexPageStore.rewrite(durableEntries);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rebuild MVCC ordered index pages "
                    + orderedIndexPagesFile(), e);
        }
    }

    public long orderedIndexPageCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0L;
        }
        try {
            return orderedIndexPageStore.pageCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC ordered index page count for "
                    + orderedIndexPagesFile(), e);
        }
    }

    public long orderedIndexEntryCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0L;
        }
        try {
            return orderedIndexPageStore.entryCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC ordered index entry count for "
                    + orderedIndexPagesFile(), e);
        }
    }

    public int orderedIndexDistinctKeyCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0;
        }
        try {
            return orderedIndexPageStore.distinctKeyCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC ordered index distinct key count for "
                    + orderedIndexPagesFile(), e);
        }
    }

    public long orderedIndexRebuildCount() {
        return enabled() && orderedIndexPageStore != null ? orderedIndexPageStore.rebuildCount() : 0L;
    }

    public List<String> orderedIndexEntrySummaries() {
        if (!enabled() || orderedIndexPageStore == null) {
            return List.of();
        }
        try {
            return orderedIndexPageStore.entrySummaries();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC ordered index entries for "
                    + orderedIndexPagesFile(), e);
        }
    }

    public Optional<List<Long>> orderedIndexRowIdsFor(int column, String key) {
        if (!enabled() || orderedIndexPageStore == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(orderedIndexPageStore.rowIdsFor(column, key));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Optional<List<Long>> orderedIndexRowIdsInRangeFor(
            int column,
            String lowerKey,
            boolean lowerInclusive,
            String upperKey,
            boolean upperInclusive) {
        if (!enabled() || orderedIndexPageStore == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(orderedIndexPageStore.rowIdsInRangeFor(
                    column, lowerKey, lowerInclusive, upperKey, upperInclusive));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public long pageCacheMaxPageCount() {
        return enabled() ? table.pageCacheMaxPageCount() : 0L;
    }

    public long pageCacheSize() {
        return enabled() ? table.pageCacheSize() : 0L;
    }

    public long pageCacheHitCount() {
        return enabled() ? table.pageCacheHitCount() : 0L;
    }

    public long pageCacheMissCount() {
        return enabled() ? table.pageCacheMissCount() : 0L;
    }

    public long pageCacheWriteCount() {
        return enabled() ? table.pageCacheWriteCount() : 0L;
    }

    public long pageCacheEvictionCount() {
        return enabled() ? table.pageCacheEvictionCount() : 0L;
    }

    public long pageCacheInvalidationCount() {
        return enabled() ? table.pageCacheInvalidationCount() : 0L;
    }

    public long pageCachePinCount() {
        return enabled() ? table.pageCachePinCount() : 0L;
    }

    public long pageCacheUnpinCount() {
        return enabled() ? table.pageCacheUnpinCount() : 0L;
    }

    public long pageCachePinnedPageCount() {
        return enabled() ? table.pageCachePinnedPageCount() : 0L;
    }

    public long pageCacheDirtyPageCount() {
        return enabled() ? table.pageCacheDirtyPageCount() : 0L;
    }

    public long pageCacheFlushListPageCount() {
        return enabled() ? table.pageCacheFlushListPageCount() : 0L;
    }

    public long pageCacheFlushCount() {
        return enabled() ? table.pageCacheFlushCount() : 0L;
    }

    public long pageCachePinnedEvictionSkipCount() {
        return enabled() ? table.pageCachePinnedEvictionSkipCount() : 0L;
    }

    public long pageCacheLastPageGeneration() {
        return enabled() ? table.pageCacheLastPageGeneration() : 0L;
    }

    public long attributeOverflowWriteCount() {
        return enabled() ? table.attributeOverflowWriteCount() : 0L;
    }

    public long attributeOverflowReadCount() {
        return enabled() ? table.attributeOverflowReadCount() : 0L;
    }

    public long attributeOverflowInlineRowBytes() {
        return enabled() ? table.attributeOverflowInlineRowBytes() : 0L;
    }

    public long attributeOverflowValueBytes() {
        return enabled() ? table.attributeOverflowValueBytes() : 0L;
    }

    public int consistencyErrorCount() {
        return validateConsistency().errors().size();
    }

    public String consistencySummary() {
        MvccDurableConsistencyCheck.Result result = validateConsistency();
        if (result.valid()) {
            return "valid: physicalVersions=" + result.physicalVersions()
                    + ", logicalRows=" + result.logicalRows()
                    + ", durableHeads=" + result.durableHeads();
        }
        return "invalid: " + String.join("; ", result.errors());
    }

    public void assertConsistent() {
        validateConsistency().assertValid();
    }

    public MvccDurableConsistencyCheck.Result validateConsistency() {
        if (!enabled()) {
            return new MvccDurableConsistencyCheck.Result(0, 0, 0, List.of());
        }
        return table.validateConsistency();
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
        return loadVisibleRows(LATEST_COMMITTED);
    }

    public List<PersistedRow<T>> loadVisibleRows(MvccCommitSequence visibleThrough) {
        Objects.requireNonNull(visibleThrough, "visibleThrough");
        if (!enabled()) {
            return List.of();
        }
        try {
            List<PersistedRow<T>> rows = new ArrayList<>();
            for (MvccRowPayload payload : table.visibleRows(visibleThrough)) {
                rows.add(new PersistedRow<>(rowIdFromKey(payload.key()), rowCodec.decode(payload.value())));
            }
            rows.sort(java.util.Comparator.comparingLong(PersistedRow::rowId));
            return List.copyOf(rows);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode MVCC page-volume state " + pageFile, e);
        }
    }

    public Optional<PersistedRow<T>> loadVisibleRow(long rowId) {
        return loadVisibleRow(rowId, LATEST_COMMITTED);
    }

    public Optional<PersistedRow<T>> loadVisibleRow(long rowId, MvccCommitSequence visibleThrough) {
        Objects.requireNonNull(visibleThrough, "visibleThrough");
        if (!enabled() || rowId <= 0L) {
            return Optional.empty();
        }
        try {
            Optional<MvccRowPayload> payload = table.readPayload(keyFor(rowId), visibleThrough);
            if (payload.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PersistedRow<>(rowId, rowCodec.decode(payload.get().value())));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode MVCC page-volume row "
                    + rowId + " from " + pageFile, e);
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

    public void requireChangedRowsCanBePersisted(List<PersistedChange<T>> changes) {
        Objects.requireNonNull(changes, "changes");
        if (!enabled()) {
            return;
        }
        try {
            for (PersistedChange<T> change : changes) {
                if (!change.delete()) {
                    PageBackedMvccTable.requirePayloadCanBeEncoded(
                            keyFor(change.rowId()),
                            rowCodec.encode(change.values()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode changed MVCC page-volume state " + pageFile, e);
        }
    }

    public void persistChangedRows(List<PersistedChange<T>> changes) {
        persistChangedRows(changes, new MvccCommitSequence(nextCommitSequence()));
    }

    public void persistChangedRows(
            List<PersistedChange<T>> changes,
            MvccCommitSequence commitSequence) {
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(commitSequence, "commitSequence");
        if (!enabled() || changes.isEmpty()) {
            return;
        }
        long durableCommitSequence = commitSequence.value();
        nextCommitSequence = Math.max(nextCommitSequence, durableCommitSequence + 1L);
        Map<String, MvccRowDirectoryStore.RowHeadRecord> existingHeads = new LinkedHashMap<>();
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            existingHeads.put(head.key(), head);
        }
        long transactionId = nextTransactionId();
        boolean beganWalTransaction = false;
        try {
            for (PersistedChange<T> change : changes) {
                String key = keyFor(change.rowId());
                MvccRowDirectoryStore.RowHeadRecord existingHead = existingHeads.get(key);
                if (change.delete()) {
                    if (existingHead != null && !existingHead.tombstone()) {
                        if (!beganWalTransaction) {
                            writeAheadLog.appendBegin(transactionId);
                            beganWalTransaction = true;
                        }
                        DelosLogSequenceNumber pageLsn = writeAheadLog.appendDeleteVersion(transactionId, change.rowId());
                        table.deleteCommitted(key, transactionId, durableCommitSequence, pageLsn);
                    }
                    continue;
                }
                byte[] encoded = rowCodec.encode(change.values());
                if (existingHead == null) {
                    if (!beganWalTransaction) {
                        writeAheadLog.appendBegin(transactionId);
                        beganWalTransaction = true;
                    }
                    DelosLogSequenceNumber pageLsn = writeAheadLog.appendInsertVersion(transactionId, change.rowId());
                    table.insertCommitted(key, encoded, transactionId, durableCommitSequence, pageLsn);
                } else if (existingHead.tombstone() || table.readPayload(key, LATEST_COMMITTED)
                        .map(payload -> !java.util.Arrays.equals(payload.value(), encoded))
                        .orElse(true)) {
                    if (!beganWalTransaction) {
                        writeAheadLog.appendBegin(transactionId);
                        beganWalTransaction = true;
                    }
                    DelosLogSequenceNumber pageLsn = writeAheadLog.appendUpdateVersion(transactionId, change.rowId());
                    table.updateCommitted(key, encoded, transactionId, durableCommitSequence, pageLsn);
                }
            }
            if (beganWalTransaction) {
                writeAheadLog.appendCommit(transactionId, durableCommitSequence);
                rewriteCheckpoint();
            }
        } catch (IOException e) {
            if (beganWalTransaction) {
                writeAheadLog.appendAbort(transactionId);
            }
            throw new UncheckedIOException("Could not persist inherited MVCC changed rows to page volume "
                    + pageFile, e);
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
            if (orderedIndexPageStore != null) {
                orderedIndexPageStore.close();
            }
            Files.deleteIfExists(pageFile);
            Path rowDirectory = rowDirectoryFile();
            if (rowDirectory != null) {
                Files.deleteIfExists(rowDirectory);
            }
            Path overflow = PageBackedMvccTable.overflowPath(pageFile);
            Files.deleteIfExists(overflow);
            Path reusablePageIndex = reusablePageIndexFile();
            if (reusablePageIndex != null) {
                Files.deleteIfExists(reusablePageIndex);
                Files.deleteIfExists(reusablePageIndex.resolveSibling(reusablePageIndex.getFileName() + ".rewrite"));
            }
            Path freeSpaceMap = freeSpaceMapFile();
            if (freeSpaceMap != null) {
                Files.deleteIfExists(freeSpaceMap);
                Files.deleteIfExists(freeSpaceMap.resolveSibling(freeSpaceMap.getFileName() + ".rewrite"));
            }
            Path visibilityMap = visibilityMapFile();
            if (visibilityMap != null) {
                Files.deleteIfExists(visibilityMap);
                Files.deleteIfExists(visibilityMap.resolveSibling(visibilityMap.getFileName() + ".rewrite"));
            }
            Path purgeQueue = purgeQueueFile();
            if (purgeQueue != null) {
                Files.deleteIfExists(purgeQueue);
                Files.deleteIfExists(purgeQueue.resolveSibling(purgeQueue.getFileName() + ".rewrite"));
            }
            Path orderedIndexPages = orderedIndexPagesFile();
            if (orderedIndexPages != null) {
                Files.deleteIfExists(orderedIndexPages);
            }
            if (pageMutationLogFile != null) {
                Files.deleteIfExists(pageMutationLogFile);
            }
            if (transactionOutcomeLogFile != null) {
                Files.deleteIfExists(transactionOutcomeLogFile);
            }
            Path wal = writeAheadLog.path();
            if (wal != null) {
                Files.deleteIfExists(wal);
            }
            Path checkpoint = checkpointStore.path();
            if (checkpoint != null) {
                Files.deleteIfExists(checkpoint);
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
            if (orderedIndexPageStore != null) {
                orderedIndexPageStore.close();
            }
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

    public record OrderedIndexEntry(int column, String key, long rowId) {
        public OrderedIndexEntry {
            if (column < 0) {
                throw new IllegalArgumentException("ordered index column must be non-negative: " + column);
            }
            key = Objects.requireNonNull(key, "key");
            if (rowId <= 0L) {
                throw new IllegalArgumentException("ordered index row id must be positive: " + rowId);
            }
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

    public record PersistedChange<T>(long rowId, T values, boolean delete) {
        public PersistedChange {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("MVCC row id must be positive: " + rowId);
            }
            if (!delete) {
                values = Objects.requireNonNull(values, "values");
            }
        }

        public static <T> PersistedChange<T> upsert(long rowId, T values) {
            return new PersistedChange<>(rowId, values, false);
        }

        public static <T> PersistedChange<T> delete(long rowId) {
            return new PersistedChange<>(rowId, null, true);
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
