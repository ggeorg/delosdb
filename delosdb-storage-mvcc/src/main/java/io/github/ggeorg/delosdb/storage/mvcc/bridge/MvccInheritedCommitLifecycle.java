package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;
import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccCommitDurabilityMetrics;
import io.github.ggeorg.delosdb.storage.mvcc.failure.MvccStorageFailureHook;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;

/**
 * Owns the inherited-table commit lifecycle: preparation, durability grouping,
 * database-scoped decision publication, participant materialization, and
 * commit observability.
 */
final class MvccInheritedCommitLifecycle implements AutoCloseable {
    private final MvccInheritedTable owner;
    private final String storageId;
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccInheritedIndexMaintenance indexMaintenance;
    private final MvccTransactionManager transactions;
    private final DelosStorageBackupCoordinator backupCoordinator;
    private final MvccDatabaseCommitCoordinator databaseCommitCoordinator;
    private final MvccFailurePointRegistry failurePoints;
    private final AtomicReference<MvccInheritedTable.RecoveryRequired> recoveryRequired;
    private final List<MvccInheritedHandles.Transaction> activeTransactions;
    private final MvccInheritedTableAccess tableAccess;
    private final Lock writeLock;
    private final MvccInheritedMaintenanceLifecycle maintenanceLifecycle;
    private final MvccCommitMetrics commitMetrics = new MvccCommitMetrics();
    private final MvccCommitCoordinator<MvccPreparedCommit, CommitPublication> durabilityCoordinator;
    private final MvccInheritedTable.SharedStatusForceHook sharedStatusForceHook;

    private int lastCommittedChangedRowCount;
    private int lastCommittedWriteIntentCount;
    private List<String> lastCommittedWriteIntentPayloadSummaries = List.of();
    private volatile Runnable orderedIndexPublicationHook = () -> { };

