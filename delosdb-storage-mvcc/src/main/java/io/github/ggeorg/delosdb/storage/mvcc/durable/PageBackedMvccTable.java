package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordFlags;

/**
 * Page-backed MVCC table prototype used to move delos_mvcc away from Java-map
 * storage. It stores durable version records in the A1 page file and rebuilds
 * a row directory on open. Vacuum can compact live records into existing pages
 * and mark emptied pages reusable for later writes.
 */
public final class PageBackedMvccTable implements AutoCloseable {
    private final PageBackedMvccTableStore store;
    private MvccRowDirectory directory;
    private final MvccPageMutationLog mutationLog;
    private final MvccTransactionOutcomeLog outcomeLog;
    private final MvccRowDirectoryStore rowDirectoryStore;
    private final MvccVisibilityMapStore visibilityMapStore;
    private final MvccPurgeQueueStore purgeQueueStore;
    private NavigableMap<Long, MvccVisibilityMapStore.PageState> visibilityMapPageStates;
    private int purgeQueuePendingCount;
    private long purgeQueueEnqueueCount;
    private long purgeQueueDrainCount;
    private long purgeQueueLastDrainCount;
    private long visibilityMapUpdateCount;
    private long visibilityMapRebuildCount;
    private long pageLocalPruneAttemptCount;
    private long pageLocalPruneSuccessCount;
    private long pageLocalPruneFallbackCount;
    private long pageLocalPruneRemovedVersionCount;

