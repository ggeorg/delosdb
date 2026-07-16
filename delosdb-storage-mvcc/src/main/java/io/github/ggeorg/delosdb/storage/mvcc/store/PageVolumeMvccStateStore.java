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
 * <p>Callers enter through the Derby adapter/provider boundary. This class owns the
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
    private final MvccSubsystemRecoveryRecordStore recoveryRecordStore;
    private final PageBackedMvccTable table;
    private final MvccOrderedIndexPageStore orderedIndexPageStore;
    private OrderedIndexLookupFallbackReason orderedIndexOpenFallbackReason;
    private OrderedIndexLookupFallbackReason pendingOrderedIndexLookupFallbackReason;
    private volatile PublicationHook publicationHook = PublicationHook.NOOP;
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
            MvccSubsystemRecoveryRecordStore recoveryRecordStore,
            PageBackedMvccTable table,
            MvccOrderedIndexPageStore orderedIndexPageStore,
            OrderedIndexLookupFallbackReason orderedIndexOpenFallbackReason) {
        this.storageId = storageId;
        this.rowCodec = Objects.requireNonNull(rowCodec, "rowCodec");
        this.pageFile = pageFile;
        this.pageMutationLogFile = pageMutationLogFile;
        this.transactionOutcomeLogFile = transactionOutcomeLogFile;
        this.writeAheadLog = Objects.requireNonNull(writeAheadLog, "writeAheadLog");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore");
        this.recoveryRecordStore = Objects.requireNonNull(recoveryRecordStore, "recoveryRecordStore");
        this.table = table;
        this.orderedIndexPageStore = orderedIndexPageStore;
        this.orderedIndexOpenFallbackReason = orderedIndexOpenFallbackReason;
        this.pendingOrderedIndexLookupFallbackReason = orderedIndexOpenFallbackReason;
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
        if (databaseDirectory == null || PageVolumeMvccPaths.isMissingStorageId(storageId)) {
            return disabled(rowCodec);
        }
        try {
            PageVolumeMvccOpenContext openContext = PageVolumeMvccOpenContext.open(databaseDirectory, storageId);
            return new PageVolumeMvccStateStore<>(
                    openContext.storageId,
                    rowCodec,
                    openContext.pageFile,
                    openContext.pageMutationLogFile,
                    openContext.transactionOutcomeLogFile,
                    openContext.writeAheadLog,
                    openContext.checkpointStore,
                    openContext.recoveryRecordStore,
                    openContext.table,
                    openContext.orderedIndexPageStore,
                    openContext.orderedIndexOpenFallbackReason);
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
                MvccSubsystemRecoveryRecordStore.disabled(),
                null,
                null,
                OrderedIndexLookupFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR);
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

    public Path subsystemRecoveryRecordsFile() {
        return recoveryRecordStore.path();
    }

    public String checkpointStatus() {
        return checkpointStore.status().name();
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
            orderedIndexOpenFallbackReason = null;
            recoveryRecordStore.appendIndexPageRedo(
                    orderedIndexPageStore.pageCount(),
                    orderedIndexPageStore.entryCount());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rebuild MVCC ordered index pages "
                    + orderedIndexPagesFile(), e);
        }
    }

    public long orderedIndexPageCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0L;
        }
        return orderedIndexPageStore.pageCount();
    }

    public long orderedIndexEntryCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0L;
        }
        return orderedIndexPageStore.entryCount();
    }

    public int orderedIndexDistinctKeyCount() {
        if (!enabled() || orderedIndexPageStore == null) {
            return 0;
        }
        return orderedIndexPageStore.distinctKeyCount();
    }

    public long orderedIndexRebuildCount() {
        return enabled() && orderedIndexPageStore != null ? orderedIndexPageStore.rebuildCount() : 0L;
    }

    public List<String> orderedIndexEntrySummaries() {
        if (!enabled() || orderedIndexPageStore == null) {
            return List.of();
        }
        return orderedIndexPageStore.entrySummaries();
    }

    public Optional<List<Long>> orderedIndexRowIdsFor(int column, String key) {
        return orderedIndexLookupFor(column, key).rowIds();
    }

    public OrderedIndexLookupResult orderedIndexLookupFor(int column, String key) {
        OrderedIndexLookupFallbackReason unavailableReason = orderedIndexUnavailableForLookup();
        if (unavailableReason != null) {
            return OrderedIndexLookupResult.fallback(unavailableReason);
        }
        try {
            return OrderedIndexLookupResult.answered(orderedIndexPageStore.rowIdsFor(column, key));
        } catch (MvccOrderedIndexPageStore.UnsupportedLookupException | IllegalArgumentException e) {
            return OrderedIndexLookupResult.fallback(OrderedIndexLookupFallbackReason.UNSUPPORTED_KEY_OR_TYPE);
        }
    }

    public Optional<List<Long>> orderedIndexRowIdsInRangeFor(
            int column,
            String lowerKey,
            boolean lowerInclusive,
            String upperKey,
            boolean upperInclusive) {
        return orderedIndexRangeLookupFor(column, lowerKey, lowerInclusive, upperKey, upperInclusive).rowIds();
    }

    public OrderedIndexLookupResult orderedIndexRangeLookupFor(
            int column,
            String lowerKey,
            boolean lowerInclusive,
            String upperKey,
            boolean upperInclusive) {
        OrderedIndexLookupFallbackReason unavailableReason = orderedIndexUnavailableForLookup();
        if (unavailableReason != null) {
            return OrderedIndexLookupResult.fallback(unavailableReason);
        }
        try {
            return OrderedIndexLookupResult.answered(orderedIndexPageStore.rowIdsInRangeFor(
                    column, lowerKey, lowerInclusive, upperKey, upperInclusive));
        } catch (MvccOrderedIndexPageStore.UnsupportedLookupException | IllegalArgumentException e) {
            return OrderedIndexLookupResult.fallback(OrderedIndexLookupFallbackReason.UNSUPPORTED_KEY_OR_TYPE);
        }
    }

    private OrderedIndexLookupFallbackReason orderedIndexUnavailableForLookup() {
        if (!enabled() || orderedIndexPageStore == null) {
            return orderedIndexOpenFallbackReason == null
                    ? OrderedIndexLookupFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR
                    : orderedIndexOpenFallbackReason;
        }
        if (orderedIndexOpenFallbackReason != null) {
            return orderedIndexOpenFallbackReason;
        }
        if (pendingOrderedIndexLookupFallbackReason != null) {
            OrderedIndexLookupFallbackReason reason = pendingOrderedIndexLookupFallbackReason;
            pendingOrderedIndexLookupFallbackReason = null;
            return reason;
        }
        return null;
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

    public long subsystemRecoveryRecordCount() {
        return recoveryDiagnostics().recordCount();
    }

    public long subsystemRecoveryLastSequence() {
        return recoveryDiagnostics().lastSequence();
    }

    public long rowPageRedoRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.ROW_PAGE);
    }

    public long indexPageRedoRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.INDEX_PAGE);
    }

    public long overflowPageRedoRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.OVERFLOW_PAGE);
    }

    public long freeSpaceMapRedoRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.FREE_SPACE_MAP);
    }

    public long transactionOutcomeRedoRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.TRANSACTION_OUTCOME);
    }

    public long checkpointRecoveryRecordCount() {
        return recoveryDiagnostics().count(MvccSubsystemRecoveryRecordStore.Subsystem.CHECKPOINT);
    }

    public List<String> subsystemRecoveryRecordSummaries() {
        return recoveryDiagnostics().summaries();
    }

    private MvccSubsystemRecoveryRecordStore.Diagnostics recoveryDiagnostics() {
        return recoveryRecordStore.diagnostics();
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

    public PreparedChanges prepareChangedRows(List<PersistedChange<T>> changes) {
        Objects.requireNonNull(changes, "changes");
        if (!enabled() || changes.isEmpty()) {
            return PreparedChanges.empty();
        }
        try {
            List<PreparedChange> prepared = new ArrayList<>(changes.size());
            for (PersistedChange<T> change : changes) {
                if (change.delete()) {
                    prepared.add(PreparedChange.delete(change.rowId()));
                    continue;
                }
                byte[] encoded = rowCodec.encode(change.values());
                PageBackedMvccTable.requirePayloadCanBeEncoded(keyFor(change.rowId()), encoded);
                prepared.add(PreparedChange.upsert(change.rowId(), encoded));
            }
            return new PreparedChanges(prepared);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode changed MVCC page-volume state " + pageFile, e);
        }
    }

    public void requireChangedRowsCanBePersisted(List<PersistedChange<T>> changes) {
        prepareChangedRows(changes);
    }

    public void persistChangedRows(List<PersistedChange<T>> changes) {
        persistPreparedChanges(prepareChangedRows(changes), new MvccCommitSequence(nextCommitSequence()));
    }

    public void persistChangedRows(
            List<PersistedChange<T>> changes,
            MvccCommitSequence commitSequence) {
        persistPreparedChanges(prepareChangedRows(changes), commitSequence);
    }

    public void persistPreparedChanges(
            PreparedChanges preparedChanges,
            MvccCommitSequence commitSequence) {
        StagedChanges staged = stagePreparedChanges(preparedChanges, commitSequence, 0L);
        publishStagedChanges(staged);
    }

    /**
     * Durably stages one transaction payload before the transaction-status
     * COMMITTED record is published.
     *
     * <p>A complete staged batch without a terminal outcome is ignored by
     * strict recovery. The caller may therefore abort the transaction safely if
     * another group member fails before the shared status force.</p>
     */
    public StagedChanges stagePreparedChanges(
            PreparedChanges preparedChanges,
            MvccCommitSequence commitSequence,
            long statusTransactionId) {
        Objects.requireNonNull(preparedChanges, "preparedChanges");
        Objects.requireNonNull(commitSequence, "commitSequence");
        if (statusTransactionId < 0L) {
            throw new IllegalArgumentException("statusTransactionId must not be negative: "
                    + statusTransactionId);
        }
        long transactionId = nextTransactionId();
        if (!enabled() || preparedChanges.isEmpty()) {
            return StagedChanges.empty(
                    this, transactionId, statusTransactionId, commitSequence.value());
        }
        long durableCommitSequence = commitSequence.value();
        nextCommitSequence = Math.max(nextCommitSequence, durableCommitSequence + 1L);
        Map<String, MvccRowDirectoryStore.RowHeadRecord> existingHeads = new LinkedHashMap<>();
        for (MvccRowDirectoryStore.RowHeadRecord head : table.durableRowDirectoryHeads().values()) {
            existingHeads.put(head.key(), head);
        }

        boolean wroteWalTransaction = false;
        PageBackedMvccTable.PreparedTransaction pageTransaction = null;
        try {
            List<PlannedPageWrite<T>> plannedWrites = new ArrayList<>();
            List<PageVolumeMvccWriteAheadLog.VersionWrite> walWrites = new ArrayList<>();
            for (PreparedChange change : preparedChanges.changes()) {
                String key = keyFor(change.rowId());
                MvccRowDirectoryStore.RowHeadRecord existingHead = existingHeads.get(key);
                if (change.delete()) {
                    if (existingHead != null && !existingHead.tombstone()) {
                        plannedWrites.add(PlannedPageWrite.delete(key, change.rowId()));
                        walWrites.add(PageVolumeMvccWriteAheadLog.VersionWrite.delete(change.rowId()));
                    }
                    continue;
                }
                byte[] encoded = change.encodedValues();
                if (existingHead == null) {
                    plannedWrites.add(PlannedPageWrite.insert(key, change.rowId(), encoded));
                    walWrites.add(PageVolumeMvccWriteAheadLog.VersionWrite.insert(change.rowId()));
                } else if (existingHead.tombstone() || table.readPayload(key, LATEST_COMMITTED)
                        .map(payload -> !java.util.Arrays.equals(payload.value(), encoded))
                        .orElse(true)) {
                    plannedWrites.add(PlannedPageWrite.update(key, change.rowId(), encoded));
                    walWrites.add(PageVolumeMvccWriteAheadLog.VersionWrite.update(change.rowId()));
                }
            }
            if (plannedWrites.isEmpty()) {
                return StagedChanges.empty(
                        this, transactionId, statusTransactionId, durableCommitSequence);
            }

            List<DelosLogSequenceNumber> pageLsns = writeAheadLog.appendVersionBatch(
                    transactionId,
                    durableCommitSequence,
                    walWrites);
            wroteWalTransaction = true;
            List<PageBackedMvccTable.CommittedWrite> committedWrites =
                    new ArrayList<>(plannedWrites.size());
            for (int i = 0; i < plannedWrites.size(); i++) {
                PlannedPageWrite<T> planned = plannedWrites.get(i);
                DelosLogSequenceNumber pageLsn = pageLsns.get(i);
                switch (planned.operation()) {
                    case DELETE -> committedWrites.add(
                            PageBackedMvccTable.CommittedWrite.delete(planned.key(), pageLsn));
                    case INSERT -> committedWrites.add(
                            PageBackedMvccTable.CommittedWrite.insert(
                                    planned.key(), planned.encodedValues(), pageLsn));
                    case UPDATE -> committedWrites.add(
                            PageBackedMvccTable.CommittedWrite.update(
                                    planned.key(), planned.encodedValues(), pageLsn));
                    default -> throw new IllegalStateException("unknown MVCC planned page write: "
                            + planned.operation());
                }
            }
            pageTransaction = table.prepareCommittedTransaction(
                    transactionId,
                    durableCommitSequence,
                    statusTransactionId,
                    committedWrites);
            table.stagePreparedTransaction(pageTransaction);
            return new StagedChanges(
                    this,
                    transactionId,
                    statusTransactionId,
                    durableCommitSequence,
                    pageTransaction,
                    true);
        } catch (RuntimeException | Error failure) {
            abortFailedStage(transactionId, wroteWalTransaction, pageTransaction, failure);
            throw failure;
        }
    }

    /**
     * Publishes a staged transaction after the shared transaction-status force.
     * Any failure is therefore a committed transaction that requires recovery;
     * it is never converted into WAL ABORT.
     */
    public void publishStagedChanges(StagedChanges stagedChanges) {
        StagedChanges staged = requireStagedChanges(stagedChanges);
        if (staged.empty()) {
            return;
        }
        PublicationStage stage = PublicationStage.OUTCOME_FENCE;
        try {
            publicationHook.beforeStage(stage, staged);
            table.publishPreparedTransaction(staged.pageTransaction());
            stage = PublicationStage.SUBSYSTEM_RECOVERY_RECORDS;
            publicationHook.beforeStage(stage, staged);
            appendSubsystemRecoveryRecords(staged.transactionId(), staged.commitSequence());
            stage = PublicationStage.CHECKPOINT;
            publicationHook.beforeStage(stage, staged);
            rewriteCheckpoint();
        } catch (PageBackedMvccTable.CommittedTransactionMaterializationException failure) {
            throw new CommittedTransactionPublicationException(
                    staged.transactionId(),
                    staged.commitSequence(),
                    PublicationStage.PAGE_MATERIALIZATION,
                    failure);
        } catch (IOException failure) {
            throw new CommittedTransactionPublicationException(
                    staged.transactionId(), staged.commitSequence(), stage, failure);
        } catch (RuntimeException | Error failure) {
            throw new CommittedTransactionPublicationException(
                    staged.transactionId(), staged.commitSequence(), stage, failure);
        }
    }

    /** Aborts a staged payload when the shared COMMITTED status force failed. */
    public void abortStagedChanges(StagedChanges stagedChanges) {
        StagedChanges staged = requireStagedChanges(stagedChanges);
        if (staged.empty()) {
            return;
        }
        Throwable failure = null;
        try {
            table.abortPreparedTransaction(staged.transactionId());
        } catch (RuntimeException | Error abortFailure) {
            failure = abortFailure;
        }
        try {
            writeAheadLog.appendAbort(staged.transactionId());
        } catch (RuntimeException | Error walFailure) {
            if (failure == null) {
                failure = walFailure;
            } else {
                failure.addSuppressed(walFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void abortFailedStage(
            long transactionId,
            boolean wroteWalTransaction,
            PageBackedMvccTable.PreparedTransaction pageTransaction,
            Throwable failure) {
        if (pageTransaction != null) {
            try {
                table.abortPreparedTransaction(transactionId);
            } catch (RuntimeException | Error abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
        if (wroteWalTransaction) {
            try {
                writeAheadLog.appendAbort(transactionId);
            } catch (RuntimeException | Error abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
    }

    private StagedChanges requireStagedChanges(StagedChanges stagedChanges) {
        StagedChanges staged = Objects.requireNonNull(stagedChanges, "stagedChanges");
        if (staged.owner() != this) {
            throw new IllegalArgumentException("staged MVCC changes belong to another state store");
        }
        return staged;
    }

    public void setPublicationHookForTesting(PublicationHook publicationHook) {
        this.publicationHook = Objects.requireNonNull(publicationHook, "publicationHook");
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
            checkpointStore.delete();
            Path recoveryRecords = recoveryRecordStore.path();
            if (recoveryRecords != null) {
                Files.deleteIfExists(recoveryRecords);
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


    private void appendSubsystemRecoveryRecords(long transactionId, long commitSequence) {
        if (!enabled()) {
            return;
        }
        recoveryRecordStore.appendRowPageRedo(
                transactionId,
                commitSequence,
                pageCount(),
                physicalVersionCount());
        recoveryRecordStore.appendIndexPageRedo(
                orderedIndexPageCount(),
                orderedIndexEntryCount());
        recoveryRecordStore.appendOverflowPageRedo(
                overflowPageCount(),
                attributeOverflowValueBytes());
        recoveryRecordStore.appendFreeSpaceMapRedo(
                freeSpaceMapPageCount(),
                freeSpaceMapUpdateCount());
        recoveryRecordStore.appendTransactionOutcomeRedo(transactionId, commitSequence);
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
        recoveryRecordStore.appendCheckpoint(table.physicalVersionCount(), table.logicalRowCount());
        MvccStorageLifecycleJfr.recordCheckpoint(
                storageId,
                table.physicalVersionCount(),
                table.logicalRowCount(),
                checkpointStore.status().name(),
                true,
                "");
    }

    private long nextTransactionId() {
        return nextTransactionId++;
    }

    private long nextCommitSequence() {
        return nextCommitSequence++;
    }



    public enum PublicationStage {
        OUTCOME_FENCE,
        PAGE_MATERIALIZATION,
        SUBSYSTEM_RECOVERY_RECORDS,
        CHECKPOINT
    }

    @FunctionalInterface
    public interface PublicationHook {
        PublicationHook NOOP = (stage, changes) -> { };

        void beforeStage(PublicationStage stage, StagedChanges changes);
    }

    public static final class CommittedTransactionPublicationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final long transactionId;
        private final long commitSequence;
        private final PublicationStage stage;

        private CommittedTransactionPublicationException(
                long transactionId,
                long commitSequence,
                PublicationStage stage,
                Throwable cause) {
            super("MVCC transaction " + transactionId + " is committed at sequence "
                    + commitSequence + " but publication failed during " + stage, cause);
            this.transactionId = transactionId;
            this.commitSequence = commitSequence;
            this.stage = Objects.requireNonNull(stage, "stage");
        }

        public long transactionId() {
            return transactionId;
        }

        public long commitSequence() {
            return commitSequence;
        }

        public PublicationStage stage() {
            return stage;
        }
    }

    public record StagedChanges(
            PageVolumeMvccStateStore<?> owner,
            long transactionId,
            long statusTransactionId,
            long commitSequence,
            PageBackedMvccTable.PreparedTransaction pageTransaction,
            boolean walTransactionWritten) {
        public StagedChanges {
            owner = Objects.requireNonNull(owner, "owner");
            if (transactionId <= 0L) {
                throw new IllegalArgumentException("transactionId must be positive: " + transactionId);
            }
            if (statusTransactionId < 0L) {
                throw new IllegalArgumentException("statusTransactionId must not be negative: "
                        + statusTransactionId);
            }
            if (commitSequence <= 0L) {
                throw new IllegalArgumentException("commitSequence must be positive: " + commitSequence);
            }
            if ((pageTransaction == null) != !walTransactionWritten) {
                throw new IllegalArgumentException("staged page transaction and WAL state must agree");
            }
        }

        static StagedChanges empty(
                PageVolumeMvccStateStore<?> owner,
                long transactionId,
                long statusTransactionId,
                long commitSequence) {
            return new StagedChanges(
                    owner, transactionId, statusTransactionId, commitSequence, null, false);
        }

        public boolean empty() {
            return pageTransaction == null;
        }
    }

    private enum PlannedPageWriteOperation {
        INSERT,
        UPDATE,
        DELETE
    }

    private record PlannedPageWrite<T>(
            PlannedPageWriteOperation operation,
            String key,
            long rowId,
            byte[] encodedValues) {
        private PlannedPageWrite {
            operation = Objects.requireNonNull(operation, "operation");
            key = Objects.requireNonNull(key, "key");
            if (rowId <= 0L) {
                throw new IllegalArgumentException("planned inherited MVCC row id must be positive: " + rowId);
            }
            if (operation == PlannedPageWriteOperation.DELETE) {
                encodedValues = null;
            } else {
                encodedValues = Objects.requireNonNull(encodedValues, "encodedValues").clone();
            }
        }

        static <T> PlannedPageWrite<T> insert(String key, long rowId, byte[] encodedValues) {
            return new PlannedPageWrite<>(PlannedPageWriteOperation.INSERT, key, rowId, encodedValues);
        }

        static <T> PlannedPageWrite<T> update(String key, long rowId, byte[] encodedValues) {
            return new PlannedPageWrite<>(PlannedPageWriteOperation.UPDATE, key, rowId, encodedValues);
        }

        static <T> PlannedPageWrite<T> delete(String key, long rowId) {
            return new PlannedPageWrite<>(PlannedPageWriteOperation.DELETE, key, rowId, null);
        }

        @Override
        public byte[] encodedValues() {
            return encodedValues == null ? null : encodedValues.clone();
        }
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

    /** Immutable row payloads prepared before entering the table durability coordinator. */
    public static final class PreparedChanges {
        private static final PreparedChanges EMPTY = new PreparedChanges(List.of());
        private final List<PreparedChange> changes;

        private PreparedChanges(List<PreparedChange> changes) {
            this.changes = List.copyOf(changes);
        }

        static PreparedChanges empty() {
            return EMPTY;
        }

        public int size() {
            return changes.size();
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }

        private List<PreparedChange> changes() {
            return changes;
        }
    }

    private record PreparedChange(long rowId, byte[] encodedValues, boolean delete) {
        private PreparedChange {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("prepared MVCC row id must be positive: " + rowId);
            }
            if (delete) {
                encodedValues = null;
            } else {
                encodedValues = Objects.requireNonNull(encodedValues, "encodedValues").clone();
            }
        }

        static PreparedChange upsert(long rowId, byte[] encodedValues) {
            return new PreparedChange(rowId, encodedValues, false);
        }

        static PreparedChange delete(long rowId) {
            return new PreparedChange(rowId, null, true);
        }

        @Override
        public byte[] encodedValues() {
            return encodedValues == null ? null : encodedValues.clone();
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

    public record OrderedIndexLookupResult(
            Optional<List<Long>> rowIds,
            OrderedIndexLookupFallbackReason fallbackReason) {
        public OrderedIndexLookupResult {
            rowIds = Objects.requireNonNull(rowIds, "rowIds");
            if (rowIds.isPresent() && fallbackReason != null) {
                throw new IllegalArgumentException("answered ordered-index lookups must not have a fallback reason");
            }
            if (rowIds.isEmpty() && fallbackReason == null) {
                throw new IllegalArgumentException("fallback ordered-index lookups must have a reason");
            }
        }

        static OrderedIndexLookupResult answered(List<Long> rowIds) {
            return new OrderedIndexLookupResult(Optional.of(List.copyOf(rowIds)), null);
        }

        static OrderedIndexLookupResult fallback(OrderedIndexLookupFallbackReason reason) {
            return new OrderedIndexLookupResult(Optional.empty(), Objects.requireNonNull(reason, "reason"));
        }
    }

    public enum OrderedIndexLookupFallbackReason {
        UNSUPPORTED_KEY_OR_TYPE,
        MALFORMED_ORDERED_INDEX_SIDECAR,
        STALE_OR_MISSING_ORDERED_INDEX_SIDECAR
    }

}
