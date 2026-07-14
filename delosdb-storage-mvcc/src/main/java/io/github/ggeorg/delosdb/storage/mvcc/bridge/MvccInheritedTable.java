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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageCommittedRead;
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
    private final ExecutorService purgeDaemonExecutor;
    private final List<MvccInheritedHandles.Transaction> activeTransactions = new ArrayList<>();
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();
    private final MvccCommitMetrics commitMetrics = new MvccCommitMetrics();
    private final MvccCommitCoordinator<MvccPreparedCommit, CommitPublication> durabilityCoordinator;
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

    MvccInheritedTable(long segmentId, long containerId, Path databaseDirectory) {
        this(segmentId, containerId, databaseDirectory, configuredCommitCoordinatorMode());
    }

    private static MvccCommitCoordinator.Mode configuredCommitCoordinatorMode() {
        String configured = System.getProperty("delosdb.mvcc.commit.mode", "group").trim().toLowerCase();
        return switch (configured) {
            case "group" -> MvccCommitCoordinator.Mode.GROUP;
            case "direct" -> MvccCommitCoordinator.Mode.DIRECT;
            case "queued" -> MvccCommitCoordinator.Mode.QUEUED;
            default -> throw new IllegalArgumentException(
                    "unsupported delosdb.mvcc.commit.mode: " + configured);
        };
    }

    MvccInheritedTable(
            long segmentId,
            long containerId,
            Path databaseDirectory,
            MvccCommitCoordinator.Mode coordinatorMode) {
        this.segmentId = segmentId;
        this.containerId = containerId;
        this.retiredSnapshotFile = retiredSnapshotFile(databaseDirectory, segmentId, containerId);
        this.transactionStatusFile = transactionStatusFile(databaseDirectory, segmentId, containerId);
        this.pageVolumeStateStore = PageVolumeMvccStateStore.open(
                databaseDirectory,
                storageId(segmentId, containerId),
                MvccInheritedRowCodec.INSTANCE);
        this.indexMaintenance = new MvccInheritedIndexMaintenance(pageVolumeStateStore);
        this.transactionStatusStore = transactionStatusFile == null || containerId == 0L
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(transactionStatusFile);
        this.transactions = new MvccTransactionManager(transactionStatusStore);
        this.durabilityCoordinator = new MvccCommitCoordinator<>(coordinatorMode);
        this.purgeDaemonExecutor = Executors.newSingleThreadExecutor(runnable ->
                Thread.ofVirtual()
                        .name("delosdb-mvcc-purge-daemon-" + segmentId + '-' + containerId)
                        .unstarted(runnable));
        loadCommittedState();
    }

    @Override
    public DelosStorageTransaction beginTransaction() {
        return writeLocked(() -> {
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
    public void commit(DelosStorageTransaction transaction) {
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
                     DelosStorageBackupCoordinator.enterDurableMutation()) {
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
        long statusStarted = observe ? System.nanoTime() : 0L;
        List<MvccCommitSequence> sequences;
        try {
            sequences = transactions.commitBatch(survivors.stream()
                    .map(index -> preparedCommits.get(index).transaction())
                    .toList());
        } catch (RuntimeException | Error failure) {
            sharedDurability = sharedScope.finish();
            for (int survivor : survivors) {
                outcomes.set(survivor, MvccCommitCoordinator.Outcome.failure(failure));
            }
            return;
        }
        long statusNanos = observe ? System.nanoTime() - statusStarted : 0L;

        List<CommitPublication> publications = new ArrayList<>(survivors.size());
        boolean anyPersisted = false;
        int totalChangedRows = 0;
        for (int position = 0; position < survivors.size(); position++) {
            int index = survivors.get(position);
            MvccPreparedCommit prepared = preparedCommits.get(index);
            MvccCommitDurabilityMetrics.Scope memberScope = MvccCommitDurabilityMetrics.begin(observe);
            long persistenceStarted = observe ? System.nanoTime() : 0L;
            Throwable failure = null;
            try {
                pageVolumeStateStore.persistPreparedChanges(
                        prepared.preparedPageChanges(),
                        sequences.get(position));
                anyPersisted = true;
                totalChangedRows += prepared.changedRowCount();
            } catch (RuntimeException | Error memberFailure) {
                failure = memberFailure;
            }
            long persistenceNanos = observe ? System.nanoTime() - persistenceStarted : 0L;
            MvccCommitDurabilityMetrics.Snapshot durability = memberScope.finish();
            boolean sharedOwner = position == 0;
            CommitPublication publication = new CommitPublication(
                    prepared.changedRowCount(),
                    sharedOwner ? backupWaitNanos : 0L,
                    0L,
                    sharedOwner ? tableLockWaitNanos : 0L,
                    0L,
                    validationNanos[index],
                    0L,
                    persistenceNanos,
                    0L,
                    0L,
                    0L,
                    0L,
                    executionConcurrency,
                    durability);
            publications.add(publication);
            if (failure != null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(failure));
            }
        }

        long indexNanos = 0L;
        Throwable sharedFailure = null;
        if (anyPersisted) {
            long indexStarted = observe ? System.nanoTime() : 0L;
            try {
                indexMaintenance.rebuildFromCommittedRows();
            } catch (RuntimeException | Error failure) {
                sharedFailure = failure;
            }
            indexNanos = observe ? System.nanoTime() - indexStarted : 0L;
        }

        long publicationStarted = observe ? System.nanoTime() : 0L;
        for (int position = 0; position < survivors.size(); position++) {
            int index = survivors.get(position);
            MvccPreparedCommit prepared = preparedCommits.get(index);
            try {
                if (outcomes.get(index) == null && sharedFailure == null) {
                    lastCommittedChangedRowCount = prepared.changedRowCount();
                    lastCommittedWriteIntentCount = prepared.writeIntentCount();
                    lastCommittedWriteIntentPayloadSummaries = prepared.payloadSummaries();
                }
            } finally {
                // commitBatch() already published COMMITTED status for every survivor.
                // Later page, index, or maintenance failure is therefore a committed
                // materialization failure, not an active transaction that may retain
                // provider write intents.
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
            }
        }
        long publicationNanos = observe ? System.nanoTime() - publicationStarted : 0L;

        long maintenanceNanos = 0L;
        if (sharedFailure == null && anyPersisted) {
            long maintenanceStarted = observe ? System.nanoTime() : 0L;
            try {
                runPurgeDaemonAfterCommit(totalChangedRows);
            } catch (RuntimeException | Error failure) {
                sharedFailure = failure;
            }
            maintenanceNanos = observe ? System.nanoTime() - maintenanceStarted : 0L;
        }
        sharedDurability = sharedScope.finish();

        int leaderIndex = survivors.get(0);
        for (int position = 0; position < survivors.size(); position++) {
            int index = survivors.get(position);
            if (outcomes.get(index) != null) {
                continue;
            }
            if (sharedFailure != null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(sharedFailure));
                continue;
            }
            CommitPublication publication = publications.get(position);
            if (index == leaderIndex) {
                publication = publication.withShared(
                        statusNanos,
                        indexNanos,
                        publicationNanos,
                        maintenanceNanos,
                        sharedDurability);
            }
            outcomes.set(index, MvccCommitCoordinator.Outcome.success(publication));
        }
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


    private void runPurgeDaemonAfterCommit(int changedRows) {
        MvccVisibilityDebtPolicy.Snapshot debt = visibilityDebtSnapshot();
        if (purgeDaemon.asynchronousEnabled()) {
            if (!purgeDaemon.eligibleAfterCommit(changedRows, debt)) {
                return;
            }
            purgeDaemon.recordAsyncScheduled(changedRows, debt);
            purgeDaemonExecutor.execute(() -> durableMutationLocked(() -> {
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
            }));
            return;
        }
        purgeDaemon.maybeRunAfterCommit(
                changedRows,
                this::visibilityDebtSnapshot,
                this::hasRetainedInheritedSnapshot,
                () -> vacuumOutcome(pageVolumeStateStore.vacuumSafely(false)))
                .ifPresent(outcome -> lastVacuumOutcome = outcome);
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
        durableMutationLocked(() -> {
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
        return readLocked(pageVolumeStateStore::logicalRowCount);
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
        return durableMutationLocked(() -> {
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
        durableMutationLocked(() -> {
            purgeDaemonExecutor.shutdownNow();
            pageVolumeStateStore.close();
        });
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
                     DelosStorageBackupCoordinator.enterDurableMutation()) {
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

    int activeCommitRequestsForTesting() {
        return commitMetrics.activeTableRequests();
    }

    private static String failureSummary(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        return failure.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
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

    private <T> T durableMutationLocked(Supplier<T> operation) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     DelosStorageBackupCoordinator.enterDurableMutation()) {
            return writeLocked(operation);
        }
    }

    private void durableMutationLocked(Runnable operation) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     DelosStorageBackupCoordinator.enterDurableMutation()) {
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

        CommitPublication withShared(
                long statusNanos,
                long indexNanos,
                long publicationNanos,
                long maintenanceNanos,
                MvccCommitDurabilityMetrics.Snapshot sharedDurability) {
            return new CommitPublication(
                    changedRows, backupWaitNanos, coordinatorHoldNanos,
                    tableLockWaitNanos, tableLockHoldNanos, validationNanos,
                    statusNanos, pageStatePersistenceNanos, indexNanos,
                    publicationNanos, maintenanceNanos,
                    sharedDurability.totalForceCount(),
                    durabilityExecutionConcurrency,
                    durability.plus(sharedDurability));
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
        Path directory = PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory);
        if (directory == null) {
            return null;
        }
        return directory.resolve("conglomerate-" + segmentId + "-" + containerId + ".txstatus");
    }
}