    private PageBackedMvccTable(
            PageBackedMvccTableStore store,
            MvccRowDirectory directory,
            MvccPageMutationLog mutationLog,
            MvccTransactionOutcomeLog outcomeLog,
            MvccRowDirectoryStore rowDirectoryStore,
            MvccVisibilityMapStore visibilityMapStore,
            MvccPurgeQueueStore purgeQueueStore,
            NavigableMap<Long, MvccVisibilityMapStore.PageState> visibilityMapPageStates,
            int purgeQueuePendingCount) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mutationLog = mutationLog;
        this.outcomeLog = outcomeLog;
        this.rowDirectoryStore = Objects.requireNonNull(rowDirectoryStore, "rowDirectoryStore");
        this.visibilityMapStore = Objects.requireNonNull(visibilityMapStore, "visibilityMapStore");
        this.purgeQueueStore = Objects.requireNonNull(purgeQueueStore, "purgeQueueStore");
        this.visibilityMapPageStates = new TreeMap<>(Objects.requireNonNull(visibilityMapPageStates, "visibilityMapPageStates"));
        this.purgeQueuePendingCount = purgeQueuePendingCount;
    }

    public static PageBackedMvccTable open(Path path) throws IOException {
        return open(path, null);
    }

    /**
     * Opens a page-backed table and, when a mutation log is supplied, applies
     * committed log records before rebuilding the row directory from pages.
     */
    public static PageBackedMvccTable open(Path path, Path mutationLogPath) throws IOException {
        return openInternal(path, mutationLogPath, null, false);
    }

    /**
     * Opens a page-backed table with an optional transaction outcome log. If the
     * outcome log already exists, recovery treats it as the authority for
     * deciding which page mutations materialize; otherwise the legacy mutation
     * log terminal markers are used once for compatibility and future writes
     * start maintaining the outcome log.
     */
    public static PageBackedMvccTable open(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath) throws IOException {
        boolean strictRecovery = outcomeLogPath != null && Files.exists(outcomeLogPath);
        return openInternal(path, mutationLogPath, outcomeLogPath, strictRecovery);
    }

    /** Opens a table and always requires transaction-outcome-log recovery. */
    public static PageBackedMvccTable openStrict(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath) throws IOException {
        Objects.requireNonNull(mutationLogPath, "mutationLogPath");
        Objects.requireNonNull(outcomeLogPath, "outcomeLogPath");
        return openInternal(path, mutationLogPath, outcomeLogPath, true);
    }

    private static PageBackedMvccTable openInternal(
            Path path,
            Path mutationLogPath,
            Path outcomeLogPath,
            boolean strictRecovery) throws IOException {
        PageBackedMvccTableStore store = PageBackedMvccTableStore.open(path);
        try {
            MvccPageMutationLog log = null;
            MvccTransactionOutcomeLog outcomes = outcomeLogPath == null
                    ? null
                    : MvccTransactionOutcomeLog.open(outcomeLogPath);
            if (mutationLogPath != null) {
                log = MvccPageMutationLog.open(mutationLogPath);
                MvccPageRecoveryRunner recovery = new MvccPageRecoveryRunner(log, store);
                if (strictRecovery) {
                    if (outcomes == null) {
                        throw new IllegalArgumentException("strict MVCC recovery requires a transaction outcome log");
                    }
                    recovery.recoverStrict(outcomes);
                } else {
                    recovery.recover();
                }
            }
            MvccRowDirectoryStore rowDirectory = MvccRowDirectoryStore.open(rowDirectoryPath(path));
            List<String> pageRecordErrors = store.pageRecordConsistencyErrors();
            if (!pageRecordErrors.isEmpty()) {
                new MvccDurableConsistencyCheck.Result(0, 0, 0, pageRecordErrors).assertValid();
            }
            MvccRowDirectory directory = MvccRowDirectory.fromStoredRecords(store.loadAll());
            reconcileRowDirectoryWithPages(rowDirectory, directory);
            MvccVisibilityMapStore visibilityMap = MvccVisibilityMapStore.open(visibilityMapPath(path));
            NavigableMap<Long, MvccVisibilityMapStore.PageState> visibilityStates = visibilityMapFor(
                    store.pageCount(), store.loadAll(), directory.headRecords());
            visibilityMap.rewrite(store.pageCount(), visibilityStates);
            MvccPurgeQueueStore purgeQueue = MvccPurgeQueueStore.open(purgeQueuePath(path));
            int pendingPurgeEntries = purgeQueue.read().pendingCount();
            MvccDurableConsistencyCheck.check(store, rowDirectory, visibilityMap).assertValid();
            PageBackedMvccTable table = new PageBackedMvccTable(
                    store, directory, log, outcomes, rowDirectory, visibilityMap, purgeQueue, visibilityStates,
                    pendingPurgeEntries);
            table.visibilityMapRebuildCount++;
            return table;
        } catch (RuntimeException | IOException failure) {
            try {
                store.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    public synchronized MvccIndexTuple insertCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        return insertCommitted(key, stringBytes(value), transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple insertCommitted(
            String key,
            String value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        return insertCommitted(key, stringBytes(value), transactionId, commitSequence, pageLsn);
    }

    public synchronized MvccIndexTuple insertCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        return insertCommitted(key, value, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple insertCommitted(
            String key,
            byte[] value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        if (directory.rowIdForKey(key).isPresent()) {
            throw new MvccWriteConflictException("logical row already exists in durable page store: " + key);
        }
        return appendVersion(
                key, value, directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, commitSequence, 0, pageLsn);
    }

    public synchronized MvccIndexTuple insertUncommitted(String key, String value, long transactionId) throws IOException {
        return appendVersion(key, stringBytes(value), directory.nextRowId(), MvccVersionId.NONE, transactionId, 0L, 0L, 0);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, String value, long transactionId, long commitSequence)
            throws IOException {
        return updateCommitted(key, stringBytes(value), transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple updateCommitted(
            String key,
            String value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        return updateCommitted(key, stringBytes(value), transactionId, commitSequence, pageLsn);
    }

    public synchronized MvccIndexTuple updateCommitted(String key, byte[] value, long transactionId, long commitSequence)
            throws IOException {
        return updateCommitted(key, value, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple updateCommitted(
            String key,
            byte[] value,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot update row without versions: " + key));
        return appendVersion(key, value, rowId, previous, transactionId, 0L, commitSequence, 0, pageLsn);
    }

    /**
     * Appends an update only if the caller still owns the current newest row
     * version observed by its write snapshot. This is the durable-table
     * compare-and-append primitive used by the A8 concurrency proof: two same-row
     * writers racing from the same predecessor cannot both succeed.
     */
    public synchronized MvccIndexTuple updateCommittedIfCurrentVersion(
            String key,
            String value,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        return updateCommittedIfCurrentVersion(
                key, stringBytes(value), expectedCurrentVersionId, transactionId, commitSequence);
    }

    /**
     * Binary-payload overload for durable SQL row codecs.
     */
    public synchronized MvccIndexTuple updateCommittedIfCurrentVersion(
            String key,
            byte[] value,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = rowIdForExistingKey(key, "update");
        MvccVersionId previous = requireExpectedCurrentVersion(key, expectedCurrentVersionId, "update");
        return appendVersion(
                key, value, rowId, previous, transactionId, 0L, commitSequence, 0, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple deleteCommitted(String key, long transactionId, long commitSequence) throws IOException {
        return deleteCommitted(key, transactionId, commitSequence, DelosLogSequenceNumber.NONE);
    }

    public synchronized MvccIndexTuple deleteCommitted(
            String key,
            long transactionId,
            long commitSequence,
            DelosLogSequenceNumber pageLsn) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = directory.rowIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete missing row: " + key));
        MvccVersionId previous = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new IllegalStateException("cannot delete row without versions: " + key));
        return appendVersion(key, new byte[0], rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE, pageLsn);
    }

    /**
     * Appends a tombstone only if the row still has the predecessor observed by
     * the deleting writer.
     */
    public synchronized MvccIndexTuple deleteCommittedIfCurrentVersion(
            String key,
            MvccVersionId expectedCurrentVersionId,
            long transactionId,
            long commitSequence) throws IOException {
        requireCommittedSequence(commitSequence);
        MvccRowId rowId = rowIdForExistingKey(key, "delete");
        MvccVersionId previous = requireExpectedCurrentVersion(key, expectedCurrentVersionId, "delete");
        return appendVersion(key, new byte[0], rowId, previous, transactionId, transactionId, commitSequence,
                MvccVersionRecordFlags.TOMBSTONE, DelosLogSequenceNumber.NONE);
    }


    /**
     * Validates that a payload can be represented by the current MVCC durable
     * row-payload codec. Large rows may be persisted through overflow pages.
     */
    public static void requirePayloadCanBeEncoded(String key, byte[] value) {
        MvccRowPayload payload = new MvccRowPayload(key, value);
        MvccRowPayloadCodec.encode(payload);
    }

    public synchronized Optional<String> read(String key, MvccCommitSequence snapshotSequence) {
        return readPayload(key, snapshotSequence).map(MvccRowPayload::valueAsUtf8);
    }

    public synchronized Optional<MvccRowPayload> readPayload(String key, MvccCommitSequence snapshotSequence) {
        return directory.read(key, Objects.requireNonNull(snapshotSequence, "snapshotSequence"));
    }

    public synchronized Optional<MvccRowId> rowIdForKey(String key) {
        return directory.rowIdForKey(key);
    }

    public synchronized Optional<MvccVersionId> newestVersionIdForKey(String key) {
        return directory.newestVersionIdForKey(key);
    }

    public synchronized Optional<MvccVersionLocator> newestVersionLocatorForKey(String key) {
        return directory.newestVersionLocatorForKey(key);
    }

    public synchronized Optional<MvccRowDirectoryStore.RowHeadRecord> rowDirectoryHeadForRowId(MvccRowId rowId) {
        try {
            return rowDirectoryStore.headForRowId(Objects.requireNonNull(rowId, "rowId"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read durable MVCC row-directory head", e);
        }
    }

    public synchronized java.util.Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableRowDirectoryHeads() {
        try {
            return rowDirectoryStore.recoverHeads();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not recover durable MVCC row-directory heads", e);
        }
    }

    public synchronized MvccDurableConsistencyCheck.Result validateConsistency() {
        try {
            return MvccDurableConsistencyCheck.check(store, rowDirectoryStore);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not validate durable MVCC consistency", e);
        }
    }

    public synchronized Path rowDirectoryPath() {
        return rowDirectoryStore.path();
    }

    public synchronized java.util.List<MvccRowPayload> visibleRows(MvccCommitSequence snapshotSequence) {
        return directory.visiblePayloads(Objects.requireNonNull(snapshotSequence, "snapshotSequence"));
    }

    public synchronized Optional<MvccRowPayload> readVisibleIndexCandidate(
            MvccIndexTuple candidate,
            MvccCommitSequence snapshotSequence,
            Function<MvccRowPayload, Object> indexKeyExtractor,
            Object expectedIndexKey) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(snapshotSequence, "snapshotSequence");
        Objects.requireNonNull(indexKeyExtractor, "indexKeyExtractor");
        Optional<MvccRowPayload> visiblePayload = directory.readByRowId(candidate.rowId(), snapshotSequence);
        if (visiblePayload.isEmpty()) {
            return Optional.empty();
        }
        Object visibleIndexKey = indexKeyExtractor.apply(visiblePayload.get());
        return Objects.equals(expectedIndexKey, visibleIndexKey) ? visiblePayload : Optional.empty();
    }

    public synchronized boolean hasVersion(MvccRowId rowId, MvccVersionId versionId) {
        return directory.containsVersion(rowId, versionId);
    }

    public synchronized MvccVacuumResult vacuum(MvccVacuumPlan plan) throws IOException {
        Objects.requireNonNull(plan, "plan");
        MvccRowDirectory.VacuumSelection selection = directory.selectVacuum(plan.oldestVisibleThrough());
        if (selection.removedVersions() == 0) {
            return new MvccVacuumResult(
                    0,
                    0,
                    0,
                    directory.physicalVersionCount(),
                    directory.logicalRowCount());
        }
        List<MvccPurgeQueueStore.Entry> purgeEntries = purgeEntriesForSelection(selection);
        enqueuePurgeEntries(purgeEntries);
        if (tryPageLocalPrune(selection)) {
            drainPurgeQueue();
            return new MvccVacuumResult(
                    selection.removedVersions(),
                    0,
                    selection.removedLogicalRows(),
                    directory.physicalVersionCount(),
                    directory.logicalRowCount());
        }
        pageLocalPruneFallbackCount++;
        if (mutationLog != null) {
            mutationLog.rewriteCheckpoint(selection.retainedRecords());
        }
        if (outcomeLog != null) {
            outcomeLog.rewriteCheckpoint(selection.retainedRecords());
        }
        directory = MvccRowDirectory.fromStoredRecords(store.rewrite(selection.retainedRecords()));
        rowDirectoryStore.rewriteHeads(directory.headRecords());
        rebuildVisibilityMap();
        drainPurgeQueue();
        return new MvccVacuumResult(
                selection.removedVersions(),
                0,
                selection.removedLogicalRows(),
                directory.physicalVersionCount(),
                directory.logicalRowCount());
    }

    public synchronized int physicalVersionCount(String key) {
        return directory.physicalVersionCount(key);
    }

    public synchronized int physicalVersionCount() {
        return directory.physicalVersionCount();
    }

    public synchronized int logicalRowCount() {
        return directory.logicalRowCount();
    }

    public synchronized long pageCount() throws IOException {
        return store.pageCount();
    }

    public synchronized long overflowPageCount() throws IOException {
        return store.overflowPageCount();
    }

    public synchronized long reusablePageCount() {
        return store.reusablePageCount();
    }

    public synchronized long pageCacheMaxPageCount() {
        return store.pageCacheMaxPageCount();
    }

    public synchronized long pageCacheSize() {
        return store.pageCacheSize();
    }

    public synchronized long pageCacheHitCount() {
        return store.pageCacheHitCount();
    }

    public synchronized long pageCacheMissCount() {
        return store.pageCacheMissCount();
    }

    public synchronized long pageCacheWriteCount() {
        return store.pageCacheWriteCount();
    }

    public synchronized long pageCacheEvictionCount() {
        return store.pageCacheEvictionCount();
    }

    public synchronized long pageCacheInvalidationCount() {
        return store.pageCacheInvalidationCount();
    }

    public synchronized long pageCachePinCount() {
        return store.pageCachePinCount();
    }

    public synchronized long pageCacheUnpinCount() {
        return store.pageCacheUnpinCount();
    }

    public synchronized long pageCachePinnedPageCount() {
        return store.pageCachePinnedPageCount();
    }

    public synchronized long pageCacheDirtyPageCount() {
        return store.pageCacheDirtyPageCount();
    }

    public synchronized long pageCacheFlushListPageCount() {
        return store.pageCacheFlushListPageCount();
    }

    public synchronized long pageCacheFlushCount() {
        return store.pageCacheFlushCount();
    }

    public synchronized long pageCachePinnedEvictionSkipCount() {
        return store.pageCachePinnedEvictionSkipCount();
    }

    public synchronized long pageCacheLastPageGeneration() {
        return store.pageCacheLastPageGeneration();
    }


    public synchronized PageBackedMvccTableStore.PageRecordStats pageRecordStats() {
        try {
            return store.pageRecordStats();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not collect MVCC page-record stats", e);
        }
    }

    public synchronized int pageRecordSlotCount() {
        return pageRecordStats().slotCount();
    }

    public synchronized int wrappedPageRecordCount() {
        return pageRecordStats().wrappedRecordCount();
    }

    public synchronized int legacyPageRecordCount() {
        return pageRecordStats().legacyRecordCount();
    }

    public synchronized int nonVersionPageRecordCount() {
        return pageRecordStats().nonVersionRecordCount();
    }

    public synchronized Path reusablePageIndexPath() {
        return store.reusablePageIndexPath();
    }

    public synchronized Path freeSpaceMapPath() {
        return store.freeSpaceMapPath();
    }

    public synchronized long freeSpaceMapPageCount() {
        return store.freeSpaceMapPageCount();
    }

    public synchronized int freeSpaceMapMaxFreeBytes() {
        return store.freeSpaceMapMaxFreeBytes();
    }

    public synchronized long freeSpaceMapLookupCount() {
        return store.freeSpaceMapLookupCount();
    }

    public synchronized long freeSpaceMapHitCount() {
        return store.freeSpaceMapHitCount();
    }

    public synchronized long freeSpaceMapNonLastHitCount() {
        return store.freeSpaceMapNonLastHitCount();
    }

    public synchronized long freeSpaceMapMissCount() {
        return store.freeSpaceMapMissCount();
    }

    public synchronized long freeSpaceMapStaleEntryCount() {
        return store.freeSpaceMapStaleEntryCount();
    }

    public synchronized long freeSpaceMapUpdateCount() {
        return store.freeSpaceMapUpdateCount();
    }

    public synchronized long freeSpaceMapRebuildCount() {
        return store.freeSpaceMapRebuildCount();
    }

    public synchronized java.util.List<String> freeSpaceMapPageSummaries() {
        return store.freeSpaceMapPageSummaries();
    }

    public synchronized Path visibilityMapPath() {
        return visibilityMapStore.path();
    }

    public synchronized long visibilityMapPageCount() {
        return visibilityMapPageStates.size();
    }

    public synchronized long visibilityMapOldVersionPageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.HAS_OLD_VERSIONS);
    }

    public synchronized long visibilityMapPrunablePageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.HAS_PRUNABLE_VERSIONS);
    }

    public synchronized long visibilityMapTombstonePageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.HAS_TOMBSTONES);
    }

    public synchronized long visibilityMapAllVisiblePageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.ALL_VISIBLE);
    }

    public synchronized long visibilityMapOverflowPageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.HAS_OVERFLOW_REFERENCES);
    }

    public synchronized long visibilityMapNeedsCheckerPageCount() {
        return visibilityMapPageCountWith(MvccVisibilityMapStore.NEEDS_CHECKER);
    }

    public synchronized long visibilityMapUpdateCount() {
        return visibilityMapUpdateCount;
    }

    public synchronized long visibilityMapRebuildCount() {
        return visibilityMapRebuildCount;
    }

    public synchronized java.util.List<String> visibilityMapPageSummaries() {
        return visibilityMapPageStates.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + visibilityFlagsSummary(entry.getValue()))
                .toList();
    }

    public synchronized long pageLocalPruneAttemptCount() {
        return pageLocalPruneAttemptCount;
    }

    public synchronized long pageLocalPruneSuccessCount() {
        return pageLocalPruneSuccessCount;
    }

    public synchronized long pageLocalPruneFallbackCount() {
        return pageLocalPruneFallbackCount;
    }

    public synchronized long pageLocalPruneRemovedVersionCount() {
        return pageLocalPruneRemovedVersionCount;
    }

    public synchronized Path purgeQueuePath() {
        return purgeQueueStore.path();
    }

    public synchronized long purgeQueuePendingCount() {
        return purgeQueuePendingCount;
    }

    public synchronized long purgeQueueEnqueueCount() {
        return purgeQueueEnqueueCount;
    }

    public synchronized long purgeQueueDrainCount() {
        return purgeQueueDrainCount;
    }

    public synchronized long purgeQueueLastDrainCount() {
        return purgeQueueLastDrainCount;
    }

    public synchronized java.util.List<String> purgeQueueEntrySummaries() {
        try {
            return purgeQueueStore.read().entries().stream()
                    .map(entry -> "row:" + entry.rowId()
                            + "|version:" + entry.versionId()
                            + "|page:" + entry.pageId()
                            + "|previous:" + entry.previousVersionId()
                            + "|flags:" + entry.flags())
                    .toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read MVCC purge queue", e);
        }
    }

    public synchronized long pageMutationContextBeginCount() {
        return store.pageMutationContextBeginCount();
    }

    public synchronized long pageMutationContextCommitCount() {
        return store.pageMutationContextCommitCount();
    }

    public synchronized long pageMutationContextAbortCount() {
        return store.pageMutationContextAbortCount();
    }

    public synchronized long pageMutationContextPageReservationCount() {
        return store.pageMutationContextPageReservationCount();
    }

    public synchronized long pageMutationContextReservedBytes() {
        return store.pageMutationContextReservedBytes();
    }

    public synchronized long pageMutationContextPageWriteCount() {
        return store.pageMutationContextPageWriteCount();
    }

    public synchronized long pageMutationContextFreeSpaceMapUpdateCount() {
        return store.pageMutationContextFreeSpaceMapUpdateCount();
    }

    public synchronized long pageMutationContextReusableIndexUpdateCount() {
        return store.pageMutationContextReusableIndexUpdateCount();
    }

    public synchronized String lastPageMutationContextOperation() {
        return store.lastPageMutationContextOperation();
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        try {
            store.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            rowDirectoryStore.close();
        } catch (Exception e) {
            if (failure == null) {
                failure = e instanceof IOException io ? io : new IOException(e);
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void enqueuePurgeEntries(List<MvccPurgeQueueStore.Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            purgeQueueLastDrainCount = 0L;
            return;
        }
        purgeQueueStore.rewrite(entries);
        purgeQueuePendingCount = entries.size();
        purgeQueueEnqueueCount += entries.size();
        purgeQueueLastDrainCount = 0L;
    }

    private void drainPurgeQueue() throws IOException {
        int drained = purgeQueueStore.read().pendingCount();
        purgeQueueStore.rewrite(List.of());
        purgeQueuePendingCount = 0;
        purgeQueueDrainCount += drained;
        purgeQueueLastDrainCount = drained;
    }

    private List<MvccPurgeQueueStore.Entry> purgeEntriesForSelection(
            MvccRowDirectory.VacuumSelection selection) throws IOException {
        if (selection.removedVersions() == 0) {
            return List.of();
        }
        Map<MvccVersionId, PageBackedMvccTableStore.StoredVersionRecord> currentByVersion = new LinkedHashMap<>();
        for (PageBackedMvccTableStore.StoredVersionRecord current : store.loadAll()) {
            currentByVersion.put(current.record().header().versionId(), current);
        }
        Set<MvccVersionId> retainedVersionIds = selection.retainedRecords().stream()
                .map(record -> record.header().versionId())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<MvccPurgeQueueStore.Entry> entries = new java.util.ArrayList<>();
        for (PageBackedMvccTableStore.StoredVersionRecord current : currentByVersion.values()) {
            if (!retainedVersionIds.contains(current.record().header().versionId())) {
                entries.add(MvccPurgeQueueStore.entryFor(current));
            }
        }
        return entries;
    }

    private boolean tryPageLocalPrune(MvccRowDirectory.VacuumSelection selection) throws IOException {
        pageLocalPruneAttemptCount++;
        List<PageBackedMvccTableStore.StoredVersionRecord> currentRecords = store.loadAll();
        PageLocalPruneCandidate candidate = pageLocalPruneCandidate(currentRecords, selection.retainedRecords());
        if (candidate == null) {
            return false;
        }
        if (mutationLog != null) {
            mutationLog.rewriteCheckpoint(selection.retainedRecords());
        }
        if (outcomeLog != null) {
            outcomeLog.rewriteCheckpoint(selection.retainedRecords());
        }
        directory = MvccRowDirectory.fromStoredRecords(
                store.rewritePage(candidate.pageId(), candidate.retainedPageRecords()));
        rowDirectoryStore.rewriteHeads(directory.headRecords());
        rebuildVisibilityMap();
        pageLocalPruneSuccessCount++;
        pageLocalPruneRemovedVersionCount += selection.removedVersions();
        return true;
    }

    private static PageLocalPruneCandidate pageLocalPruneCandidate(
            List<PageBackedMvccTableStore.StoredVersionRecord> currentRecords,
            List<MvccVersionRecord> retainedRecords) {
        Map<MvccVersionId, PageBackedMvccTableStore.StoredVersionRecord> currentByVersion = new LinkedHashMap<>();
        Map<MvccVersionId, MvccVersionRecord> retainedByVersion = new LinkedHashMap<>();
        for (PageBackedMvccTableStore.StoredVersionRecord current : currentRecords) {
            currentByVersion.put(current.record().header().versionId(), current);
        }
        for (MvccVersionRecord retained : retainedRecords) {
            retainedByVersion.put(retained.header().versionId(), retained);
        }
        Set<MvccVersionId> removedVersionIds = new java.util.LinkedHashSet<>(currentByVersion.keySet());
        removedVersionIds.removeAll(retainedByVersion.keySet());
        if (removedVersionIds.isEmpty()) {
            return null;
        }
        Long prunePageId = null;
        for (MvccVersionId removedVersionId : removedVersionIds) {
            PageBackedMvccTableStore.StoredVersionRecord removed = currentByVersion.get(removedVersionId);
            if (removed == null) {
                return null;
            }
            long pageId = removed.locator().pageId().value();
            if (prunePageId == null) {
                prunePageId = pageId;
            } else if (prunePageId.longValue() != pageId) {
                return null;
            }
        }
        if (prunePageId == null) {
            return null;
        }
        for (PageBackedMvccTableStore.StoredVersionRecord current : currentRecords) {
            if (current.locator().pageId().value() == prunePageId
                    && PageBackedMvccTableStore.requiresOverflowPayload(current.record())) {
                return null;
            }
        }
        List<MvccVersionRecord> retainedForPage = new java.util.ArrayList<>();
        for (PageBackedMvccTableStore.StoredVersionRecord current : currentRecords) {
            MvccVersionId versionId = current.record().header().versionId();
            MvccVersionRecord retained = retainedByVersion.get(versionId);
            if (retained == null) {
                continue;
            }
            if (current.locator().pageId().value() == prunePageId) {
                retainedForPage.add(retained);
            } else if (!current.record().equals(retained)) {
                return null;
            }
        }
        return new PageLocalPruneCandidate(prunePageId, retainedForPage);
    }

    private MvccRowId rowIdForExistingKey(String key, String operation) {
        return directory.rowIdForKey(key)
                .orElseThrow(() -> new MvccWriteConflictException("cannot " + operation + " missing row: " + key));
    }

    private MvccVersionId requireExpectedCurrentVersion(
            String key,
            MvccVersionId expectedCurrentVersionId,
            String operation) {
        Objects.requireNonNull(expectedCurrentVersionId, "expectedCurrentVersionId");
        MvccVersionId current = directory.newestVersionIdForKey(key)
                .orElseThrow(() -> new MvccWriteConflictException(
                        "cannot " + operation + " row without versions: " + key));
        if (!current.equals(expectedCurrentVersionId)) {
            throw new MvccWriteConflictException("cannot " + operation + " row " + key
                    + " from stale version " + expectedCurrentVersionId + "; current version is " + current);
        }
        return current;
    }

    private MvccIndexTuple appendVersion(
            String key,
            byte[] value,
            MvccRowId rowId,
            MvccVersionId previousVersionId,
            long transactionId,
            long deletedByTx,
            long commitSequence,
            int flags) throws IOException {
        return appendVersion(
                key,
                value,
                rowId,
                previousVersionId,
                transactionId,
                deletedByTx,
                commitSequence,
                flags,
                DelosLogSequenceNumber.NONE);
    }

    private MvccIndexTuple appendVersion(
            String key,
            byte[] value,
            MvccRowId rowId,
            MvccVersionId previousVersionId,
            long transactionId,
            long deletedByTx,
            long commitSequence,
            int flags,
            DelosLogSequenceNumber pageLsn) throws IOException {
        MvccVersionId versionId = directory.nextVersionId();
        MvccRowPayload payload = new MvccRowPayload(key, value);
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        rowId,
                        versionId,
                        previousVersionId,
                        new MvccTransactionId(transactionId),
                        new MvccTransactionId(deletedByTx),
                        new MvccCommitSequence(commitSequence),
                        flags),
                MvccRowPayloadCodec.encode(payload));
        pageLsn = Objects.requireNonNull(pageLsn, "pageLsn");
        MvccVersionLocator locator = commitSequence > 0L
                ? appendCommittedRecord(transactionId, commitSequence, record, pageLsn)
                : store.append(record, pageLsn);
        directory.addNewCommitted(key, rowId, new MvccRowDirectory.StoredVersion(locator, record, payload));
        rowDirectoryStore.recordHead(new MvccRowDirectoryStore.RowHeadRecord(
                rowId,
                key,
                versionId,
                previousVersionId,
                locator,
                record.header().isTombstone()));
        rebuildVisibilityMap();
        return MvccIndexTuple.active(rowId, versionId, locator);
    }

    private MvccVersionLocator appendCommittedRecord(
            long transactionId,
            long commitSequence,
            MvccVersionRecord record,
            DelosLogSequenceNumber pageLsn) throws IOException {
        if (mutationLog != null) {
            mutationLog.appendVersion(transactionId, record);
        }
        if (outcomeLog != null) {
            outcomeLog.appendCommit(transactionId, commitSequence);
        }
        if (mutationLog != null) {
            mutationLog.appendCommit(transactionId, commitSequence);
        }
        return store.append(record, pageLsn);
    }

    private static void reconcileRowDirectoryWithPages(
            MvccRowDirectoryStore rowDirectoryStore,
            MvccRowDirectory pageDirectory) throws IOException {
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> pageHeads = pageDirectory.headRecords().stream()
                .collect(java.util.stream.Collectors.toMap(
                        MvccRowDirectoryStore.RowHeadRecord::rowId,
                        java.util.function.Function.identity(),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new));
        Map<MvccRowId, MvccRowDirectoryStore.RowHeadRecord> durableHeads = rowDirectoryStore.recoverHeads();
        if (!durableHeads.equals(pageHeads)) {
            rowDirectoryStore.rewriteHeads(pageHeads.values());
        }
    }

    public static Path rowDirectoryPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".rowdir");
    }

    public static Path overflowPath(Path pageFile) {
        return PageBackedMvccTableStore.overflowPath(pageFile);
    }

    public static Path reusablePageIndexPath(Path pageFile) {
        return PageBackedMvccTableStore.reusablePageIndexPath(pageFile);
    }

    public static Path freeSpaceMapPath(Path pageFile) {
        return PageBackedMvccTableStore.freeSpaceMapPath(pageFile);
    }

    public static Path visibilityMapPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".vmap");
    }

    public static Path purgeQueuePath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".purge");
    }

    public static Path orderedIndexPagesPath(Path pageFile) {
        Objects.requireNonNull(pageFile, "pageFile");
        return pageFile.resolveSibling(pageFile.getFileName() + ".oindex");
    }

    private void rebuildVisibilityMap() throws IOException {
        visibilityMapPageStates = visibilityMapFor(store.pageCount(), store.loadAll(), directory.headRecords());
        visibilityMapStore.rewrite(store.pageCount(), visibilityMapPageStates);
        visibilityMapUpdateCount++;
    }

    private long visibilityMapPageCountWith(int flag) {
        return visibilityMapPageStates.values().stream()
                .filter(state -> state.hasFlag(flag))
                .count();
    }

    private static NavigableMap<Long, MvccVisibilityMapStore.PageState> visibilityMapFor(
            long pageCount,
            List<PageBackedMvccTableStore.StoredVersionRecord> records,
            List<MvccRowDirectoryStore.RowHeadRecord> heads) {
        NavigableMap<Long, VisibilityAccumulator> accumulators = new TreeMap<>();
        for (long page = 0L; page < pageCount; page++) {
            accumulators.put(page, new VisibilityAccumulator());
        }
        Map<MvccRowId, MvccVersionId> headVersionByRow = heads.stream()
                .collect(java.util.stream.Collectors.toMap(
                        MvccRowDirectoryStore.RowHeadRecord::rowId,
                        MvccRowDirectoryStore.RowHeadRecord::headVersionId));
        for (PageBackedMvccTableStore.StoredVersionRecord stored : records) {
            long page = stored.locator().pageId().value();
            VisibilityAccumulator accumulator = accumulators.computeIfAbsent(page, ignored -> new VisibilityAccumulator());
            MvccTupleHeader header = stored.record().header();
            boolean oldVersion = !Objects.equals(headVersionByRow.get(header.rowId()), header.versionId());
            boolean tombstone = header.isTombstone();
            boolean committed = !header.commitSequence().equals(MvccCommitSequence.NONE);
            boolean overflowReference = MvccOverflowPayloadReferenceCodec.isOverflowReference(stored.record().payload());
            accumulator.add(oldVersion, oldVersion || tombstone, tombstone, committed, overflowReference);
        }
        NavigableMap<Long, MvccVisibilityMapStore.PageState> states = new TreeMap<>();
        for (var entry : accumulators.entrySet()) {
            states.put(entry.getKey(), entry.getValue().toPageState());
        }
        return states;
    }

    private static String visibilityFlagsSummary(MvccVisibilityMapStore.PageState state) {
        List<String> flags = new java.util.ArrayList<>();
        if (state.hasFlag(MvccVisibilityMapStore.HAS_OLD_VERSIONS)) {
            flags.add("old");
        }
        if (state.hasFlag(MvccVisibilityMapStore.HAS_PRUNABLE_VERSIONS)) {
            flags.add("prunable");
        }
        if (state.hasFlag(MvccVisibilityMapStore.ALL_VISIBLE)) {
            flags.add("allVisible");
        }
        if (state.hasFlag(MvccVisibilityMapStore.HAS_TOMBSTONES)) {
            flags.add("tombstone");
        }
        if (state.hasFlag(MvccVisibilityMapStore.HAS_OVERFLOW_REFERENCES)) {
            flags.add("overflow");
        }
        if (state.hasFlag(MvccVisibilityMapStore.NEEDS_CHECKER)) {
            flags.add("needsChecker");
        }
        if (flags.isEmpty()) {
            flags.add("empty");
        }
        return String.join("+", flags) + ",versions=" + state.versionCount();
    }

    private record PageLocalPruneCandidate(long pageId, List<MvccVersionRecord> retainedPageRecords) {
        private PageLocalPruneCandidate {
            retainedPageRecords = List.copyOf(Objects.requireNonNull(retainedPageRecords, "retainedPageRecords"));
        }
    }

    private static final class VisibilityAccumulator {
        private int flags;
        private int versionCount;
        private boolean allRecordsCommitted = true;

        private void add(
                boolean oldVersion,
                boolean prunable,
                boolean tombstone,
                boolean committed,
                boolean overflowReference) {
            versionCount++;
            if (oldVersion) {
                flags |= MvccVisibilityMapStore.HAS_OLD_VERSIONS;
            }
            if (prunable) {
                flags |= MvccVisibilityMapStore.HAS_PRUNABLE_VERSIONS;
            }
            if (tombstone) {
                flags |= MvccVisibilityMapStore.HAS_TOMBSTONES;
            }
            if (overflowReference) {
                flags |= MvccVisibilityMapStore.HAS_OVERFLOW_REFERENCES;
            }
            allRecordsCommitted &= committed;
        }

        private MvccVisibilityMapStore.PageState toPageState() {
            int visibleFlags = flags;
            if (versionCount > 0
                    && allRecordsCommitted
                    && (visibleFlags & MvccVisibilityMapStore.HAS_PRUNABLE_VERSIONS) == 0) {
                visibleFlags |= MvccVisibilityMapStore.ALL_VISIBLE;
            }
            return new MvccVisibilityMapStore.PageState(visibleFlags, versionCount);
        }
    }

    private static byte[] stringBytes(String value) {
        Objects.requireNonNull(value, "value");
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void requireCommittedSequence(long commitSequence) {
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commit sequence must be positive for committed durable rows: "
                    + commitSequence);
        }
    }
}