    MvccInheritedCommitLifecycle(
            MvccInheritedTable owner,
            String storageId,
            PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore,
            MvccInheritedIndexMaintenance indexMaintenance,
            MvccTransactionManager transactions,
            DelosStorageBackupCoordinator backupCoordinator,
            MvccDatabaseCommitCoordinator databaseCommitCoordinator,
            MvccFailurePointRegistry failurePoints,
            AtomicReference<MvccInheritedTable.RecoveryRequired> recoveryRequired,
            List<MvccInheritedHandles.Transaction> activeTransactions,
            MvccInheritedTableAccess tableAccess,
            Lock writeLock,
            MvccInheritedMaintenanceLifecycle maintenanceLifecycle,
            MvccCommitCoordinator.Mode coordinatorMode,
            int coordinatorCapacity,
            int maxGroupSize,
            long maxGroupDelayNanos,
            MvccInheritedTable.SharedStatusForceHook sharedStatusForceHook) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.storageId = Objects.requireNonNull(storageId, "storageId");
        this.pageVolumeStateStore = Objects.requireNonNull(pageVolumeStateStore, "pageVolumeStateStore");
        this.indexMaintenance = Objects.requireNonNull(indexMaintenance, "indexMaintenance");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.backupCoordinator = Objects.requireNonNull(backupCoordinator, "backupCoordinator");
        this.databaseCommitCoordinator = Objects.requireNonNull(
                databaseCommitCoordinator, "databaseCommitCoordinator");
        this.failurePoints = Objects.requireNonNull(failurePoints, "failurePoints");
        this.recoveryRequired = Objects.requireNonNull(recoveryRequired, "recoveryRequired");
        this.activeTransactions = Objects.requireNonNull(activeTransactions, "activeTransactions");
        this.tableAccess = Objects.requireNonNull(tableAccess, "tableAccess");
        this.writeLock = Objects.requireNonNull(writeLock, "writeLock");
        this.maintenanceLifecycle = Objects.requireNonNull(maintenanceLifecycle, "maintenanceLifecycle");
        this.durabilityCoordinator = new MvccCommitCoordinator<>(
                coordinatorMode, coordinatorCapacity, maxGroupSize, maxGroupDelayNanos);
        this.sharedStatusForceHook = Objects.requireNonNull(sharedStatusForceHook, "sharedStatusForceHook");
    }

    void commit(DelosStorageTransaction transaction) {
        databaseCommitCoordinator.commit(List.of(
                new DelosStorageCommitCoordinator.Participant(owner, transaction)));
    }

    MvccInheritedTable.DatabasePreparedCommit prepareDatabaseCommit(
            DelosStorageTransaction transaction,
            MvccTransactionId databaseTransactionId,
            MvccCommitSequence commitSequence,
            int participantIndex,
            int participantCount) {
        MvccInheritedHandles.Transaction handle = MvccInheritedTable.nativeTransactionHandle(transaction);
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
                        databaseTransactionId.value(),
                        participantIndex,
                        participantCount);
        return new MvccInheritedTable.DatabasePreparedCommit(
                owner, databaseTransactionId, preparedCommit, localStatus, stagedChanges);
    }

    void publishDatabaseCommit(MvccInheritedTable.DatabasePreparedCommit databaseCommit) {
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
            pageVolumeStateStore.publishStagedChanges(
                    databaseCommit.stagedChanges(), failurePoints.storageHook());
        } catch (RuntimeException | Error publicationFailure) {
            if (failure == null) {
                failure = publicationFailure;
            } else {
                failure.addSuppressed(publicationFailure);
            }
        }

        if (failure == null && !databaseCommit.stagedChanges().empty()) {
            try {
                hitIndexPublication(databaseCommit.stagedChanges());
                orderedIndexPublicationHook.run();
                indexMaintenance.rebuildFromCommittedRows();
            } catch (RuntimeException | Error indexFailure) {
                failure = indexFailure;
            }
        }
        if (failure == null && !databaseCommit.stagedChanges().empty()) {
            maintenanceLifecycle.afterCommit(prepared.changedRowCount());
        }

        lastCommittedChangedRowCount = prepared.changedRowCount();
        lastCommittedWriteIntentCount = prepared.writeIntentCount();
        lastCommittedWriteIntentPayloadSummaries = prepared.payloadSummaries();
        prepared.handle().clearWriteIntents();
        activeTransactions.remove(prepared.handle());

        if (failure != null) {
            MvccInheritedTable.RecoveryRequired unhealthy = markRecoveryRequired(
                    "database transaction participant publication", failure);
            throw new MvccInheritedTable.CommittedTransactionRecoveryRequiredException(
                    storageId,
                    databaseCommit.databaseTransactionId().value(),
                    databaseCommit.localStatus().commitSequence().value(),
                    unhealthy,
                    failure);
        }
    }

    private void hitIndexPublication(
            PageVolumeMvccStateStore.StagedChanges stagedChanges) {
        MvccStorageFailureHook.Context context = stagedChanges.failureContext();
        failurePoints.hit(
                MvccFailurePointRegistry.Point.DURING_INDEX_PUBLICATION,
                MvccFailurePointRegistry.Context.transaction(
                        context.transactionId(),
                        context.commitSequence(),
                        context.participantIndex(),
                        context.participantCount()));
    }

    void abortDatabaseCommit(MvccInheritedTable.DatabasePreparedCommit databaseCommit) {
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
        abortDatabaseTransaction(MvccInheritedTable.nativeTransactionHandle(transaction));
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

    private void requireOwnedDatabaseCommit(MvccInheritedTable.DatabasePreparedCommit databaseCommit) {
        if (Objects.requireNonNull(databaseCommit, "databaseCommit").table() != owner) {
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
            handle = MvccInheritedTable.nativeTransactionHandle(transaction);
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
                MvccInheritedTable.throwUnchecked(submission.failure());
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
                        storageId,
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
                        MvccInheritedTable.failureSummary(commitFailure));
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
            MvccInheritedTable.RecoveryRequired unhealthy = markRecoveryRequired(
                    "transaction-status publication", failure);
            for (int position = 0; position < stagedSurvivors.size(); position++) {
                MvccPreparedCommit prepared = stagedCommits.get(position);
                prepared.handle().clearWriteIntents();
                activeTransactions.remove(prepared.handle());
                RuntimeException outcomeFailure = statusPublicationCompleted
                        ? new MvccInheritedTable.CommittedTransactionRecoveryRequiredException(
                                storageId,
                                prepared.transaction().id().value(),
                                stagedStatuses.get(position).commitSequence().value(),
                                unhealthy,
                                failure)
                        : new MvccInheritedTable.TransactionStatusOutcomeUnknownException(
                                storageId,
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
            MvccInheritedTable.RecoveryRequired existingRecovery = recoveryRequired.get();
            if (existingRecovery != null) {
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(
                        new MvccInheritedTable.CommittedTransactionRecoveryRequiredException(
                                storageId,
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
                pageVolumeStateStore.publishStagedChanges(
                        staged, failurePoints.storageHook());
                anyMaterialized |= !staged.empty();
                totalChangedRows += prepared.changedRowCount();
            } catch (RuntimeException | Error publicationFailure) {
                MvccInheritedTable.RecoveryRequired unhealthy = markRecoveryRequired("committed page publication", publicationFailure);
                outcomes.set(index, MvccCommitCoordinator.Outcome.failure(
                        new MvccInheritedTable.CommittedTransactionRecoveryRequiredException(
                                storageId,
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
                hitIndexPublication(stagedChanges.getLast());
                orderedIndexPublicationHook.run();
                indexMaintenance.rebuildFromCommittedRows();
            } catch (RuntimeException | Error failure) {
                MvccInheritedTable.RecoveryRequired unhealthy = markRecoveryRequired("ordered-index publication", failure);
                sharedFailure = new MvccInheritedTable.CommittedTransactionRecoveryRequiredException(
                        storageId,
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
            maintenanceLifecycle.afterCommit(totalChangedRows);
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

    void abort(DelosStorageTransaction transaction) {
        tableAccess.durable(DelosStorageBackupCoordinator.Mutation.TRANSACTION_ABORT, () -> {
            MvccInheritedHandles.Transaction handle =
                    MvccInheritedTable.nativeTransactionHandle(transaction);
            try {
                transactions.abort(handle.nativeTransaction());
                handle.clearWriteIntents();
            } finally {
                activeTransactions.remove(handle);
            }
        });
    }

    private void requireNoOtherActiveProviderWriter(
            MvccInheritedHandles.Transaction handle,
            long rowId,
            String operation,
            Set<MvccInheritedHandles.Transaction> ignoredWriters) {
        MvccInheritedWriteConflictPolicy.requireNoOtherActiveProviderWriter(
                activeTransactions, handle, rowId, operation, ignoredWriters);
    }

    private MvccPreparedCommit prepareCommit(MvccInheritedHandles.Transaction handle) {
        CommitInput input = tableAccess.read(() -> {
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
        tableAccess.durable(
                DelosStorageBackupCoordinator.Mutation.PREPARATION_FAILURE_CLEANUP,
                () -> {
                    if (!activeTransactions.contains(handle)) {
                        return;
                    }
                    abortIfActive(handle.nativeTransaction(), failure);
                    handle.clearWriteIntents();
                    activeTransactions.remove(handle);
                });
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

    private List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changedRows(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents) {
        List<PageVolumeMvccStateStore.PersistedChange<StoreDataValue[]>> changes = new ArrayList<>();
        for (MvccInheritedHandles.Transaction.WriteIntent intent : intents) {
            if (intent.delete()) {
                changes.add(PageVolumeMvccStateStore.PersistedChange.delete(intent.rowId()));
            } else {
                changes.add(PageVolumeMvccStateStore.PersistedChange.upsert(
                        intent.rowId(),
                        MvccInheritedTable.cloneRowUnchecked(intent.row())));
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

    private void abortIfActive(MvccTransaction transaction, Throwable failure) {
        try {
            transactions.abort(transaction);
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
    }

    int lastCommittedChangedRowCount() {
        return lastCommittedChangedRowCount;
    }

    int lastCommittedWriteIntentCount() {
        return lastCommittedWriteIntentCount;
    }

    List<String> lastCommittedWriteIntentPayloadSummaries() {
        return lastCommittedWriteIntentPayloadSummaries;
    }

    int activeCommitRequests() {
        return commitMetrics.activeTableRequests();
    }

    int durabilityEnrollmentCount() {
        return durabilityCoordinator.currentEnrollmentCountForTesting();
    }

    void setOrderedIndexPublicationHook(Runnable hook) {
        orderedIndexPublicationHook = Objects.requireNonNull(hook, "hook");
    }

    boolean recoveryRequired() {
        return recoveryRequired.get() != null;
    }

    String recoveryRequiredSummary() {
        MvccInheritedTable.RecoveryRequired unhealthy = recoveryRequired.get();
        return unhealthy == null ? "" : unhealthy.stage() + ": " + unhealthy.failureSummary();
    }

    private MvccInheritedTable.RecoveryRequired markRecoveryRequired(String stage, Throwable failure) {
        MvccInheritedTable.RecoveryRequired candidate = new MvccInheritedTable.RecoveryRequired(
                Objects.requireNonNull(stage, "stage"),
                MvccInheritedTable.failureSummary(Objects.requireNonNull(failure, "failure")));
        recoveryRequired.compareAndSet(null, candidate);
        return recoveryRequired.get();
    }

    @Override
    public void close() {
        durabilityCoordinator.close();
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

    private record CommitInput(
            List<MvccInheritedHandles.Transaction.WriteIntent> intents,
            long writeIntentRevision,
            int writeIntentCount) {
        private CommitInput {
            intents = List.copyOf(intents);
        }
    }

}
