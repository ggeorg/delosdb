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
    private final MvccPurgeDaemon purgeDaemon = new MvccPurgeDaemon();
    private final MvccDatabaseMaintenanceService maintenanceService;
    private final DelosStorageBackupCoordinator backupCoordinator;
    private final MvccDatabaseCommitCoordinator databaseCommitCoordinator;
    private final MvccDatabaseMaintenanceService.Registration maintenanceRegistration;
    private final boolean ownsMaintenanceService;
    private final Consumer<MvccInheritedTable> closeCallback;
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<RecoveryRequired> recoveryRequired = new AtomicReference<>();
    private final List<MvccInheritedHandles.Transaction> activeTransactions = new ArrayList<>();
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    private final Lock closeLock = new java.util.concurrent.locks.ReentrantLock();
    private final MvccCommitMetrics commitMetrics = new MvccCommitMetrics();
    private final MvccCommitCoordinator<MvccPreparedCommit, CommitPublication> durabilityCoordinator;
    private final SharedStatusForceHook sharedStatusForceHook;
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
    private DelosVacuumOutcome lastVacuumOutcome = DelosVacuumOutcome.disabled();
    private long postCommitMaintenanceFailureCount;
    private String lastPostCommitMaintenanceFailure = "";
    private volatile Runnable orderedIndexPublicationHook = () -> { };
    private volatile Runnable postCommitMaintenanceHook = () -> { };

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
            boolean ownsMaintenanceService,
            Consumer<MvccInheritedTable> closeCallback) {
        this.segmentId = segmentId;
        this.containerId = containerId;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, segmentId, containerId);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, segmentId, containerId);
        this.databaseCommitCoordinator = Objects.requireNonNull(
                databaseCommitCoordinator, "databaseCommitCoordinator");
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
        this.durabilityCoordinator = new MvccCommitCoordinator<>(
                coordinatorMode, coordinatorCapacity, maxGroupSize, maxGroupDelayNanos);
        this.sharedStatusForceHook = Objects.requireNonNull(sharedStatusForceHook, "sharedStatusForceHook");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.backupCoordinator = Objects.requireNonNull(backupCoordinator, "backupCoordinator");
        this.ownsMaintenanceService = ownsMaintenanceService;
        this.closeCallback = Objects.requireNonNull(closeCallback, "closeCallback");
        loadCommittedState();
        this.maintenanceRegistration = maintenanceService.register(new MaintenanceTarget());
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
        databaseCommitCoordinator.commit(List.of(
                new DelosStorageCommitCoordinator.Participant(this, transaction)));
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
            MvccCommitSequence commitSequence) {
        MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
        if (handle.readOnly()) {
            throw new IllegalStateException("read-only delos_mvcc transaction cannot commit");
        }
        MvccPreparedCommit preparedCommit = prepareCommit(handle);
        requirePreparedCommitCanPublish(preparedCommit);
        MvccTransactionManager.PreparedCommit localStatus = transactions.prepareCommitAt(
                preparedCommit.transaction(), commitSequence);
        PageVolumeMvccStateStore.StagedChanges stagedChanges =
                pageVolumeStateStore.stagePreparedChanges(
                        preparedCommit.preparedPageChanges(),
                        commitSequence,
                        databaseTransactionId.value());
        return new DatabasePreparedCommit(
                this, databaseTransactionId, preparedCommit, localStatus, stagedChanges);
    }

    void publishDatabaseCommit(DatabasePreparedCommit databaseCommit) {
        requireOwnedDatabaseCommit(databaseCommit);
        MvccPreparedCommit prepared = databaseCommit.preparedCommit();
        Throwable failure = null;
        try {
            transactions.publishPreparedCommit(databaseCommit.localStatus());
        } catch (RuntimeException | Error statusFailure) {
            failure = statusFailure;
            try {
                transactions.acknowledgeExternalCommitDecision(databaseCommit.localStatus());
            } catch (RuntimeException | Error acknowledgementFailure) {
                failure.addSuppressed(acknowledgementFailure);
            }
        }
        try {
            pageVolumeStateStore.publishStagedChanges(databaseCommit.stagedChanges());
        } catch (RuntimeException | Error publicationFailure) {
            if (failure == null) {
                failure = publicationFailure;
            } else {
                failure.addSuppressed(publicationFailure);
            }
        }

        if (failure == null && !databaseCommit.stagedChanges().empty()) {
            try {
                orderedIndexPublicationHook.run();
                indexMaintenance.rebuildFromCommittedRows();
            } catch (RuntimeException | Error indexFailure) {
                failure = indexFailure;
            }
        }
        if (failure == null && !databaseCommit.stagedChanges().empty()) {
            try {
                postCommitMaintenanceHook.run();
                runPurgeDaemonAfterCommit(prepared.changedRowCount());
            } catch (RuntimeException maintenanceFailure) {
                postCommitMaintenanceFailureCount++;
                lastPostCommitMaintenanceFailure = failureSummary(maintenanceFailure);
            }
        }

        lastCommittedChangedRowCount = prepared.changedRowCount();
        lastCommittedWriteIntentCount = prepared.writeIntentCount();
        lastCommittedWriteIntentPayloadSummaries = prepared.payloadSummaries();
        prepared.handle().clearWriteIntents();
        activeTransactions.remove(prepared.handle());

        if (failure != null) {
            RecoveryRequired unhealthy = markRecoveryRequired(
                    "database transaction participant publication", failure);
            throw new CommittedTransactionRecoveryRequiredException(
                    storageId(segmentId, containerId),
                    databaseCommit.databaseTransactionId().value(),
                    databaseCommit.localStatus().commitSequence().value(),
                    unhealthy,
                    failure);
        }
    }

    void abortDatabaseCommit(DatabasePreparedCommit databaseCommit) {
        requireOwnedDatabaseCommit(databaseCommit);
        Throwable failure = null;
        try {
            pageVolumeStateStore.abortStagedChanges(databaseCommit.stagedChanges());
        } catch (RuntimeException | Error stageAbortFailure) {
            failure = stageAbortFailure;
        }
        try {
            abortDatabaseTransaction(databaseCommit.preparedCommit().handle());
        } catch (RuntimeException | Error transactionAbortFailure) {
            if (failure == null) {
                failure = transactionAbortFailure;
            } else {
                failure.addSuppressed(transactionAbortFailure);
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
    }

    void abortDatabaseTransaction(DelosStorageTransaction transaction) {
        abortDatabaseTransaction(nativeTransactionHandle(transaction));
    }

    private void abortDatabaseTransaction(MvccInheritedHandles.Transaction handle) {
        Throwable failure = new IllegalStateException("database-scoped MVCC commit aborted before decision");
        abortIfActive(handle.nativeTransaction(), failure);
        handle.clearWriteIntents();
        activeTransactions.remove(handle);
        if (failure.getSuppressed().length > 0) {
            Throwable abortFailure = failure.getSuppressed()[0];
            if (abortFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (abortFailure instanceof Error errorFailure) {
                throw errorFailure;
            }
        }
    }

    private void requireOwnedDatabaseCommit(DatabasePreparedCommit databaseCommit) {
        if (Objects.requireNonNull(databaseCommit, "databaseCommit").table() != this) {
            throw new IllegalArgumentException("database commit belongs to another MVCC table");
        }
    }

    void commitSingleParticipant(DelosStorageTransaction transaction) {
        boolean observe = MvccCommitJfr.enabled();
        MvccCommitMetrics.Concurrency noConcurrency = MvccCommitMetrics.Concurrency.NONE;
        MvccCommitMetrics.Concurrency requestConcurrency = observe
                ? commitMetrics.enterRequest()
                : noConcurrency;
        MvccCommitMetrics.Concurrency preparationConcurrency = noConcurrency;
        MvccCommitMetrics.Concurrency durabilityQueueConcurrency = noConcurrency;
        long commitStarted = observe ? System.nanoTime() : 0L;
        long preparationNanos = 0L;
        MvccInheritedHandles.Transaction handle = null;
        MvccPreparedCommit preparedCommit;
        CommitPublication publication = CommitPublication.empty();
        MvccCommitCoordinator.Submission<CommitPublication> submission = null;
        Throwable commitFailure = null;
        boolean success = false;
        try {
            handle = nativeTransactionHandle(transaction);
            if (handle.readOnly()) {
                throw new IllegalStateException("read-only delos_mvcc transaction cannot commit");
            }

            long preparationStarted = observe ? System.nanoTime() : 0L;
            if (observe) {
                preparationConcurrency = commitMetrics.enterPreparation();
            }
            try {
                preparedCommit = prepareCommit(handle);
            } catch (RuntimeException failure) {
                MvccCommitDurabilityMetrics.Scope cleanupDurability =
                        MvccCommitDurabilityMetrics.begin(observe);
                try {
                    cleanupFailedPreparation(handle, failure);
                } finally {
                    publication = publication.withDurability(cleanupDurability.finish());
                }
                throw failure;
            } finally {
                if (observe) {
                    preparationNanos = System.nanoTime() - preparationStarted;
                    commitMetrics.exitPreparation();
                }
            }

            if (observe) {
                durabilityQueueConcurrency = commitMetrics.enterDurabilityQueue();
            }
            try {
                submission = durabilityCoordinator.submit(
                        preparedCommit,
                        observe,
                        this::publishPreparedGroup);
            } finally {
                if (observe) {
                    commitMetrics.exitDurabilityQueue();
                }
            }
            publication = submission.value() == null ? CommitPublication.empty() : submission.value();
            if (!submission.succeeded()) {
                throwUnchecked(submission.failure());
            }
            success = true;
        } catch (RuntimeException | Error failure) {
            commitFailure = failure;
            throw failure;
        } finally {
            if (observe) {
                MvccCommitDurabilityMetrics.Snapshot beginDurability = handle == null
                        ? MvccCommitDurabilityMetrics.Snapshot.empty()
                        : handle.beginDurability();
                String coordinatorMode = submission == null
                        ? durabilityCoordinator.mode().label()
                        : submission.mode().label();
                int enrollmentDepth = submission == null ? 0 : submission.enrollmentDepth();
                MvccCommitMetrics.Sample sample = new MvccCommitMetrics.Sample(
                        storageId(segmentId, containerId),
                        handle == null ? -1L : handle.nativeTransaction().id().value(),
                        publication.changedRows(),
                        System.nanoTime() - commitStarted,
                        preparationNanos,
                        publication.backupWaitNanos(),
                        submission == null ? 0L : submission.waitNanos(),
                        publication.coordinatorHoldNanos(),
                        coordinatorMode,
                        enrollmentDepth,
                        submission == null ? 0L : submission.groupId(),
                        submission == null ? 1 : submission.groupSize(),
                        submission == null || submission.leader(),
                        submission == null ? 0L : submission.groupWaitNanos(),
                        publication.sharedForceCount(),
                        commitFailure != null && (submission == null || submission.leader()),
                        commitFailure != null && submission != null && !submission.leader(),
                        publication.tableLockWaitNanos(),
                        publication.tableLockHoldNanos(),
                        publication.validationNanos(),
                        publication.transactionStatusCommitNanos(),
                        publication.pageStatePersistenceNanos(),
                        publication.orderedIndexRebuildNanos(),
                        publication.transactionStatePublicationNanos(),
                        publication.maintenanceNanos(),
                        requestConcurrency,
                        preparationConcurrency,
                        durabilityQueueConcurrency,
                        publication.durabilityExecutionConcurrency(),
                        beginDurability.plus(publication.durability()),
                        beginDurability.observed() && publication.durability().observed(),
                        success,
                        failureSummary(commitFailure));
                try {
                    MvccCommitJfr.record(sample);
                } catch (RuntimeException instrumentationFailure) {
                    if (commitFailure != null) {
                        commitFailure.addSuppressed(instrumentationFailure);
                    }
                } finally {
                    commitMetrics.exitRequest();
                }
            }
        }
    }

    private List<MvccCommitCoordinator.Outcome<CommitPublication>> publishPreparedGroup(
            List<MvccPreparedCommit> preparedCommits) {
        boolean observe = MvccCommitJfr.enabled();
        List<MvccCommitCoordinator.Outcome<CommitPublication>> outcomes = new ArrayList<>(preparedCommits.size());
        for (int index = 0; index < preparedCommits.size(); index++) {
            outcomes.add(null);
        }
        long backupWaitStarted = observe ? System.nanoTime() : 0L;
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(
                             DelosStorageBackupCoordinator.Mutation.COMMIT_PUBLICATION)) {
            long backupWaitNanos = observe ? System.nanoTime() - backupWaitStarted : 0L;
            MvccCommitMetrics.Concurrency executionConcurrency = observe
                    ? commitMetrics.enterDurabilityExecution()
                    : MvccCommitMetrics.Concurrency.NONE;
            long coordinatorHoldStarted = observe ? System.nanoTime() : 0L;
            try {
                long lockWaitStarted = observe ? System.nanoTime() : 0L;
                writeLock.lock();
                long lockWaitNanos = observe ? System.nanoTime() - lockWaitStarted : 0L;
                long lockHoldStarted = observe ? System.nanoTime() : 0L;
                try {
                    publishPreparedGroupLocked(
                            preparedCommits,
                            outcomes,
                            observe,
                            backupWaitNanos,
                            lockWaitNanos,
                            executionConcurrency);
                } finally {
                    long lockHoldNanos = observe ? System.nanoTime() - lockHoldStarted : 0L;
                    int sharedOwner = firstSuccessfulOutcome(outcomes);
                    if (sharedOwner >= 0) {
                        MvccCommitCoordinator.Outcome<CommitPublication> outcome = outcomes.get(sharedOwner);
                        outcomes.set(sharedOwner, MvccCommitCoordinator.Outcome.success(
                                outcome.value().withTableLockHold(lockHoldNanos)));
                    }
                    writeLock.unlock();
                }
            } finally {
                long holdNanos = observe ? System.nanoTime() - coordinatorHoldStarted : 0L;
                int sharedOwner = firstSuccessfulOutcome(outcomes);
                if (sharedOwner >= 0) {
                    MvccCommitCoordinator.Outcome<CommitPublication> outcome = outcomes.get(sharedOwner);
                    outcomes.set(sharedOwner, MvccCommitCoordinator.Outcome.success(
                            outcome.value().withCoordinatorHold(holdNanos)));
                }
                if (observe) {
                    commitMetrics.exitDurabilityExecution();
                }
            }
        }
        for (int index = 0; index < outcomes.size(); index++) {
            if (outcomes.get(index) == null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(
                        new IllegalStateException("MVCC commit group produced no result")));
            }
        }
        return List.copyOf(outcomes);
    }

    private void publishPreparedGroupLocked(
            List<MvccPreparedCommit> preparedCommits,
            List<MvccCommitCoordinator.Outcome<CommitPublication>> outcomes,
            boolean observe,
            long backupWaitNanos,
            long tableLockWaitNanos,
            MvccCommitMetrics.Concurrency executionConcurrency) {
        List<Integer> survivors = new ArrayList<>();
        Set<MvccInheritedHandles.Transaction> groupHandles = preparedCommits.stream()
                .map(MvccPreparedCommit::handle)
                .collect(java.util.stream.Collectors.toSet());
        Set<Long> reservedRows = new java.util.HashSet<>();
        long[] validationNanos = new long[preparedCommits.size()];

        for (int index = 0; index < preparedCommits.size(); index++) {
            MvccPreparedCommit prepared = preparedCommits.get(index);
            long started = observe ? System.nanoTime() : 0L;
            try {
                requirePreparedCommitCanPublish(prepared, groupHandles);
                for (PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]> change : prepared.changes()) {
                    if (!reservedRows.add(change.rowId())) {
                        throw new MvccWriteConflictException(
                                "provider group commit conflict on row " + change.rowId());
                    }
                }
                survivors.add(index);
            } catch (RuntimeException failure) {
                MvccCommitDurabilityMetrics.Scope cleanup = MvccCommitDurabilityMetrics.begin(observe);
                try {
                    abortIfActive(prepared.transaction(), failure);
                    prepared.handle().clearWriteIntents();
                    activeTransactions.remove(prepared.handle());
                } finally {
                    outcomes.set(index, MvccCommitCoordinator.Outcome.failure(failure));
                    cleanup.finish();
                }
            } finally {
                if (observe) {
                    validationNanos[index] = System.nanoTime() - started;
                }
            }
        }
        if (survivors.isEmpty()) {
            return;
        }

        MvccCommitDurabilityMetrics.Scope sharedScope = MvccCommitDurabilityMetrics.begin(observe);
        MvccCommitDurabilityMetrics.Snapshot sharedDurability;
        List<MvccPreparedCommit> survivingCommits = survivors.stream()
                .map(preparedCommits::get)
                .toList();
        MvccTransactionManager.PreparedCommitBatch preparedStatusBatch;
        try {
            preparedStatusBatch = transactions.prepareCommitBatch(survivingCommits.stream()
                    .map(MvccPreparedCommit::transaction)
                    .toList());
        } catch (RuntimeException | Error failure) {
            sharedDurability = sharedScope.finish();
            for (int survivor : survivors) {
                outcomes.set(survivor, MvccCommitCoordinator.Outcome.failure(failure));
            }
            return;
        }

        List<Integer> stagedSurvivors = new ArrayList<>();
        List<MvccPreparedCommit> stagedCommits = new ArrayList<>();
        List<MvccTransactionManager.PreparedCommit> stagedStatuses = new ArrayList<>();
        List<PageVolumeMvccStateStore.StagedChanges> stagedChanges = new ArrayList<>();
        List<CommitPublication> publications = new ArrayList<>();
        for (int position = 0; position < survivors.size(); position++) {
            int index = survivors.get(position);
            MvccPreparedCommit prepared = preparedCommits.get(index);
            MvccTransactionManager.PreparedCommit preparedStatus = preparedStatusBatch.commits().get(position);
            MvccCommitDurabilityMetrics.Scope memberScope = MvccCommitDurabilityMetrics.begin(observe);
            long persistenceStarted = observe ? System.nanoTime() : 0L;
            try {
                PageVolumeMvccStateStore.StagedChanges staged = pageVolumeStateStore.stagePreparedChanges(
                        prepared.preparedPageChanges(),
                        preparedStatus.commitSequence(),
                        prepared.transaction().id().value());
                stagedSurvivors.add(index);
                stagedCommits.add(prepared);
                stagedStatuses.add(preparedStatus);
                stagedChanges.add(staged);
                long persistenceNanos = observe ? System.nanoTime() - persistenceStarted : 0L;
                publications.add(new CommitPublication(
                        prepared.changedRowCount(),
                        stagedSurvivors.size() == 1 ? backupWaitNanos : 0L,
                        0L,
                        stagedSurvivors.size() == 1 ? tableLockWaitNanos : 0L,
                        0L,
                        validationNanos[index],
                        0L,
                        persistenceNanos,
                        0L,
                        0L,
                        0L,
                        0L,
                        executionConcurrency,
                        memberScope.finish()));
            } catch (RuntimeException | Error stageFailure) {
                memberScope.finish();
                abortIfActive(prepared.transaction(), stageFailure);
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(stageFailure));
            }
        }
        if (stagedSurvivors.isEmpty()) {
            sharedScope.finish();
            return;
        }

        long statusStarted = observe ? System.nanoTime() : 0L;
        try {
            sharedStatusForceHook.beforeForce(List.copyOf(stagedCommits));
        } catch (RuntimeException | Error failure) {
            abortStagedBeforeStatusPublication(
                    stagedSurvivors, stagedCommits, stagedChanges, outcomes, failure);
            sharedScope.finish();
            return;
        }

        boolean statusPublicationCompleted = false;
        try {
            transactions.publishPreparedCommitBatch(new MvccTransactionManager.PreparedCommitBatch(
                    preparedStatusBatch.baseCommitSequence(),
                    stagedStatuses));
            statusPublicationCompleted = true;
            sharedStatusForceHook.afterForce(List.copyOf(stagedCommits));
            backupCoordinator.recordCommittedTransactions(stagedSurvivors.size());
        } catch (RuntimeException | Error failure) {
            RecoveryRequired unhealthy = markRecoveryRequired(
                    "transaction-status publication", failure);
            for (int position = 0; position < stagedSurvivors.size(); position++) {
                MvccPreparedCommit prepared = stagedCommits.get(position);
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
                RuntimeException outcomeFailure = statusPublicationCompleted
                        ? new CommittedTransactionRecoveryRequiredException(
                                storageId(segmentId, containerId),
                                prepared.transaction().id().value(),
                                stagedStatuses.get(position).commitSequence().value(),
                                unhealthy,
                                failure)
                        : new TransactionStatusOutcomeUnknownException(
                                storageId(segmentId, containerId),
                                prepared.transaction().id().value(),
                                stagedStatuses.get(position).commitSequence().value(),
                                unhealthy,
                                failure);
                outcomes.set(stagedSurvivors.get(position),
                        MvccCommitCoordinator.Outcome.failure(outcomeFailure));
            }
            sharedScope.finish();
            return;
        }
        long statusNanos = observe ? System.nanoTime() - statusStarted : 0L;

        boolean anyMaterialized = false;
        int totalChangedRows = 0;
        for (int position = 0; position < stagedSurvivors.size(); position++) {
            int index = stagedSurvivors.get(position);
            MvccPreparedCommit prepared = stagedCommits.get(position);
            PageVolumeMvccStateStore.StagedChanges staged = stagedChanges.get(position);
            long publicationStarted = observe ? System.nanoTime() : 0L;
            RecoveryRequired existingRecovery = recoveryRequired.get();
            if (existingRecovery != null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(
                        new CommittedTransactionRecoveryRequiredException(
                                storageId(segmentId, containerId),
                                prepared.transaction().id().value(),
                                staged.commitSequence(),
                                existingRecovery,
                                new IllegalStateException(existingRecovery.failureSummary()))));
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
                continue;
            }
            MvccCommitDurabilityMetrics.Scope publicationScope =
                    MvccCommitDurabilityMetrics.begin(observe);
            try {
                pageVolumeStateStore.publishStagedChanges(staged);
                anyMaterialized |= !staged.empty();
                totalChangedRows += prepared.changedRowCount();
            } catch (RuntimeException | Error publicationFailure) {
                RecoveryRequired unhealthy = markRecoveryRequired("committed page publication", publicationFailure);
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(
                        new CommittedTransactionRecoveryRequiredException(
                                storageId(segmentId, containerId),
                                prepared.transaction().id().value(),
                                staged.commitSequence(),
                                unhealthy,
                                publicationFailure)));
            } finally {
                MvccCommitDurabilityMetrics.Snapshot publicationDurability = publicationScope.finish();
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
                if (observe) {
                    publications.set(position, publications.get(position).withTransactionPublication(
                            System.nanoTime() - publicationStarted,
                            publicationDurability));
                }
            }
        }

        long indexNanos = 0L;
        Throwable sharedFailure = null;
        if (recoveryRequired.get() == null && anyMaterialized) {
            long indexStarted = observe ? System.nanoTime() : 0L;
            try {
                orderedIndexPublicationHook.run();
                indexMaintenance.rebuildFromCommittedRows();
            } catch (RuntimeException | Error failure) {
                RecoveryRequired unhealthy = markRecoveryRequired("ordered-index publication", failure);
                sharedFailure = new CommittedTransactionRecoveryRequiredException(
                        storageId(segmentId, containerId),
                        -1L,
                        stagedStatuses.getLast().commitSequence().value(),
                        unhealthy,
                        failure);
            }
            indexNanos = observe ? System.nanoTime() - indexStarted : 0L;
        }

        long maintenanceNanos = 0L;
        if (sharedFailure == null && recoveryRequired.get() == null && anyMaterialized) {
            long maintenanceStarted = observe ? System.nanoTime() : 0L;
            try {
                postCommitMaintenanceHook.run();
                runPurgeDaemonAfterCommit(totalChangedRows);
            } catch (RuntimeException maintenanceFailure) {
                postCommitMaintenanceFailureCount++;
                lastPostCommitMaintenanceFailure = failureSummary(maintenanceFailure);
            }
            maintenanceNanos = observe ? System.nanoTime() - maintenanceStarted : 0L;
        }
        sharedDurability = sharedScope.finish();

        int leaderIndex = stagedSurvivors.getFirst();
        for (int position = 0; position < stagedSurvivors.size(); position++) {
            int index = stagedSurvivors.get(position);
            if (outcomes.get(index) != null) {
                continue;
            }
            if (sharedFailure != null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(sharedFailure));
                continue;
            }
            MvccPreparedCommit prepared = stagedCommits.get(position);
            lastCommittedChangedRowCount = prepared.changedRowCount();
            lastCommittedWriteIntentCount = prepared.writeIntentCount();
            lastCommittedWriteIntentPayloadSummaries = prepared.payloadSummaries();
            CommitPublication publication = publications.get(position);
            if (index == leaderIndex) {
                publication = publication.withShared(
                        statusNanos,
                        indexNanos,
                        maintenanceNanos,
                        sharedDurability);
            }
            outcomes.set(index, MvccCommitCoordinator.Outcome.success(publication));
        }
    }

    @Override
    public void abort(DelosStorageTransaction transaction) {
        durableMutationLocked(DelosStorageBackupCoordinator.Mutation.TRANSACTION_ABORT, () -> {
            MvccInheritedHandles.Transaction handle = nativeTransactionHandle(transaction);
            try {
                transactions.abort(handle.nativeTransaction());
                handle.clearWriteIntents();
            } finally {
                activeTransactions.remove(handle);
            }
        });
    }


    private void abortStagedBeforeStatusPublication(
            List<Integer> stagedSurvivors,
            List<MvccPreparedCommit> stagedCommits,
            List<PageVolumeMvccStateStore.StagedChanges> stagedChanges,
            List<MvccCommitCoordinator.Outcome<CommitPublication>> outcomes,
            Throwable failure) {
        for (int position = 0; position < stagedSurvivors.size(); position++) {
            try {
                pageVolumeStateStore.abortStagedChanges(stagedChanges.get(position));
            } catch (RuntimeException | Error abortFailure) {
                failure.addSuppressed(abortFailure);
            }
            MvccPreparedCommit prepared = stagedCommits.get(position);
            abortIfActive(prepared.transaction(), failure);
            prepared.handle().clearWriteIntents();
            activeTransactions.remove(prepared.handle());
            outcomes.set(stagedSurvivors.get(position), MvccCommitCoordinator.Outcome.failure(failure));
        }
    }

    private void runPurgeDaemonAfterCommit(int changedRows) {
        MvccVisibilityDebtPolicy.Snapshot debt = visibilityDebtSnapshot();
        if (purgeDaemon.asynchronousEnabled()) {
            if (!purgeDaemon.eligibleAfterCommit(changedRows, debt)) {
                return;
            }
            purgeDaemon.recordAsyncScheduled(changedRows, debt);
            maintenanceRegistration.request(
                    MvccDatabaseMaintenanceService.Priority.from(debt),
                    MvccDatabaseMaintenanceService.Trigger.COMMIT);
            return;
        }
        purgeDaemon.maybeRunAfterCommit(
                changedRows,
                this::visibilityDebtSnapshot,
                this::hasRetainedInheritedSnapshot,
                () -> vacuumOutcome(pageVolumeStateStore.vacuumSafely(false)))
                .ifPresent(outcome -> lastVacuumOutcome = outcome);
    }

    private Optional<MvccDatabaseMaintenanceService.Priority> periodicMaintenancePriority() {
        if (closeStarted.get() || closed.get() || recoveryRequired.get() != null) {
            return Optional.empty();
        }
        return readLocked(() -> {
            if (closed.get()) {
                return Optional.empty();
            }
            MvccVisibilityDebtPolicy.Snapshot debt = visibilityDebtSnapshot();
            if (!purgeDaemon.periodicMaintenanceEligible(debt)) {
                return Optional.empty();
            }
            purgeDaemon.recordPeriodicScheduled(debt);
            return Optional.of(MvccDatabaseMaintenanceService.Priority.from(debt));
        });
    }

    private void runScheduledMaintenance(MvccDatabaseMaintenanceService.Trigger trigger) {
        if (closeStarted.get() || closed.get() || recoveryRequired.get() != null) {
            return;
        }
        durableMutationLocked(DelosStorageBackupCoordinator.Mutation.ASYNCHRONOUS_MAINTENANCE, () -> {
            if (closed.get()) {
                return;
            }
            if (hasRetainedInheritedSnapshot()) {
                purgeDaemon.recordAsyncSkip("retained inherited MVCC transaction or scan");
                return;
            }
            if (!purgeDaemon.eligibleVisibilityDebt(visibilityDebtSnapshot())) {
                return;
            }
            DelosVacuumOutcome outcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(false));
            lastVacuumOutcome = outcome;
            purgeDaemon.recordAsyncRun(outcome);
        });
    }

    private MvccVisibilityDebtPolicy.Snapshot visibilityDebtSnapshot() {
        long obsoleteVersions = Math.max(
                0L,
                (long) pageVolumeStateStore.physicalVersionCount() - pageVolumeStateStore.logicalRowCount());
        return new MvccVisibilityDebtPolicy.Snapshot(
                pageVolumeStateStore.visibilityMapPrunablePageCount(),
                pageVolumeStateStore.visibilityMapOldVersionPageCount(),
                pageVolumeStateStore.visibilityMapTombstonePageCount(),
                pageVolumeStateStore.purgeQueuePendingCount(),
                obsoleteVersions);
    }

    private boolean hasRetainedInheritedSnapshot() {
        return transactions.activeTransactionCount() > 0 || transactions.retainedSnapshotCount() > 0;
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
        return readLocked(purgeDaemon::scheduleCount);
    }

    @Override
    public long purgeDaemonRunCountForTesting() {
        return readLocked(purgeDaemon::runCount);
    }

    @Override
    public long purgeDaemonSkipCountForTesting() {
        return readLocked(purgeDaemon::skipCount);
    }

    @Override
    public long purgeDaemonLastTriggerChangedRowsForTesting() {
        return readLocked(purgeDaemon::lastTriggerChangedRows);
    }

    @Override
    public String purgeDaemonLastDecisionForTesting() {
        return readLocked(purgeDaemon::lastDecision);
    }

    @Override
    public long purgeDaemonLastVisibilityDebtScoreForTesting() {
        return readLocked(purgeDaemon::lastVisibilityDebtScore);
    }

    @Override
    public String purgeDaemonLastVisibilityDebtSummaryForTesting() {
        return readLocked(purgeDaemon::lastVisibilityDebtSummary);
    }

    @Override
    public int databaseMaintenanceWorkerCountForTesting() {
        return maintenanceService.metrics().workerCount();
    }

    @Override
    public int databaseMaintenanceRegisteredTableCountForTesting() {
        return maintenanceService.metrics().registeredTableCount();
    }

    @Override
    public int databaseMaintenanceQueuedTaskCountForTesting() {
        return maintenanceService.metrics().queuedTaskCount();
    }

    @Override
    public long databaseMaintenanceCommitWakeupCountForTesting() {
        return maintenanceService.metrics().commitWakeupCount();
    }

    @Override
    public long databaseMaintenancePeriodicScanCountForTesting() {
        return maintenanceService.metrics().periodicScanCount();
    }

    @Override
    public long databaseMaintenanceRunCountForTesting() {
        return maintenanceService.metrics().runCount();
    }

    @Override
    public long databaseMaintenanceFailureCountForTesting() {
        return maintenanceService.metrics().failureCount();
    }

    @Override
    public int databaseMaintenanceMaximumActiveWorkerCountForTesting() {
        return maintenanceService.metrics().maximumActiveWorkerCount();
    }

    @Override
    public boolean databaseMaintenanceAcceptingForTesting() {
        return maintenanceService.metrics().accepting();
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
        return durableMutationLocked(DelosStorageBackupCoordinator.Mutation.VACUUM, () -> {
            lastVacuumOutcome = vacuumOutcome(pageVolumeStateStore.vacuumSafely(hasRetainedInheritedSnapshot()));
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
        closeLock.lock();
        try {
            if (closed.get()) {
                return;
            }
            closeStarted.set(true);
            durabilityCoordinator.close();
            if (ownsMaintenanceService) {
                maintenanceService.close();
            }
            maintenanceRegistration.close();
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
        return maintenanceService;
    }

    DelosStorageBackupCoordinator backupCoordinatorForTesting() {
        return backupCoordinator;
    }

    void setPagePublicationHookForTesting(PageVolumeMvccStateStore.PublicationHook hook) {
        pageVolumeStateStore.setPublicationHookForTesting(hook);
    }

    void setOrderedIndexPublicationHookForTesting(Runnable hook) {
        orderedIndexPublicationHook = Objects.requireNonNull(hook, "hook");
    }

    void setPostCommitMaintenanceHookForTesting(Runnable hook) {
        postCommitMaintenanceHook = Objects.requireNonNull(hook, "hook");
    }


    private final class MaintenanceTarget implements MvccDatabaseMaintenanceService.Target {
        @Override
        public String maintenanceIdentity() {
            return segmentId + ":" + containerId;
        }

        @Override
        public Optional<MvccDatabaseMaintenanceService.Priority> periodicMaintenancePriority() {
            return MvccInheritedTable.this.periodicMaintenancePriority();
        }

        @Override
        public void runMaintenance(MvccDatabaseMaintenanceService.Trigger trigger) {
            MvccInheritedTable.this.runScheduledMaintenance(trigger);
        }
    }


    private MvccPreparedCommit prepareCommit(MvccInheritedHandles.Transaction handle) {
        CommitInput input = readLocked(() -> {
            if (!activeTransactions.contains(handle)) {
                throw new IllegalStateException("delos_mvcc transaction is no longer active: "
                        + handle.nativeTransaction().id());
            }
            List<MvccInheritedHandles.Transaction.WriteIntent> intents = handle.writeIntents();
            return new CommitInput(intents, handle.writeIntentRevision(), intents.size());
        });
        List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes =
                changedRows(input.intents());
        PageVolumeMvccStateStore.PreparedChanges preparedPageChanges =
                pageVolumeStateStore.prepareChangedRows(changes);
        return new MvccPreparedCommit(
                handle,
                handle.nativeTransaction(),
                changes,
                preparedPageChanges,
                input.writeIntentRevision(),
                input.writeIntentCount(),
                committedChangePayloadSummaries(changes));
    }

    private void cleanupFailedPreparation(
            MvccInheritedHandles.Transaction handle,
            RuntimeException failure) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(
                             DelosStorageBackupCoordinator.Mutation.PREPARATION_FAILURE_CLEANUP)) {
            writeLock.lock();
            try {
                if (!activeTransactions.contains(handle)) {
                    return;
                }
                abortIfActive(handle.nativeTransaction(), failure);
                handle.clearWriteIntents();
                activeTransactions.remove(handle);
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void requirePreparedCommitCanPublish(MvccPreparedCommit preparedCommit) {
        requirePreparedCommitCanPublish(preparedCommit, Set.of());
    }

    private void requirePreparedCommitCanPublish(
            MvccPreparedCommit preparedCommit,
            Set<MvccInheritedHandles.Transaction> ignoredWriters) {
        MvccInheritedHandles.Transaction handle = preparedCommit.handle();
        if (!activeTransactions.contains(handle)) {
            throw new IllegalStateException("prepared delos_mvcc transaction is no longer active: "
                    + preparedCommit.transaction().id());
        }
        if (handle.writeIntentRevision() != preparedCommit.writeIntentRevision()) {
            throw new IllegalStateException("delos_mvcc transaction changed after commit preparation: "
                    + preparedCommit.transaction().id());
        }
        for (PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]> change : preparedCommit.changes()) {
            requireNoOtherActiveProviderWriter(
                    handle, change.rowId(), "commit publication", ignoredWriters);
        }
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
        for (MvccInheritedHandles.Transaction activeTransaction : activeTransactions) {
            if (activeTransaction != handle
                    && !ignoredWriters.contains(activeTransaction)
                    && activeTransaction.hasWriteIntentForRow(rowId)) {
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

    int activeTransactionCountForTesting() {
        return transactions.activeTransactionCount();
    }

    int activeCommitRequestsForTesting() {
        return commitMetrics.activeTableRequests();
    }

    int durabilityEnrollmentCountForTesting() {
        return durabilityCoordinator.currentEnrollmentCountForTesting();
    }

    private static String failureSummary(Throwable failure) {
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

    private RecoveryRequired markRecoveryRequired(String stage, Throwable failure) {
        RecoveryRequired candidate = new RecoveryRequired(
                Objects.requireNonNull(stage, "stage"),
                failureSummary(Objects.requireNonNull(failure, "failure")));
        recoveryRequired.compareAndSet(null, candidate);
        return recoveryRequired.get();
    }

    boolean recoveryRequiredForTesting() {
        return recoveryRequired.get() != null;
    }

    String recoveryRequiredSummaryForTesting() {
        RecoveryRequired unhealthy = recoveryRequired.get();
        return unhealthy == null ? "" : unhealthy.stage() + ": " + unhealthy.failureSummary();
    }

    long postCommitMaintenanceFailureCountForTesting() {
        return postCommitMaintenanceFailureCount;
    }

    String lastPostCommitMaintenanceFailureForTesting() {
        return lastPostCommitMaintenanceFailure;
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
        requireOperational();
        readLock.lock();
        try {
            requireOperational();
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    private void readLocked(Runnable operation) {
        requireOperational();
        readLock.lock();
        try {
            requireOperational();
            operation.run();
        } finally {
            readLock.unlock();
        }
    }

    private <T> T writeLocked(Supplier<T> operation) {
        requireOperational();
        writeLock.lock();
        try {
            requireOperational();
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    private void writeLocked(Runnable operation) {
        requireOperational();
        writeLock.lock();
        try {
            requireOperational();
            operation.run();
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T durableMutationLocked(
            DelosStorageBackupCoordinator.Mutation mutation,
            Supplier<T> operation) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(mutation)) {
            return writeLocked(operation);
        }
    }

    private void durableMutationLocked(
            DelosStorageBackupCoordinator.Mutation mutation,
            Runnable operation) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(mutation)) {
            writeLocked(operation);
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

    private static int firstSuccessfulOutcome(
            List<MvccCommitCoordinator.Outcome<CommitPublication>> outcomes) {
        for (int index = 0; index < outcomes.size(); index++) {
            MvccCommitCoordinator.Outcome<CommitPublication> outcome = outcomes.get(index);
            if (outcome != null && outcome.succeeded()) {
                return index;
            }
        }
        return -1;
    }

    private static void throwUnchecked(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("MVCC commit group failed", failure);
    }

    private record CommitPublication(
            int changedRows,
            long backupWaitNanos,
            long coordinatorHoldNanos,
            long tableLockWaitNanos,
            long tableLockHoldNanos,
            long validationNanos,
            long transactionStatusCommitNanos,
            long pageStatePersistenceNanos,
            long orderedIndexRebuildNanos,
            long transactionStatePublicationNanos,
            long maintenanceNanos,
            long sharedForceCount,
            MvccCommitMetrics.Concurrency durabilityExecutionConcurrency,
            MvccCommitDurabilityMetrics.Snapshot durability) {
        private CommitPublication {
            durabilityExecutionConcurrency = Objects.requireNonNull(
                    durabilityExecutionConcurrency, "durabilityExecutionConcurrency");
            durability = Objects.requireNonNull(durability, "durability");
        }

        static CommitPublication empty() {
            return new CommitPublication(
                    0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                    MvccCommitMetrics.Concurrency.NONE,
                    MvccCommitDurabilityMetrics.Snapshot.empty());
        }

        CommitPublication withDurability(MvccCommitDurabilityMetrics.Snapshot extra) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, coordinatorHoldNanos,
                    tableLockWaitNanos, tableLockHoldNanos, validationNanos,
                    transactionStatusCommitNanos, pageStatePersistenceNanos,
                    orderedIndexRebuildNanos, transactionStatePublicationNanos,
                    maintenanceNanos, sharedForceCount, durabilityExecutionConcurrency,
                    durability.plus(extra));
        }

        CommitPublication withTableLockHold(long nanos) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, coordinatorHoldNanos,
                    tableLockWaitNanos, nanos, validationNanos,
                    transactionStatusCommitNanos, pageStatePersistenceNanos,
                    orderedIndexRebuildNanos, transactionStatePublicationNanos,
                    maintenanceNanos, sharedForceCount, durabilityExecutionConcurrency, durability);
        }

        CommitPublication withCoordinatorHold(long nanos) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, nanos,
                    tableLockWaitNanos, tableLockHoldNanos, validationNanos,
                    transactionStatusCommitNanos, pageStatePersistenceNanos,
                    orderedIndexRebuildNanos, transactionStatePublicationNanos,
                    maintenanceNanos, sharedForceCount, durabilityExecutionConcurrency, durability);
        }

        CommitPublication withTransactionPublication(
                long nanos,
                MvccCommitDurabilityMetrics.Snapshot publicationDurability) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, coordinatorHoldNanos,
                    tableLockWaitNanos, tableLockHoldNanos, validationNanos,
                    transactionStatusCommitNanos, pageStatePersistenceNanos,
                    orderedIndexRebuildNanos, nanos,
                    maintenanceNanos, sharedForceCount, durabilityExecutionConcurrency,
                    durability.plus(publicationDurability));
        }

        CommitPublication withShared(
                long statusNanos,
                long indexNanos,
                long maintenanceNanos,
                MvccCommitDurabilityMetrics.Snapshot sharedDurability) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, coordinatorHoldNanos,
                    tableLockWaitNanos, tableLockHoldNanos, validationNanos,
                    statusNanos, pageStatePersistenceNanos, indexNanos,
                    transactionStatePublicationNanos, maintenanceNanos,
                    sharedDurability.totalForceCount(),
                    durabilityExecutionConcurrency,
                    durability.plus(sharedDurability));
        }
    }

    @FunctionalInterface
    interface SharedStatusForceHook {
        SharedStatusForceHook NOOP = preparedCommits -> { };

        void beforeForce(List<MvccPreparedCommit> preparedCommits);

        default void afterForce(List<MvccPreparedCommit> preparedCommits) {
        }
    }

    private record RecoveryRequired(String stage, String failureSummary) {
        private RecoveryRequired {
            stage = Objects.requireNonNull(stage, "stage");
            failureSummary = Objects.requireNonNull(failureSummary, "failureSummary");
        }
    }

    static final class TableRecoveryRequiredException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private TableRecoveryRequiredException(String storageId, RecoveryRequired recoveryRequired) {
            super("MVCC table " + storageId + " requires close and reopen after "
                    + recoveryRequired.stage() + ": " + recoveryRequired.failureSummary());
        }
    }

    static final class TransactionStatusOutcomeUnknownException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final long transactionId;
        private final long proposedCommitSequence;

        private TransactionStatusOutcomeUnknownException(
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

        private CommittedTransactionRecoveryRequiredException(
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

    private record CommitInput(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents,
            long writeIntentRevision,
            int writeIntentCount) {
        private CommitInput {
            intents = List.copyOf(intents);
        }
    }

    private List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changedRows(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents) {
        List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes = new ArrayList<>();
        for (MvccInheritedHandles.Transaction.WriteIntent intent : intents) {
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
                summaries.add(change.rowId() + "|UPSERT|" + String.join("|", MvccInheritedIndexMaintenance.valueKeysRaw(change.values())));
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
                summaries.add(intent.rowId() + "|UPSERT|" + String.join("|", MvccInheritedIndexMaintenance.valueKeysRaw(intent.row())));
            }
        }
        return List.copyOf(summaries);
    }

    private void abortIfActive(MvccTransaction transaction, Throwable failure) {
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

    private static StoreDataValue[] cloneRowUnchecked(StoreDataValue[] row) {
        try {
            return StoreValueCopySupport.cloneRow(row);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not clone inherited MVCC row", e);
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
        return PageVolumeMvccPaths.transactionStatusFile(databaseDirectory, storageId(segmentId, containerId));
    }
}
