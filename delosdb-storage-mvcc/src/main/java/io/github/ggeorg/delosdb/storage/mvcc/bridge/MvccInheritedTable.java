package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommandSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.store.MvccCandidateIndex;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageCommittedRead;
import org.apache.derby.iapi.store.types.DelosStorageMaintenance;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageRowHead;
import org.apache.derby.iapi.store.types.DelosStorageRowLocator;
import org.apache.derby.iapi.store.types.DelosStorageSavepointParticipant;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.shared.common.error.StandardException;

final class MvccInheritedTable implements DelosStorageTable,
        DelosStorageMaintenance,
        DelosStorageRowLocator,
        DelosStorageCandidateIndex,
        DelosStorageCommittedRead,
        DelosStorageSavepointParticipant,
        DelosStorageTableDiagnostics {
    private final long segmentId;
    private final long containerId;
    private final Path retiredSnapshotFile;
    private final Path transactionStatusFile;
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final MvccTransactionManager transactions;
    private final MvccCandidateIndex candidateIndex = new MvccCandidateIndex();
    private final List<MvccInheritedHandles.Transaction> activeTransactions = new ArrayList<>();
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    private final Lock readLock = tableLock.readLock();
    private final Lock writeLock = tableLock.writeLock();
    private long nextRowId = 1L;
    private int lastCommittedChangedRowCount;
    private int lastCommittedWriteIntentCount;
    private List<String> lastCommittedWriteIntentPayloadSummaries = List.of();
    private int providerFirstWriteAppendCount;
    private int legacyWriteFrontShadowMutationCount;
    private int legacyWriteFrontShadowBypassCount;
    private int legacyWriteFrontQuarantineViolationCount;
    private int providerFirstWriteAppendFailureRollbackCount;
    private int transactionLocalWriteIntentReadCount;
    private int transactionLocalWriteIntentScanCount;
    private int transactionLocalPageBackedBaseReadCount;
    private int transactionLocalPageBackedBaseScanCount;
    private int pageBackedHistoricalSnapshotReadCount;
    private int pageBackedHistoricalSnapshotScanCount;
    private int pageBackedCandidateIndexRebuildCount;
    private DelosVacuumOutcome lastVacuumOutcome = DelosVacuumOutcome.disabled();

    MvccInheritedTable(long segmentId, long containerId, Path databaseDirectory) {
        this.segmentId = segmentId;
        this.containerId = containerId;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, segmentId, containerId);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, segmentId, containerId);
        this.pageVolumeStateStore = PageVolumeMvccStateStore.open(
                databaseDirectory,
                storageId(segmentId, containerId),
                MvccInheritedRowCodec.INSTANCE);
        this.transactionStatusStore = transactionStatusFile == null || containerId == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        loadCommittedState();
    }

    @Override
    public DelosStorageTransaction beginTransaction() {
        return writeLocked(() -> {
            MvccInheritedHandles.Transaction transaction = new MvccInheritedHandles.Transaction(transactions.begin());
            activeTransactions.add(transaction);
            return transaction;
        });
    }

    @Override
    public DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
        return readLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            return new MvccInheritedHandles.Snapshot(handle, transactions.snapshot(handle.nativeTransaction()));
        });
    }

    @Override
    public DelosStorageSnapshot snapshot(
            DelosStorageTransaction transaction,
            DelosStorageSnapshot visibilitySnapshot) {
        return readLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            return new MvccInheritedHandles.Snapshot(handle, nativeSnapshot(visibilitySnapshot));
        });
    }

    @Override
    public DelosStorageScan openScan(DelosStorageSnapshot snapshot) {
        return readLocked(() -> {
            Optional<List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>>> writeIntentRows =
                    writeIntentOverlayRows(snapshot);
            if (writeIntentRows.isPresent()) {
                transactionLocalWriteIntentScanCount++;
                return new MvccPageBackedCommittedScan(writeIntentRows.get());
            }
            if (canReadCommittedImageUnlocked(snapshot)) {
                transactionLocalPageBackedBaseScanCount++;
                return new MvccPageBackedCommittedScan(pageVolumeStateStore.loadVisibleRows());
            }
            pageBackedHistoricalSnapshotScanCount++;
            return new MvccPageBackedCommittedScan(
                    pageVolumeStateStore.loadVisibleRows(nativeSnapshot(snapshot).visibleThrough()));
        });
    }

    @Override
    public Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
        return readLocked(() -> {
            Optional<StoreDataValue[]> writeIntentRow = readWriteIntent(rowId, snapshot);
            if (writeIntentRow.isPresent()) {
                transactionLocalWriteIntentReadCount++;
                return writeIntentRow;
            }
            if (writeIntentDeletesRow(rowId, snapshot)) {
                transactionLocalWriteIntentReadCount++;
                return Optional.empty();
            }
            if (canReadCommittedImageUnlocked(snapshot)) {
                transactionLocalPageBackedBaseReadCount++;
                return pageVolumeStateStore.loadVisibleRow(rowId)
                        .map(PageVolumeMvccStateStore.PersistedRow::values)
                        .map(MvccInheritedTable::cloneRowUnchecked);
            }
            pageBackedHistoricalSnapshotReadCount++;
            return pageVolumeStateStore.loadVisibleRow(rowId, nativeSnapshot(snapshot).visibleThrough())
                    .map(PageVolumeMvccStateStore.PersistedRow::values)
                    .map(MvccInheritedTable::cloneRowUnchecked);
        });
    }


    @Override
    public boolean canReadCommittedImage(DelosStorageSnapshot snapshot) {
        return readLocked(() -> {
            MvccSnapshot nativeSnapshot = nativeSnapshot(snapshot);
            return nativeSnapshot.visibleThrough().equals(transactions.newestCommitSequence());
        });
    }


    @Override
    public DelosStorageScan openCommittedImageScan(DelosStorageSnapshot snapshot) {
        return readLocked(() -> {
            if (!canReadCommittedImageUnlocked(snapshot)) {
                throw new IllegalStateException("MVCC committed image is newer than snapshot");
            }
            return new MvccPageBackedCommittedScan(pageVolumeStateStore.loadVisibleRows());
        });
    }

    @Override
    public Optional<StoreDataValue[]> readCommittedImage(long rowId, DelosStorageSnapshot snapshot) {
        return readLocked(() -> {
            if (!canReadCommittedImageUnlocked(snapshot)) {
                return Optional.empty();
            }
            return pageVolumeStateStore.loadVisibleRow(rowId)
                    .map(PageVolumeMvccStateStore.PersistedRow::values);
        });
    }

    @Override
    public void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            MvccCommandSequence commandSequence = handle.nextCommandSequence();
            StoreDataValue[] rowVersion = cloneRowUnchecked(row);
            recordProviderFirstUpsertWriteIntent(handle, rowId, rowVersion, commandSequence);
            recordRemovedLegacyWriteFrontBypass(handle, rowId, commandSequence, false);
        });
    }

    @Override
    public void update(
            long rowId,
            StoreDataValue[] replacement,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            MvccCommandSequence commandSequence = handle.nextCommandSequence();
            StoreDataValue[] rowVersion = cloneRowUnchecked(replacement);
            requireProviderVisibleRowForWrite(rowId, snapshot, "update");
            requireNoOtherActiveProviderWriter(handle, rowId, "update");
            recordProviderFirstUpsertWriteIntent(handle, rowId, rowVersion, commandSequence);
            recordRemovedLegacyWriteFrontBypass(handle, rowId, commandSequence, false);
        });
    }

    @Override
    public void delete(
            long rowId,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            MvccCommandSequence commandSequence = handle.nextCommandSequence();
            requireProviderVisibleRowForWrite(rowId, snapshot, "delete");
            requireNoOtherActiveProviderWriter(handle, rowId, "delete");
            recordProviderFirstDeleteWriteIntent(handle, rowId, commandSequence);
            recordRemovedLegacyWriteFrontBypass(handle, rowId, commandSequence, true);
        });
    }

    @Override
    public void commit(DelosStorageTransaction transaction) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            try {
                MvccTransaction nativeTx = handle.nativeTransaction();
                List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes = changedRows(handle);
                try {
                    pageVolumeStateStore.requireChangedRowsCanBePersisted(changes);
                } catch (RuntimeException failure) {
                    abortIfActive(nativeTx, failure);
                    handle.clearWriteIntents();
                    throw failure;
                }
                MvccCommitSequence commitSequence = transactions.commit(nativeTx);
                persistCommittedChangesUnlocked(changes, commitSequence);
                lastCommittedChangedRowCount = changes.size();
                lastCommittedWriteIntentCount = handle.writeIntentCount();
                lastCommittedWriteIntentPayloadSummaries = committedChangePayloadSummaries(changes);
                handle.clearWriteIntents();
            } finally {
                activeTransactions.remove(handle);
            }
        });
    }

    @Override
    public void abort(DelosStorageTransaction transaction) {
        writeLocked(() -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            try {
                transactions.abort(handle.nativeTransaction());
                handle.clearWriteIntents();
            } finally {
                activeTransactions.remove(handle);
            }
        });
    }

    @Override
    public void setSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> nativeTransactionHandle(transaction).setSavepoint(savepointName));
    }

    @Override
    public void rollbackToSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> {
            nativeTransactionHandle(transaction).rollbackToSavepoint(savepointName);
        });
    }

    @Override
    public void releaseSavepoint(DelosStorageTransaction transaction, String savepointName) {
        writeLocked(() -> nativeTransactionHandle(transaction).releaseSavepoint(savepointName));
    }

    @Override
    public long nextRowId() {
        return writeLocked(() -> nextRowId++);
    }

    private void recordProviderFirstUpsertWriteIntent(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            StoreDataValue[] rowVersion,
            MvccCommandSequence commandSequence) {
        handle.recordUpsertWriteIntent(rowId, cloneRowUnchecked(rowVersion), commandSequence);
        providerFirstWriteAppendCount++;
    }

    private void recordProviderFirstDeleteWriteIntent(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            MvccCommandSequence commandSequence) {
        handle.recordDeleteWriteIntent(rowId, commandSequence);
        providerFirstWriteAppendCount++;
    }


    private void recordRemovedLegacyWriteFrontBypass(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            MvccCommandSequence commandSequence,
            boolean delete) {
        if (!handle.hasAppendedWriteIntent(rowId, commandSequence, delete)) {
            legacyWriteFrontQuarantineViolationCount++;
            throw new IllegalStateException("Removed inherited MVCC write-front bypass attempted "
                    + "without a matching provider-first write intent for row " + rowId);
        }
        legacyWriteFrontShadowBypassCount++;
    }

    private void persistCommittedChangesUnlocked(
            List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes,
            MvccCommitSequence commitSequence) {
        pageVolumeStateStore.persistChangedRows(changes, commitSequence);
        rebuildCandidateIndexFromPageBackedCommittedRows();
    }

    @Override
    public void dropDurableState() {
        writeLocked(() -> {
            candidateIndex.clear();
            try {
                pageVolumeStateStore.drop();
                if (retiredSnapshotFile != null) {
                    Files.deleteIfExists(retiredSnapshotFile);
                }
                if (transactionStatusFile != null) {
                    Files.deleteIfExists(transactionStatusFile);
                }
                Path pageMutationLogFile = pageVolumeStateStore.pageMutationLogFile();
                if (pageMutationLogFile != null) {
                    Files.deleteIfExists(pageMutationLogFile);
                }
                Path writeAheadLogFile = pageVolumeStateStore.writeAheadLogFile();
                if (writeAheadLogFile != null) {
                    Files.deleteIfExists(writeAheadLogFile);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not delete inherited MVCC state for "
                        + segmentId + ":" + containerId, e);
            }
        });
    }

    @Override
    public DelosStorageRowHead rowHeadFor(long rowId) {
        return readLocked(() -> pageVolumeStateStore.rowHeadForInheritedRowId(rowId)
                .map(head -> DelosStorageRowHead.present(
                        rowId,
                        head.headLocator().pageId().value(),
                        head.headLocator().slotId()))
                .orElseGet(() -> DelosStorageRowHead.absent(rowId)));
    }

    @Override
    public Optional<List<Long>> candidateRowIdsFor(int column, String value) {
        return readLocked(() -> candidateIndex.candidatesFor(column, value));
    }

    @Override
    public int candidateIndexKeyCountForTesting() {
        return readLocked(candidateIndex::indexedKeyCountForTesting);
    }

    @Override
    public int lastCommittedChangedRowCountForTesting() {
        return readLocked(() -> lastCommittedChangedRowCount);
    }

    @Override
    public int lastCommittedWriteIntentCountForTesting() {
        return readLocked(() -> lastCommittedWriteIntentCount);
    }

    @Override
    public List<String> lastCommittedWriteIntentPayloadSummariesForTesting() {
        return readLocked(() -> lastCommittedWriteIntentPayloadSummaries);
    }

    @Override
    public int activeProviderWriteAppendCountForTesting() {
        return readLocked(() -> activeTransactions.stream()
                .mapToInt(MvccInheritedHandles.Transaction::appendedWriteIntentCount)
                .sum());
    }

    @Override
    public List<String> activeProviderWriteAppendPayloadSummariesForTesting() {
        return readLocked(() -> writeIntentPayloadSummaries(activeAppendedWriteIntents()));
    }

    @Override
    public int activeProviderSurvivingWriteIntentCountForTesting() {
        return readLocked(() -> activeTransactions.stream()
                .mapToInt(MvccInheritedHandles.Transaction::writeIntentCount)
                .sum());
    }

    @Override
    public List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting() {
        return readLocked(() -> writeIntentPayloadSummaries(activeSurvivingWriteIntents()));
    }

    @Override
    public int providerFirstWriteAppendCountForTesting() {
        return readLocked(() -> providerFirstWriteAppendCount);
    }

    @Override
    public int legacyWriteFrontShadowMutationCountForTesting() {
        return readLocked(() -> legacyWriteFrontShadowMutationCount);
    }

    @Override
    public int legacyWriteFrontShadowBypassCountForTesting() {
        return readLocked(() -> legacyWriteFrontShadowBypassCount);
    }

    @Override
    public boolean legacyWriteFrontShadowEnabledForTesting() {
        return false;
    }

    @Override
    public int legacyWriteFrontQuarantineViolationCountForTesting() {
        return readLocked(() -> legacyWriteFrontQuarantineViolationCount);
    }

    @Override
    public int providerFirstWriteAppendFailureRollbackCountForTesting() {
        return readLocked(() -> providerFirstWriteAppendFailureRollbackCount);
    }

    @Override
    public int transactionLocalWriteIntentReadCountForTesting() {
        return readLocked(() -> transactionLocalWriteIntentReadCount);
    }

    @Override
    public int transactionLocalWriteIntentScanCountForTesting() {
        return readLocked(() -> transactionLocalWriteIntentScanCount);
    }

    @Override
    public int transactionLocalPageBackedBaseReadCountForTesting() {
        return readLocked(() -> transactionLocalPageBackedBaseReadCount);
    }

    @Override
    public int transactionLocalPageBackedBaseScanCountForTesting() {
        return readLocked(() -> transactionLocalPageBackedBaseScanCount);
    }

    @Override
    public int pageBackedHistoricalSnapshotReadCountForTesting() {
        return readLocked(() -> pageBackedHistoricalSnapshotReadCount);
    }

    @Override
    public int pageBackedHistoricalSnapshotScanCountForTesting() {
        return readLocked(() -> pageBackedHistoricalSnapshotScanCount);
    }

    @Override
    public int pageBackedCandidateIndexRebuildCountForTesting() {
        return readLocked(() -> pageBackedCandidateIndexRebuildCount);
    }

    @Override
    public int legacyCandidateIndexRebuildCountForTesting() {
        return 0;
    }


    @Override
    public Path pageVolumeStateFileForTesting() {
        return readLocked(pageVolumeStateStore::pageFile);
    }

    @Override
    public Path rowDirectoryStateFileForTesting() {
        return readLocked(pageVolumeStateStore::rowDirectoryFile);
    }

    @Override
    public Path reusablePageIndexFileForTesting() {
        return readLocked(pageVolumeStateStore::reusablePageIndexFile);
    }

    @Override
    public Path pageMutationLogFileForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationLogFile);
    }

    @Override
    public Path writeAheadLogFileForTesting() {
        return readLocked(pageVolumeStateStore::writeAheadLogFile);
    }

    @Override
    public Path checkpointFileForTesting() {
        return readLocked(pageVolumeStateStore::checkpointFile);
    }

    @Override
    public String checkpointStatusForTesting() {
        return readLocked(pageVolumeStateStore::checkpointStatus);
    }

    @Override
    public int physicalVersionCountForTesting() {
        return readLocked(pageVolumeStateStore::physicalVersionCount);
    }

    @Override
    public int logicalRowCountForTesting() {
        return readLocked(pageVolumeStateStore::logicalRowCount);
    }

    @Override
    public List<String> pageBackedVisibleRowSummariesForTesting() {
        return readLocked(() -> pageVolumeStateStore.loadVisibleRows().stream()
                .map(row -> row.rowId() + "|" + String.join("|", valueKeys(row.values())))
                .sorted()
                .toList());
    }

    @Override
    public long pageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCount);
    }

    @Override
    public long overflowPageCountForTesting() {
        return readLocked(pageVolumeStateStore::overflowPageCount);
    }

    @Override
    public long reusablePageCountForTesting() {
        return readLocked(pageVolumeStateStore::reusablePageCount);
    }

    @Override
    public long pageCacheMaxPageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheMaxPageCount);
    }

    @Override
    public long pageCacheSizeForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheSize);
    }

    @Override
    public long pageCacheHitCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheHitCount);
    }

    @Override
    public long pageCacheMissCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheMissCount);
    }

    @Override
    public long pageCacheWriteCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheWriteCount);
    }

    @Override
    public long pageCacheEvictionCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheEvictionCount);
    }

    @Override
    public long pageCacheInvalidationCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheInvalidationCount);
    }

    @Override
    public int consistencyErrorCountForTesting() {
        return readLocked(pageVolumeStateStore::consistencyErrorCount);
    }

    @Override
    public String consistencySummaryForTesting() {
        return readLocked(pageVolumeStateStore::consistencySummary);
    }

    @Override
    public void assertConsistentForTesting() {
        readLocked(pageVolumeStateStore::assertConsistent);
    }

    @Override
    public DelosVacuumOutcome vacuumSafely() {
        return writeLocked(() -> {
            boolean hasRetainedInheritedSnapshot = transactions.activeTransactionCount() > 0
                    || transactions.retainedSnapshotCount() > 0;
            lastVacuumOutcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(hasRetainedInheritedSnapshot));
            return lastVacuumOutcome;
        });
    }

    @Override
    public DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return readLocked(() -> lastVacuumOutcome);
    }

    @Override
    public Path legacySnapshotFileForTesting() {
        return readLocked(() -> retiredSnapshotFile);
    }

    @Override
    public void close() {
        writeLocked(pageVolumeStateStore::close);
    }




    private void requireNoOtherActiveProviderWriter(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            String operation) {
        for (MvccInheritedHandles.Transaction activeTransaction : activeTransactions) {
            if (activeTransaction != handle && activeTransaction.hasWriteIntentForRow(rowId)) {
                throw new MvccWriteConflictException("provider write conflict: row "
                        + rowId + " has another active writer during " + operation);
            }
        }
    }

    private void requireProviderVisibleRowForWrite(
            long rowId,
            DelosStorageSnapshot snapshot,
            String operation) {
        Optional<MvccInheritedHandles.Transaction.WriteIntent> writeIntent = latestWriteIntent(rowId, snapshot);
        if (writeIntent.isPresent()) {
            if (!writeIntent.get().delete()) {
                return;
            }
            throw missingProviderVisibleRowForWrite(rowId, operation);
        }
        if (canReadCommittedImageUnlocked(snapshot)) {
            if (pageVolumeStateStore.loadVisibleRow(rowId).isPresent()) {
                transactionLocalPageBackedBaseReadCount++;
                return;
            }
            throw missingProviderVisibleRowForWrite(rowId, operation);
        }
        pageBackedHistoricalSnapshotReadCount++;
        if (pageVolumeStateStore.loadVisibleRow(rowId, nativeSnapshot(snapshot).visibleThrough()).isPresent()) {
            return;
        }
        throw missingProviderVisibleRowForWrite(rowId, operation);
    }

    private static MvccWriteConflictException missingProviderVisibleRowForWrite(long rowId, String operation) {
        return new MvccWriteConflictException("provider write conflict: logical row is not visible for "
                + operation + ": " + rowId);
    }

    private Optional<List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>>> writeIntentOverlayRows(
            DelosStorageSnapshot snapshot) {
        MvccInheritedHandles.Snapshot handleSnapshot = nativeSnapshotHandle(snapshot);
        MvccInheritedHandles.Transaction handle = handleSnapshot.transaction();
        if (!handle.hasWriteIntents()) {
            return Optional.empty();
        }
        java.util.LinkedHashMap<Long, PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows =
                new java.util.LinkedHashMap<>();
        List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> baseRows;
        if (canReadCommittedImageUnlocked(snapshot)) {
            baseRows = pageVolumeStateStore.loadVisibleRows();
        } else {
            pageBackedHistoricalSnapshotScanCount++;
            baseRows = pageVolumeStateStore.loadVisibleRows(handleSnapshot.nativeSnapshot().visibleThrough());
            pageBackedHistoricalSnapshotReadCount += baseRows.size();
        }
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : baseRows) {
            rows.put(row.rowId(), new PageVolumeMvccStateStore.PersistedRow<>(
                    row.rowId(), cloneRowUnchecked(row.values())));
        }
        for (MvccInheritedHandles.Transaction.WriteIntent intent : handle.writeIntents()) {
            if (!intent.commandSequence().isAtOrBefore(handleSnapshot.nativeSnapshot().visibleThroughCommand())) {
                continue;
            }
            if (intent.delete()) {
                rows.remove(intent.rowId());
            } else {
                rows.put(intent.rowId(), new PageVolumeMvccStateStore.PersistedRow<>(
                        intent.rowId(), cloneRowUnchecked(intent.row())));
            }
        }
        return Optional.of(List.copyOf(rows.values()));
    }

    private Optional<StoreDataValue[]> readWriteIntent(long rowId, DelosStorageSnapshot snapshot) {
        return latestWriteIntent(rowId, snapshot)
                .filter(intent -> !intent.delete())
                .map(intent -> cloneRowUnchecked(intent.row()));
    }

    private boolean writeIntentDeletesRow(long rowId, DelosStorageSnapshot snapshot) {
        return latestWriteIntent(rowId, snapshot)
                .map(MvccInheritedHandles.Transaction.WriteIntent::delete)
                .orElse(false);
    }

    private Optional<MvccInheritedHandles.Transaction.WriteIntent> latestWriteIntent(
            long rowId,
            DelosStorageSnapshot snapshot) {
        MvccInheritedHandles.Snapshot handleSnapshot = nativeSnapshotHandle(snapshot);
        return handleSnapshot.transaction().latestVisibleWriteIntent(
                rowId,
                handleSnapshot.nativeSnapshot().visibleThroughCommand());
    }

    private boolean canReadCommittedImageUnlocked(DelosStorageSnapshot snapshot) {
        MvccSnapshot nativeSnapshot = nativeSnapshot(snapshot);
        return nativeSnapshot.visibleThrough().equals(transactions.newestCommitSequence());
    }

    private <T> T readLocked(Supplier<T> operation) {
        readLock.lock();
        try {
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    private void readLocked(Runnable operation) {
        readLock.lock();
        try {
            operation.run();
        } finally {
            readLock.unlock();
        }
    }

    private <T> T writeLocked(Supplier<T> operation) {
        writeLock.lock();
        try {
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void writeLocked(Runnable operation) {
        writeLock.lock();
        try {
            operation.run();
        } finally {
            writeLock.unlock();
        }
    }

    private void loadCommittedState() {
        if (pageVolumeStateStore.hasDurableState()) {
            hydrateCommittedRows(
                    pageVolumeStateStore.loadVisibleRows(),
                    pageVolumeStateStore.nextInheritedRowId());
        }
    }

    private void hydrateCommittedRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows,
            long storedNextRowId) {
        rebuildCandidateIndexFromPageBackedRows(rows);
        if (rows.isEmpty()) {
            nextRowId = Math.max(nextRowId, storedNextRowId);
            return;
        }
        long maxRowId = 0L;
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            maxRowId = Math.max(maxRowId, row.rowId());
        }
        nextRowId = Math.max(storedNextRowId, maxRowId + 1L);
    }

    private void rebuildCandidateIndexFromPageBackedCommittedRows() {
        rebuildCandidateIndexFromPageBackedRows(pageVolumeStateStore.loadVisibleRows());
    }

    private void rebuildCandidateIndexFromPageBackedRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        candidateIndex.rebuildFromVisibleRows(toCandidateRows(rows));
        pageBackedCandidateIndexRebuildCount++;
    }

    private List<MvccInheritedHandles.Transaction.WriteIntent> activeAppendedWriteIntents() {
        List<MvccInheritedHandles.Transaction.WriteIntent> intents = new ArrayList<>();
        for (MvccInheritedHandles.Transaction transaction : activeTransactions) {
            intents.addAll(transaction.appendedWriteIntents());
        }
        return List.copyOf(intents);
    }

    private List<MvccInheritedHandles.Transaction.WriteIntent> activeSurvivingWriteIntents() {
        List<MvccInheritedHandles.Transaction.WriteIntent> intents = new ArrayList<>();
        for (MvccInheritedHandles.Transaction transaction : activeTransactions) {
            intents.addAll(transaction.writeIntents());
        }
        return List.copyOf(intents);
    }

    private List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changedRows(
            MvccInheritedHandles.Transaction handle) {
        List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes = new ArrayList<>();
        for (MvccInheritedHandles.Transaction.WriteIntent intent : handle.writeIntents()) {
            if (intent.delete()) {
                changes.add(PageVolumeMvccStateStore.PersistedChange.delete(intent.rowId()));
            } else {
                changes.add(PageVolumeMvccStateStore.PersistedChange.upsert(
                        intent.rowId(),
                        cloneRowUnchecked(intent.row())));
            }
        }
        return List.copyOf(changes);
    }

    private static List<String> committedChangePayloadSummaries(
            List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes) {
        List<String> summaries = new ArrayList<>(changes.size());
        for (PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]> change : changes) {
            if (change.delete()) {
                summaries.add(change.rowId() + "|DELETE");
            } else {
                summaries.add(change.rowId() + "|UPSERT|" + String.join("|", valueKeys(change.values())));
            }
        }
        return List.copyOf(summaries);
    }

    private static List<String> writeIntentPayloadSummaries(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents) {
        List<String> summaries = new ArrayList<>(intents.size());
        for (MvccInheritedHandles.Transaction.WriteIntent intent : intents) {
            if (intent.delete()) {
                summaries.add(intent.rowId() + "|DELETE");
            } else {
                summaries.add(intent.rowId() + "|UPSERT|" + String.join("|", valueKeys(intent.row())));
            }
        }
        return List.copyOf(summaries);
    }

    private void abortIfActive(MvccTransaction transaction, RuntimeException failure) {
        try {
            transactions.abort(transaction);
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    private static MvccInheritedHandles.Transaction nativeTransactionHandle(DelosStorageTransaction transaction) {
        return MvccInheritedHandles.transaction(transaction);
    }

    private static MvccTransaction nativeTransaction(DelosStorageTransaction transaction) {
        return nativeTransactionHandle(transaction).nativeTransaction();
    }

    private static MvccInheritedHandles.Snapshot nativeSnapshotHandle(DelosStorageSnapshot snapshot) {
        return MvccInheritedHandles.snapshot(snapshot);
    }

    private static MvccSnapshot nativeSnapshot(DelosStorageSnapshot snapshot) {
        return nativeSnapshotHandle(snapshot).nativeSnapshot();
    }

    private static List<MvccCandidateIndex.CandidateRow> toCandidateRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<MvccCandidateIndex.CandidateRow> candidates = new ArrayList<>(rows.size());
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            candidates.add(new MvccCandidateIndex.CandidateRow(row.rowId(), valueKeys(row.values())));
        }
        return List.copyOf(candidates);
    }

    private static List<String> valueKeys(StoreDataValue[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(values.length);
        for (StoreDataValue value : values) {
            keys.add(value == null ? null : valueKey(value));
        }
        return List.copyOf(keys);
    }

    private static String valueKey(StoreDataValue value) {
        if (value instanceof StoreValueOperations operations) {
            try {
                return valueKeyString(operations.getString());
            } catch (StandardException e) {
                throw new IllegalStateException("Cannot derive store value key from "
                        + value.getClass().getName(), e);
            }
        }
        Optional<Method> getString = publicNoArgMethod(value.getClass(), "getString");
        if (getString.isEmpty()) {
            return value.toString();
        }
        try {
            Object result = getString.get().invoke(value);
            return valueKeyString(result);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access store value key operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return value.toString();
        }
    }

    private static String valueKeyString(Object value) {
        return value == null ? "<null>" : value.toString();
    }

    private static Optional<Method> publicNoArgMethod(Class<?> type, String name) {
        if (type == null) {
            return Optional.empty();
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            Optional<Method> method = publicNoArgMethod(interfaceType, name);
            if (method.isPresent()) {
                return method;
            }
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                Method method = type.getMethod(name);
                if (method.getParameterCount() == 0
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                    return Optional.of(method);
                }
            } catch (NoSuchMethodException e) {
                // Keep searching public super types below.
            }
        }
        return publicNoArgMethod(type.getSuperclass(), name);
    }

    private static StoreDataValue[] cloneRowUnchecked(StoreDataValue[] row) {
        try {
            return cloneRow(row);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not clone inherited MVCC row", e);
        }
    }

    private static StoreDataValue[] cloneRow(StoreDataValue[] row) throws StandardException {
        if (row == null) {
            return new StoreDataValue[0];
        }
        StoreDataValue[] copy = new StoreDataValue[row.length];
        for (int i = 0; i < row.length; i++) {
            copy[i] = cloneValue(row[i]);
        }
        return copy;
    }

    private static StoreDataValue cloneValue(StoreDataValue value) throws StandardException {
        if (value == null) {
            return null;
        }
        if (value instanceof StoreValueOperations operations) {
            return operations.cloneValue(false);
        }
        StoreDataValue reflected = cloneSqlValueReflectively(value);
        if (reflected != null) {
            return reflected;
        }
        throw new IllegalArgumentException("MVCC storage provider requires cloneable StoreDataValue: "
                + value.getClass().getName());
    }

    private static StoreDataValue cloneSqlValueReflectively(StoreDataValue value) throws StandardException {
        try {
            Method cloneValue = value.getClass().getMethod("cloneValue", boolean.class);
            Object cloned = cloneValue.invoke(value, false);
            if (cloned instanceof StoreDataValue storeDataValue) {
                return storeDataValue;
            }
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access SQL value clone operation on "
                    + value.getClass().getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StandardException standardException) {
                throw standardException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    private static DelosVacuumOutcome vacuumOutcome(PageVolumeMvccStateStore.VacuumOutcome outcome) {
        return new DelosVacuumOutcome(
                outcome.skipped(),
                outcome.reason(),
                outcome.removedVersions(),
                outcome.remainingVersions());
    }

    private static String storageId(long segmentId, long containerId) {
        return PageVolumeMvccPaths.conglomerateStorageId(segmentId, containerId);
    }

    private static Path retiredSnapshotFile(Path databaseDirectory, long segmentId, long containerId) {
        Path directory = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + segmentId + "-" + containerId + ".snapshot");
    }

    private static Path transactionStatusFile(Path databaseDirectory, long segmentId, long containerId) {
        Path directory = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + segmentId + "-" + containerId + ".txstatus");
    }
}
