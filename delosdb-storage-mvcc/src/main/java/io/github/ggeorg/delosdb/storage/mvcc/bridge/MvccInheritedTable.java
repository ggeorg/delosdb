package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommandSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageCommittedRead;
import org.apache.derby.iapi.store.types.DelosStorageCoordinatedCommitTable;
import org.apache.derby.iapi.store.types.DelosStorageMaintenance;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageRowHead;
import org.apache.derby.iapi.store.types.DelosStorageRowLocator;
import org.apache.derby.iapi.store.types.DelosStorageSavepointParticipant;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

final class MvccInheritedTable implements DelosStorageTable,
        DelosStorageMaintenance,
        DelosStorageRowLocator,
        DelosStorageCandidateIndex,
        DelosStorageCommittedRead,
        DelosStorageCoordinatedCommitTable,
        DelosStorageSavepointParticipant,
        DelosStorageTableDiagnostics {
    private final long segmentId;
    private final long containerId;
    private final Path retiredSnapshotFile;
    private final Path transactionStatusFile;
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccInheritedIndexMaintenance indexMaintenance;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final MvccTransactionManager transactions;
    private final DelosStorageBackupCoordinator backupCoordinator;
    private final MvccDatabaseCommitCoordinator databaseCommitCoordinator;
    private final MvccFailurePointRegistry failurePoints;
    private final MvccInheritedTableAccess tableAccess;
    private final MvccInheritedMaintenanceLifecycle maintenanceLifecycle;
    private final MvccInheritedCommitLifecycle commitLifecycle;
    private final Consumer<MvccInheritedTable> closeCallback;
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<RecoveryRequired> recoveryRequired = new AtomicReference<>();
    private final List<MvccInheritedHandles.Transaction> activeTransactions = new ArrayList<>();
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    private final Lock closeLock = new java.util.concurrent.locks.ReentrantLock();
    private final Lock readLock = tableLock.readLock();
    private final Lock writeLock = tableLock.writeLock();
    private long nextRowId = 1L;
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

    MvccInheritedTable(long segmentId, long containerId, Path databaseDirectory) {
        this(segmentId, containerId, databaseDirectory, MvccCommitCoordinator.Mode.GROUP);
    }

    private static DelosStorageBackupCoordinator isolatedBackupCoordinator(
            long segmentId,
            long containerId,
            Path databaseDirectory) {
        String description = databaseDirectory == null
                ? storageId(segmentId, containerId)
                : databaseDirectory.toAbsolutePath().normalize() + ":" + storageId(segmentId, containerId);
        return DelosStorageBackupCoordinator.isolatedDatabase(description).coordinator();
    }

    MvccInheritedTable(
            long segmentId,
            long containerId,
            Path databaseDirectory,
            MvccDatabaseMaintenanceService maintenanceService,
            DelosStorageBackupCoordinator backupCoordinator,
            MvccDatabaseCommitCoordinator databaseCommitCoordinator,
            MvccFailurePointRegistry failurePoints,
            Consumer<MvccInheritedTable> closeCallback) {
        this(
                segmentId,
                containerId,
                databaseDirectory,
                MvccCommitCoordinator.Mode.GROUP,
                MvccCommitCoordinator.DEFAULT_CAPACITY,
                MvccCommitCoordinator.DEFAULT_MAX_GROUP_SIZE,
                MvccCommitCoordinator.DEFAULT_MAX_GROUP_DELAY_NANOS,
                SharedStatusForceHook.NOOP,
                maintenanceService,
                backupCoordinator,
                databaseCommitCoordinator,
                failurePoints,
                false,
                closeCallback);
    }

    MvccInheritedTable(
            long segmentId,
            long containerId,
            Path databaseDirectory,
            MvccCommitCoordinator.Mode coordinatorMode) {
        this(
                segmentId,
                containerId,
                databaseDirectory,
                coordinatorMode,
                MvccCommitCoordinator.DEFAULT_CAPACITY,
                MvccCommitCoordinator.DEFAULT_MAX_GROUP_SIZE,
                MvccCommitCoordinator.DEFAULT_MAX_GROUP_DELAY_NANOS,
                SharedStatusForceHook.NOOP);
    }

    MvccInheritedTable(
            long segmentId,
            long containerId,
            Path databaseDirectory,
            MvccCommitCoordinator.Mode coordinatorMode,
            int coordinatorCapacity,
            int maxGroupSize,
            long maxGroupDelayNanos,
            SharedStatusForceHook sharedStatusForceHook) {
        this(
                segmentId,
                containerId,
                databaseDirectory,
                coordinatorMode,
                coordinatorCapacity,
                maxGroupSize,
                maxGroupDelayNanos,
                sharedStatusForceHook,
                new MvccDatabaseMaintenanceService(databaseDirectory),
                isolatedBackupCoordinator(segmentId, containerId, databaseDirectory),
                new MvccDatabaseCommitCoordinator(databaseDirectory),
                MvccFailurePointRegistry.disabled(databaseDirectory),
                true,
                ignored -> { });
    }

    private MvccInheritedTable(
            long segmentId,
            long containerId,
            Path databaseDirectory,
            MvccCommitCoordinator.Mode coordinatorMode,
            int coordinatorCapacity,
            int maxGroupSize,
            long maxGroupDelayNanos,
            SharedStatusForceHook sharedStatusForceHook,
            MvccDatabaseMaintenanceService maintenanceService,
            DelosStorageBackupCoordinator backupCoordinator,
            MvccDatabaseCommitCoordinator databaseCommitCoordinator,
            MvccFailurePointRegistry failurePoints,
            boolean ownsMaintenanceService,
            Consumer<MvccInheritedTable> closeCallback) {
        this.segmentId = segmentId;
        this.containerId = containerId;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, segmentId, containerId);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, segmentId, containerId);
        this.databaseCommitCoordinator = Objects.requireNonNull(
                databaseCommitCoordinator, "databaseCommitCoordinator");
        this.failurePoints = Objects.requireNonNull(failurePoints, "failurePoints");
        this.pageVolumeStateStore = PageVolumeMvccStateStore.open(
                databaseDirectory,
                storageId(segmentId, containerId),
                MvccInheritedRowCodec.INSTANCE,
                this.databaseCommitCoordinator.recoveredStatuses());
        this.indexMaintenance = new MvccInheritedIndexMaintenance(pageVolumeStateStore);
        this.transactionStatusStore = transactionStatusFile == null || containerId == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        this.transactions.observeExternalCommitSequence(
                this.databaseCommitCoordinator.newestRecoveredCommitSequence());
        this.backupCoordinator = Objects.requireNonNull(backupCoordinator, "backupCoordinator");
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
        this.tableAccess = new MvccInheritedTableAccess(
                this.backupCoordinator,
                readLock,
                writeLock,
                this::requireOperational);
        loadCommittedState();
        String storageId = storageId(segmentId, containerId);
        this.maintenanceLifecycle = new MvccInheritedMaintenanceLifecycle(
                segmentId,
                containerId,
                pageVolumeStateStore,
                transactions,
                maintenanceService,
                ownsMaintenanceService,
                closeStarted,
                closed,
                recoveryRequired,
                tableAccess);
        this.commitLifecycle = new MvccInheritedCommitLifecycle(
                this,
                storageId,
                pageVolumeStateStore,
                indexMaintenance,
                transactions,
                this.backupCoordinator,
                this.databaseCommitCoordinator,
                this.failurePoints,
                recoveryRequired,
                activeTransactions,
                tableAccess,
                writeLock,
                maintenanceLifecycle,
                coordinatorMode,
                coordinatorCapacity,
                maxGroupSize,
                maxGroupDelayNanos,
                sharedStatusForceHook);
    }

    @Override
    public DelosStorageTransaction beginTransaction() {
        return durableMutationLocked(DelosStorageBackupCoordinator.Mutation.TRANSACTION_BEGIN, () -> {
            MvccCommitDurabilityMetrics.Scope durabilityScope =
                    MvccCommitDurabilityMetrics.begin(MvccCommitJfr.enabled());
            try {
                MvccTransaction nativeTransaction = transactions.begin();
                MvccInheritedHandles.Transaction transaction = new MvccInheritedHandles.Transaction(
                        nativeTransaction,
                        false,
                        durabilityScope.finish());
                activeTransactions.add(transaction);
                return transaction;
            } finally {
                durabilityScope.finish();
            }
        });
    }

    @Override
    public DelosStorageTransaction beginReadOnlyTransaction() {
        return writeLocked(() -> {
            MvccInheritedHandles.Transaction transaction =
                    new MvccInheritedHandles.Transaction(transactions.beginReadOnly(), true);
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
    public DelosStorageCommitCoordinator commitCoordinator() {
        return databaseCommitCoordinator;
    }

    @Override
    public void commit(DelosStorageTransaction transaction) {
        commitLifecycle.commit(transaction);
    }

    String databaseCommitIdentity() {
        return storageId(segmentId, containerId);
    }

    DelosStorageBackupCoordinator databaseBackupCoordinator() {
        return backupCoordinator;
    }

    void lockForDatabaseCommit() {
        requireOperational();
        writeLock.lock();
        try {
            requireOperational();
        } catch (RuntimeException | Error failure) {
            writeLock.unlock();
            throw failure;
        }
    }

    void unlockForDatabaseCommit() {
        writeLock.unlock();
    }

    long newestCommitSequenceForDatabaseCommit() {
        return transactions.newestCommitSequence().value();
    }

    DatabasePreparedCommit prepareDatabaseCommit(
            DelosStorageTransaction transaction,
            MvccTransactionId databaseTransactionId,
            MvccCommitSequence commitSequence,
            int participantIndex,
            int participantCount) {
        return commitLifecycle.prepareDatabaseCommit(
                transaction,
                databaseTransactionId,
                commitSequence,
                participantIndex,
                participantCount);
    }

    void publishDatabaseCommit(DatabasePreparedCommit databaseCommit) {
        commitLifecycle.publishDatabaseCommit(databaseCommit);
    }

    void abortDatabaseCommit(DatabasePreparedCommit databaseCommit) {
        commitLifecycle.abortDatabaseCommit(databaseCommit);
    }

    void abortDatabaseTransaction(DelosStorageTransaction transaction) {
        commitLifecycle.abortDatabaseTransaction(transaction);
    }

    void commitSingleParticipant(DelosStorageTransaction transaction) {
        commitLifecycle.commitSingleParticipant(transaction);
    }

    @Override
    public void abort(DelosStorageTransaction transaction) {
        commitLifecycle.abort(transaction);
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

    @Override
    public void dropDurableState() {
        durableMutationLocked(DelosStorageBackupCoordinator.Mutation.DROP_DURABLE_STATE, () -> {
            indexMaintenance.clear();
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
        databaseCommitCoordinator.tableDurableStateDropped();
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
        return readLocked(() -> indexMaintenance.candidateRowIdsFor(column, value));
    }

    @Override
    public Optional<List<Long>> orderedIndexRowIdsFor(
            DelosStorageSnapshot snapshot,
            int column,
            String value) {
        return writeLocked(() -> {
            if (!canReadCommittedImageUnlocked(snapshot)) {
                return Optional.empty();
            }
            return indexMaintenance.orderedIndexRowIdsFor(column, value);
        });
    }

    @Override
    public Optional<List<Long>> orderedIndexRowIdsInRangeFor(
            DelosStorageSnapshot snapshot,
            int column,
            String lowerValue,
            boolean lowerInclusive,
            String upperValue,
            boolean upperInclusive) {
        return writeLocked(() -> {
            if (!canReadCommittedImageUnlocked(snapshot)) {
                return Optional.empty();
            }
            return indexMaintenance.orderedIndexRowIdsInRangeFor(
                    column, lowerValue, lowerInclusive, upperValue, upperInclusive);
        });
    }

    @Override
    public void recordOrderedIndexFallbackForTesting(DelosStorageOrderedIndexFallbackReason reason) {
        writeLocked(() -> indexMaintenance.recordOrderedIndexFallbackForTesting(reason));
    }

    @Override
    public int candidateIndexKeyCountForTesting() {
        return readLocked(indexMaintenance::candidateIndexKeyCountForTesting);
    }

    @Override
    public int lastCommittedChangedRowCountForTesting() {
        return readLocked(commitLifecycle::lastCommittedChangedRowCount);
    }

    @Override
    public int lastCommittedWriteIntentCountForTesting() {
        return readLocked(commitLifecycle::lastCommittedWriteIntentCount);
    }

    @Override
    public List<String> lastCommittedWriteIntentPayloadSummariesForTesting() {
        return readLocked(commitLifecycle::lastCommittedWriteIntentPayloadSummaries);
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
        return readLocked(indexMaintenance::pageBackedCandidateIndexRebuildCountForTesting);
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
    public Path freeSpaceMapFileForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapFile);
    }

    @Override
    public Path visibilityMapFileForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapFile);
    }

    @Override
    public Path purgeQueueFileForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueueFile);
    }

    @Override
    public Path orderedIndexPagesFileForTesting() {
        return readLocked(pageVolumeStateStore::orderedIndexPagesFile);
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
    public Path subsystemRecoveryRecordsFileForTesting() {
        return readLocked(pageVolumeStateStore::subsystemRecoveryRecordsFile);
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
        return diagnosticReadLocked(pageVolumeStateStore::logicalRowCount);
    }

    @Override
    public List<String> pageBackedVisibleRowSummariesForTesting() {
        return readLocked(() -> pageVolumeStateStore.loadVisibleRows().stream()
                .map(row -> row.rowId() + "|" + String.join("|", MvccInheritedIndexMaintenance.valueKeysRaw(row.values())))
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
    public long freeSpaceMapPageCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapPageCount);
    }

    @Override
    public int freeSpaceMapMaxFreeBytesForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapMaxFreeBytes);
    }

    @Override
    public long freeSpaceMapLookupCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapLookupCount);
    }

    @Override
    public long freeSpaceMapHitCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapHitCount);
    }

    @Override
    public long freeSpaceMapNonLastHitCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapNonLastHitCount);
    }

    @Override
    public long freeSpaceMapMissCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapMissCount);
    }

    @Override
    public long freeSpaceMapStaleEntryCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapStaleEntryCount);
    }

    @Override
    public long freeSpaceMapUpdateCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapUpdateCount);
    }

    @Override
    public long freeSpaceMapRebuildCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapRebuildCount);
    }

    @Override
    public List<String> freeSpaceMapPageSummariesForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapPageSummaries);
    }

    @Override
    public long visibilityMapPageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapPageCount);
    }

    @Override
    public long visibilityMapOldVersionPageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapOldVersionPageCount);
    }

    @Override
    public long visibilityMapPrunablePageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapPrunablePageCount);
    }

    @Override
    public long visibilityMapTombstonePageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapTombstonePageCount);
    }

    @Override
    public long visibilityMapAllVisiblePageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapAllVisiblePageCount);
    }

    @Override
    public long visibilityMapOverflowPageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapOverflowPageCount);
    }

    @Override
    public long visibilityMapNeedsCheckerPageCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapNeedsCheckerPageCount);
    }

    @Override
    public long visibilityMapUpdateCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapUpdateCount);
    }

    @Override
    public long visibilityMapRebuildCountForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapRebuildCount);
    }

    @Override
    public List<String> visibilityMapPageSummariesForTesting() {
        return readLocked(pageVolumeStateStore::visibilityMapPageSummaries);
    }

    @Override
    public long pageLocalPruneAttemptCountForTesting() {
        return readLocked(pageVolumeStateStore::pageLocalPruneAttemptCount);
    }

    @Override
    public long pageLocalPruneSuccessCountForTesting() {
        return readLocked(pageVolumeStateStore::pageLocalPruneSuccessCount);
    }

    @Override
    public long pageLocalPruneFallbackCountForTesting() {
        return readLocked(pageVolumeStateStore::pageLocalPruneFallbackCount);
    }

    @Override
    public long pageLocalPruneRemovedVersionCountForTesting() {
        return readLocked(pageVolumeStateStore::pageLocalPruneRemovedVersionCount);
    }

    @Override
    public long pageMutationContextBeginCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextBeginCount);
    }

    @Override
    public long pageMutationContextCommitCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextCommitCount);
    }

    @Override
    public long pageMutationContextAbortCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextAbortCount);
    }

    @Override
    public long pageMutationContextPageReservationCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextPageReservationCount);
    }

    @Override
    public long pageMutationContextReservedBytesForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextReservedBytes);
    }

    @Override
    public long pageMutationContextPageWriteCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextPageWriteCount);
    }

    @Override
    public long pageMutationContextFreeSpaceMapUpdateCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextFreeSpaceMapUpdateCount);
    }

    @Override
    public long pageMutationContextReusableIndexUpdateCountForTesting() {
        return readLocked(pageVolumeStateStore::pageMutationContextReusableIndexUpdateCount);
    }

    @Override
    public String lastPageMutationContextOperationForTesting() {
        return readLocked(pageVolumeStateStore::lastPageMutationContextOperation);
    }

    @Override
    public long purgeQueuePendingCountForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueuePendingCount);
    }

    @Override
    public long purgeQueueEnqueueCountForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueueEnqueueCount);
    }

    @Override
    public long purgeQueueDrainCountForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueueDrainCount);
    }

    @Override
    public long purgeQueueLastDrainCountForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueueLastDrainCount);
    }

    @Override
    public List<String> purgeQueueEntrySummariesForTesting() {
        return readLocked(pageVolumeStateStore::purgeQueueEntrySummaries);
    }

    @Override
    public long purgeDaemonScheduleCountForTesting() {
        return maintenanceLifecycle.purgeDaemonScheduleCount();
    }

    @Override
    public long purgeDaemonRunCountForTesting() {
        return maintenanceLifecycle.purgeDaemonRunCount();
    }

    @Override
    public long purgeDaemonSkipCountForTesting() {
        return maintenanceLifecycle.purgeDaemonSkipCount();
    }

    @Override
    public long purgeDaemonLastTriggerChangedRowsForTesting() {
        return maintenanceLifecycle.purgeDaemonLastTriggerChangedRows();
    }

    @Override
    public String purgeDaemonLastDecisionForTesting() {
        return maintenanceLifecycle.purgeDaemonLastDecision();
    }

    @Override
    public long purgeDaemonLastVisibilityDebtScoreForTesting() {
        return maintenanceLifecycle.purgeDaemonLastVisibilityDebtScore();
    }

    @Override
    public String purgeDaemonLastVisibilityDebtSummaryForTesting() {
        return maintenanceLifecycle.purgeDaemonLastVisibilityDebtSummary();
    }

    @Override
    public int databaseMaintenanceWorkerCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().workerCount();
    }

    @Override
    public int databaseMaintenanceRegisteredTableCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().registeredTableCount();
    }

    @Override
    public int databaseMaintenanceQueuedTaskCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().queuedTaskCount();
    }

    @Override
    public long databaseMaintenanceCommitWakeupCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().commitWakeupCount();
    }

    @Override
    public long databaseMaintenancePeriodicScanCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().periodicScanCount();
    }

    @Override
    public long databaseMaintenanceRunCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().runCount();
    }

    @Override
    public long databaseMaintenanceFailureCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().failureCount();
    }

    @Override
    public int databaseMaintenanceMaximumActiveWorkerCountForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().maximumActiveWorkerCount();
    }

    @Override
    public boolean databaseMaintenanceAcceptingForTesting() {
        return maintenanceLifecycle.maintenanceService().metrics().accepting();
    }

    @Override
    public long orderedIndexPageCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexPageCountForTesting);
    }

    @Override
    public long orderedIndexEntryCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexEntryCountForTesting);
    }

    @Override
    public int orderedIndexDistinctKeyCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexDistinctKeyCountForTesting);
    }

    @Override
    public long orderedIndexRebuildCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexRebuildCountForTesting);
    }

    @Override
    public List<String> orderedIndexEntrySummariesForTesting() {
        return readLocked(indexMaintenance::orderedIndexEntrySummariesForTesting);
    }

    @Override
    public long orderedIndexLookupCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexLookupCountForTesting);
    }

    @Override
    public long orderedIndexHitCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexHitCountForTesting);
    }

    @Override
    public long orderedIndexFallbackCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexFallbackCountForTesting);
    }

    @Override
    public long orderedIndexFallbackReasonCountForTesting(
            DelosStorageOrderedIndexFallbackReason reason) {
        return readLocked(() -> indexMaintenance.orderedIndexFallbackReasonCountForTesting(reason));
    }

    @Override
    public List<String> orderedIndexFallbackReasonSummariesForTesting() {
        return readLocked(indexMaintenance::orderedIndexFallbackReasonSummariesForTesting);
    }

    @Override
    public long orderedIndexRowIdCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexRowIdCountForTesting);
    }

    @Override
    public int orderedIndexCandidateParityErrorCountForTesting() {
        return readLocked(indexMaintenance::orderedIndexCandidateParityErrorCountForTesting);
    }

    @Override
    public List<String> orderedIndexCandidateParityErrorSummariesForTesting() {
        return readLocked(indexMaintenance::orderedIndexCandidateParityErrorSummariesForTesting);
    }

    @Override
    public DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting() {
        return readLocked(indexMaintenance::orderedIndexAuthorityModeForTesting);
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
    public long pageCachePinCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCachePinCount);
    }

    @Override
    public long pageCacheUnpinCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheUnpinCount);
    }

    @Override
    public long pageCachePinnedPageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCachePinnedPageCount);
    }

    @Override
    public long pageCacheDirtyPageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheDirtyPageCount);
    }

    @Override
    public long pageCacheFlushListPageCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheFlushListPageCount);
    }

    @Override
    public long pageCacheFlushCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheFlushCount);
    }

    @Override
    public long pageCachePinnedEvictionSkipCountForTesting() {
        return readLocked(pageVolumeStateStore::pageCachePinnedEvictionSkipCount);
    }

    @Override
    public long pageCacheLastPageGenerationForTesting() {
        return readLocked(pageVolumeStateStore::pageCacheLastPageGeneration);
    }

    @Override
    public long attributeOverflowWriteCountForTesting() {
        return readLocked(pageVolumeStateStore::attributeOverflowWriteCount);
    }

    @Override
    public long attributeOverflowReadCountForTesting() {
        return readLocked(pageVolumeStateStore::attributeOverflowReadCount);
    }

    @Override
    public long attributeOverflowInlineRowBytesForTesting() {
        return readLocked(pageVolumeStateStore::attributeOverflowInlineRowBytes);
    }

    @Override
    public long attributeOverflowValueBytesForTesting() {
        return readLocked(pageVolumeStateStore::attributeOverflowValueBytes);
    }

    @Override
    public long subsystemRecoveryRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::subsystemRecoveryRecordCount);
    }

    @Override
    public long subsystemRecoveryLastSequenceForTesting() {
        return readLocked(pageVolumeStateStore::subsystemRecoveryLastSequence);
    }

    @Override
    public long rowPageRedoRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::rowPageRedoRecordCount);
    }

    @Override
    public long indexPageRedoRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::indexPageRedoRecordCount);
    }

    @Override
    public long overflowPageRedoRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::overflowPageRedoRecordCount);
    }

    @Override
    public long freeSpaceMapRedoRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::freeSpaceMapRedoRecordCount);
    }

    @Override
    public long transactionOutcomeRedoRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::transactionOutcomeRedoRecordCount);
    }

    @Override
    public long checkpointRecoveryRecordCountForTesting() {
        return readLocked(pageVolumeStateStore::checkpointRecoveryRecordCount);
    }

    @Override
    public List<String> subsystemRecoveryRecordSummariesForTesting() {
        return readLocked(pageVolumeStateStore::subsystemRecoveryRecordSummaries);
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
        return maintenanceLifecycle.vacuumSafely();
    }

    @Override
    public DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return maintenanceLifecycle.lastVacuumOutcome();
    }

    @Override
    public Path legacySnapshotFileForTesting() {
        return readLocked(() -> retiredSnapshotFile);
    }

    @Override
    public void close() {
        closeLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            closeStarted.set(true);
            commitLifecycle.close();
            maintenanceLifecycle.close();
            Throwable failure = null;
            try {
                try (DelosStorageBackupCoordinator.Guard ignored =
                             backupCoordinator.enterDurableMutation(
                                     DelosStorageBackupCoordinator.Mutation.TABLE_CLOSE)) {
                    writeLock.lock();
                    try {
                        pageVolumeStateStore.close();
                    } finally {
                        writeLock.unlock();
                    }
                }
            } catch (RuntimeException | Error closeFailure) {
                failure = closeFailure;
            } finally {
                closed.set(true);
                try {
                    closeCallback.accept(this);
                } catch (RuntimeException | Error callbackFailure) {
                    if (failure == null) {
                        failure = callbackFailure;
                    } else {
                        failure.addSuppressed(callbackFailure);
                    }
                }
            }
            if (failure != null) {
                throwUnchecked(failure);
            }
        } finally {
            closeLock.unlock();
        }
    }

    MvccDatabaseMaintenanceService maintenanceServiceForTesting() {
        return maintenanceLifecycle.maintenanceService();
    }

    DelosStorageBackupCoordinator backupCoordinatorForTesting() {
        return backupCoordinator;
    }

    void setPagePublicationHookForTesting(PageVolumeMvccStateStore.PublicationHook hook) {
        pageVolumeStateStore.setPublicationHookForTesting(hook);
    }

    void setOrderedIndexPublicationHookForTesting(Runnable hook) {
        commitLifecycle.setOrderedIndexPublicationHook(hook);
    }

    void setPostCommitMaintenanceHookForTesting(Runnable hook) {
        maintenanceLifecycle.setPostCommitMaintenanceHook(hook);
    }

    private void requireNoOtherActiveProviderWriter(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            String operation) {
        requireNoOtherActiveProviderWriter(handle, rowId, operation, Set.of());
    }

    private void requireNoOtherActiveProviderWriter(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            String operation,
            Set<MvccInheritedHandles.Transaction> ignoredWriters) {
        MvccInheritedWriteConflictPolicy.requireNoOtherActiveProviderWriter(
                activeTransactions, handle, rowId, operation, ignoredWriters);
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

    int activeTransactionCountForTesting() {
        return transactions.activeTransactionCount();
    }
    int activeCommitRequestsForTesting() {
        return commitLifecycle.activeCommitRequests();
    }

    int durabilityEnrollmentCountForTesting() {
        return commitLifecycle.durabilityEnrollmentCount();
    }
    static String failureSummary(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void requireOperational() {
        if (closeStarted.get() || closed.get()) {
            throw new IllegalStateException("delos_mvcc table is closing or closed: "
                    + storageId(segmentId, containerId));
        }
        RecoveryRequired unhealthy = recoveryRequired.get();
        if (unhealthy != null) {
            throw new TableRecoveryRequiredException(storageId(segmentId, containerId), unhealthy);
        }
    }

    boolean recoveryRequiredForTesting() {
        return commitLifecycle.recoveryRequired();
    }

    String recoveryRequiredSummaryForTesting() {
        return commitLifecycle.recoveryRequiredSummary();
    }

    long postCommitMaintenanceFailureCountForTesting() {
        return maintenanceLifecycle.postCommitMaintenanceFailureCount();
    }

    String lastPostCommitMaintenanceFailureForTesting() {
        return maintenanceLifecycle.lastPostCommitMaintenanceFailure();
    }

    private <T> T diagnosticReadLocked(Supplier<T> operation) {
        readLock.lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("delos_mvcc table is closed: "
                        + storageId(segmentId, containerId));
            }
            RecoveryRequired unhealthy = recoveryRequired.get();
            if (unhealthy != null) {
                throw new TableRecoveryRequiredException(storageId(segmentId, containerId), unhealthy);
            }
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    private <T> T readLocked(Supplier<T> operation) {
        return tableAccess.read(operation);
    }

    private void readLocked(Runnable operation) {
        tableAccess.read(operation);
    }

    private <T> T writeLocked(Supplier<T> operation) {
        return tableAccess.write(operation);
    }

    private void writeLocked(Runnable operation) {
        tableAccess.write(operation);
    }

    private <T> T durableMutationLocked(
            DelosStorageBackupCoordinator.Mutation mutation,
            Supplier<T> operation) {
        return tableAccess.durable(mutation, operation);
    }

    private void durableMutationLocked(
            DelosStorageBackupCoordinator.Mutation mutation,
            Runnable operation) {
        tableAccess.durable(mutation, operation);
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
        indexMaintenance.rebuildFromRows(rows);
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

    static void throwUnchecked(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("MVCC commit group failed", failure);
    }

    @FunctionalInterface
    interface SharedStatusForceHook {
        SharedStatusForceHook NOOP = preparedCommits -> { };

        void beforeForce(List<MvccPreparedCommit> preparedCommits);

        default void afterForce(List<MvccPreparedCommit> preparedCommits) {
        }
    }

    record RecoveryRequired(String stage, String failureSummary) {
        RecoveryRequired {
            stage = Objects.requireNonNull(stage, "stage");
            failureSummary = Objects.requireNonNull(failureSummary, "failureSummary");
        }
    }

    static final class TableRecoveryRequiredException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        TableRecoveryRequiredException(String storageId, RecoveryRequired recoveryRequired) {
            super("MVCC table " + storageId + " requires close and reopen after "
                    + recoveryRequired.stage() + ": " + recoveryRequired.failureSummary());
        }
    }

    static final class TransactionStatusOutcomeUnknownException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final long transactionId;
        private final long proposedCommitSequence;

        TransactionStatusOutcomeUnknownException(
                String storageId,
                long transactionId,
                long proposedCommitSequence,
                RecoveryRequired recoveryRequired,
                Throwable cause) {
            super("MVCC transaction " + transactionId + " has an unknown durable outcome for "
                    + storageId + " after transaction-status publication failed; close and reopen "
                    + "the table before deciding whether the transaction may be retried", cause);
            this.transactionId = transactionId;
            this.proposedCommitSequence = proposedCommitSequence;
        }

        long transactionId() {
            return transactionId;
        }

        long proposedCommitSequence() {
            return proposedCommitSequence;
        }
    }

    static final class CommittedTransactionRecoveryRequiredException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final long transactionId;
        private final long commitSequence;

        CommittedTransactionRecoveryRequiredException(
                String storageId,
                long transactionId,
                long commitSequence,
                RecoveryRequired recoveryRequired,
                Throwable cause) {
            super("MVCC transaction " + transactionId + " committed at sequence " + commitSequence
                    + " for " + storageId + ", but the table requires close and reopen after "
                    + recoveryRequired.stage() + "; the transaction must not be retried", cause);
            this.transactionId = transactionId;
            this.commitSequence = commitSequence;
        }

        long transactionId() {
            return transactionId;
        }

        long commitSequence() {
            return commitSequence;
        }
    }

    record DatabasePreparedCommit(
            MvccInheritedTable table,
            MvccTransactionId databaseTransactionId,
            MvccPreparedCommit preparedCommit,
            MvccTransactionManager.PreparedCommit localStatus,
            PageVolumeMvccStateStore.StagedChanges stagedChanges) {
        DatabasePreparedCommit {
            table = Objects.requireNonNull(table, "table");
            databaseTransactionId = Objects.requireNonNull(
                    databaseTransactionId, "databaseTransactionId");
            preparedCommit = Objects.requireNonNull(preparedCommit, "preparedCommit");
            localStatus = Objects.requireNonNull(localStatus, "localStatus");
            stagedChanges = Objects.requireNonNull(stagedChanges, "stagedChanges");
        }
    }

    private static List<String> writeIntentPayloadSummaries(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents) {
        List<String> summaries = new ArrayList<>(intents.size());
        for (MvccInheritedHandles.Transaction.WriteIntent intent : intents) {
            if (intent.delete()) {
                summaries.add(intent.rowId() + "|DELETE");
            } else {
                summaries.add(intent.rowId() + "|UPSERT|" + String.join("|", MvccInheritedIndexMaintenance.valueKeysRaw(intent.row())));
            }
        }
        return List.copyOf(summaries);
    }

    static MvccInheritedHandles.Transaction nativeTransactionHandle(DelosStorageTransaction transaction) {
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

    static StoreDataValue[] cloneRowUnchecked(StoreDataValue[] row) {
        try {
            return StoreValueCopySupport.cloneRow(row);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not clone inherited MVCC row", e);
        }
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
        return PageVolumeMvccPaths.transactionStatusFile(databaseDirectory, storageId(segmentId, containerId));
    }
}
