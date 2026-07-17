package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitDecision;
import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageRawDecisionCommitCoordinator;

/**
 * Database-scoped commit authority for transactions spanning MVCC tables.
 *
 * <p>MVCC-only transactions force the existing database status record as the
 * authoritative decision. Mixed heap/MVCC transactions durably stage every
 * MVCC payload, then let Derby raw store commit a transactional decision marker.
 * After raw-store recovery, marker presence is authoritative and incomplete
 * MVCC publication is repeatable.</p>
 */
final class MvccDatabaseCommitCoordinator implements DelosStorageRawDecisionCommitCoordinator {
    private static final long FIRST_DATABASE_TRANSACTION_ID = 1L << 62;

    private final Path databaseDirectory;
    private final MvccFailurePointRegistry failurePoints;
    private final MvccTransactionStatusStore decisionStore;
    private final ReentrantReadWriteLock coordinationLock = new ReentrantReadWriteLock();
    private final Lock singleTableLock = coordinationLock.readLock();
    private final Lock multiTableLock = coordinationLock.writeLock();
    private long nextTransactionId = FIRST_DATABASE_TRANSACTION_ID;
    private long newestCommitSequence;
    private volatile Runnable afterDecisionHook = () -> { };

    MvccDatabaseCommitCoordinator(Path databaseDirectory) {
        this(databaseDirectory, MvccFailurePointRegistry.disabled(databaseDirectory));
    }

    MvccDatabaseCommitCoordinator(
            Path databaseDirectory,
            MvccFailurePointRegistry failurePoints) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.failurePoints = Objects.requireNonNull(failurePoints, "failurePoints");
        Path statusFile = PageVolumeMvccPaths.databaseTransactionStatusFile(this.databaseDirectory);
        decisionStore = statusFile == null
                ? MvccTransactionStatusStore.disabled()
                : MvccTransactionStatusStore.open(statusFile);
        recoverCoordinatorState();
    }

    @Override
    public void commit(List<Participant> participants) {
        List<DatabaseParticipant> required = requireParticipants(participants);
        if (required.size() == 1) {
            singleTableLock.lock();
            try {
                DatabaseParticipant participant = required.getFirst();
                participant.table().commitSingleParticipant(participant.transaction());
            } finally {
                singleTableLock.unlock();
            }
            return;
        }

        multiTableLock.lock();
        try {
            commitMultiple(required);
        } finally {
            multiTableLock.unlock();
        }
    }

    @Override
    public PreparedCommit prepareForRawStoreDecision(List<Participant> participants) {
        List<DatabaseParticipant> required = requireParticipants(participants);
        multiTableLock.lock();
        DelosStorageBackupCoordinator.Guard backupGuard = null;
        boolean tablesLocked = false;
        try {
            DelosStorageBackupCoordinator backupCoordinator = requireSharedBackupCoordinator(required);
            backupGuard = backupCoordinator.enterDurableMutation(
                    DelosStorageBackupCoordinator.Mutation.COMMIT_PUBLICATION);
            lockTables(required);
            tablesLocked = true;
            PreparedState prepared = prepareParticipantsLocked(required);
            return new RawStorePreparedCommit(required, prepared, backupGuard);
        } catch (RuntimeException | Error failure) {
            Throwable releaseFailure = failure;
            if (tablesLocked) {
                try {
                    unlockTables(required);
                } catch (RuntimeException | Error tableUnlockFailure) {
                    releaseFailure = appendFailure(releaseFailure, tableUnlockFailure);
                }
            }
            if (backupGuard != null) {
                try {
                    backupGuard.close();
                } catch (RuntimeException | Error guardFailure) {
                    releaseFailure = appendFailure(releaseFailure, guardFailure);
                }
            }
            try {
                multiTableLock.unlock();
            } catch (RuntimeException | Error lockFailure) {
                releaseFailure = appendFailure(releaseFailure, lockFailure);
            }
            rethrow(releaseFailure);
            throw new AssertionError("unreachable");
        }
    }

    Map<MvccTransactionId, MvccTransactionStatusRecord> recoveredStatuses() {
        return recoveredDecisionStatuses();
    }

    MvccCommitSequence newestRecoveredCommitSequence() {
        return new MvccCommitSequence(newestCommitSequence);
    }

    void afterDecisionHookForTesting(Runnable hook) {
        afterDecisionHook = Objects.requireNonNull(hook, "hook");
    }

    private void commitMultiple(List<DatabaseParticipant> participants) {
        DelosStorageBackupCoordinator backupCoordinator = requireSharedBackupCoordinator(participants);
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(
                             DelosStorageBackupCoordinator.Mutation.COMMIT_PUBLICATION)) {
            lockTables(participants);
            try {
                PreparedState prepared = prepareParticipantsLocked(participants);
                boolean committedDecision = false;
                Throwable failure = null;
                try {
                    hit(
                            MvccFailurePointRegistry.Point.BEFORE_TRANSACTION_DECISION_FORCE,
                            prepared,
                            0);
                    forceCommittedDecision(prepared.databaseTransactionId(), prepared.commitSequence());
                    committedDecision = true;
                    newestCommitSequence = Math.max(
                            newestCommitSequence, prepared.commitSequence().value());
                    hit(
                            MvccFailurePointRegistry.Point.AFTER_TRANSACTION_DECISION_FORCE,
                            prepared,
                            0);
                    afterDecisionHook.run();
                } catch (RuntimeException | Error commitFailure) {
                    failure = commitFailure;
                    MvccTransactionStatusRecord recovered = recoveredDecisionStatuses()
                            .get(prepared.databaseTransactionId());
                    committedDecision = recovered != null
                            && recovered.status() == MvccTransactionStatus.COMMITTED;
                    if (committedDecision) {
                        newestCommitSequence = Math.max(
                                newestCommitSequence, recovered.commitSequence().value());
                    }
                }

                if (!committedDecision) {
                    Throwable aborted = abortBeforeDecision(prepared, failure);
                    rethrow(Objects.requireNonNullElseGet(
                            aborted,
                            () -> new IllegalStateException("MVCC database commit was aborted")));
                    return;
                }

                Throwable publicationFailure = publishPrepared(prepared, failure);
                if (publicationFailure != null) {
                    throw recoveryRequired(prepared, publicationFailure);
                }
            } finally {
                unlockTables(participants);
            }
        }
    }

    private PreparedState prepareParticipantsLocked(List<DatabaseParticipant> participants) {
        MvccTransactionId databaseTransactionId = new MvccTransactionId(nextTransactionId++);
        MvccCommitSequence commitSequence = nextCommitSequence(participants);
        List<MvccInheritedTable.DatabasePreparedCommit> prepared = new ArrayList<>();
        try {
            decisionStore.recordActive(databaseTransactionId);
            for (int index = 0; index < participants.size(); index++) {
                DatabaseParticipant participant = participants.get(index);
                prepared.add(participant.table().prepareDatabaseCommit(
                        participant.transaction(),
                        databaseTransactionId,
                        commitSequence,
                        index + 1,
                        participants.size()));
                failurePoints.hit(
                        MvccFailurePointRegistry.Point.AFTER_PARTICIPANT_PREPARE,
                        MvccFailurePointRegistry.Context.transaction(
                                databaseTransactionId.value(),
                                commitSequence.value(),
                                index + 1,
                                participants.size()));
            }
            return new PreparedState(
                    participants, databaseTransactionId, commitSequence, List.copyOf(prepared));
        } catch (RuntimeException | Error preparationFailure) {
            PreparedState partial = new PreparedState(
                    participants, databaseTransactionId, commitSequence, List.copyOf(prepared));
            Throwable aborted = abortBeforeDecision(partial, preparationFailure);
            rethrow(Objects.requireNonNullElse(aborted, preparationFailure));
            throw new AssertionError("unreachable");
        }
    }

    private DelosStorageBackupCoordinator requireSharedBackupCoordinator(
            List<DatabaseParticipant> participants) {
        DelosStorageBackupCoordinator backupCoordinator = participants.getFirst()
                .table().databaseBackupCoordinator();
        for (DatabaseParticipant participant : participants) {
            if (participant.table().databaseBackupCoordinator() != backupCoordinator) {
                throw new IllegalArgumentException(
                        "coordinated MVCC participants must share one database backup coordinator");
            }
        }
        return backupCoordinator;
    }

    private void forceCommittedDecision(
            MvccTransactionId databaseTransactionId,
            MvccCommitSequence commitSequence) {
        try {
            decisionStore.recordCommitted(databaseTransactionId, commitSequence);
        } catch (RuntimeException | Error forceFailure) {
            MvccTransactionStatusRecord recovered = recoveredDecisionStatuses().get(databaseTransactionId);
            if (recovered != null && recovered.status() == MvccTransactionStatus.COMMITTED) {
                return;
            }
            throw forceFailure;
        }
    }

    private Throwable publishPrepared(PreparedState prepared, Throwable originalFailure) {
        Throwable failure = originalFailure;
        try {
            hit(
                    MvccFailurePointRegistry.Point.BEFORE_FIRST_PARTICIPANT_PUBLICATION,
                    prepared,
                    0);
        } catch (RuntimeException | Error injectedFailure) {
            return appendFailure(failure, injectedFailure);
        }
        for (int index = 0; index < prepared.prepared().size(); index++) {
            MvccInheritedTable.DatabasePreparedCommit participant = prepared.prepared().get(index);
            try {
                participant.table().publishDatabaseCommit(participant);
            } catch (RuntimeException | Error memberFailure) {
                failure = appendFailure(failure, memberFailure);
            }
            if (index + 1 < prepared.prepared().size()) {
                try {
                    hit(
                            MvccFailurePointRegistry.Point.BETWEEN_PARTICIPANT_PUBLICATIONS,
                            prepared,
                            index + 1);
                } catch (RuntimeException | Error injectedFailure) {
                    return appendFailure(failure, injectedFailure);
                }
            }
        }
        return failure;
    }

    private Throwable abortBeforeDecision(PreparedState state, Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = state.prepared().size() - 1; index >= 0; index--) {
            try {
                state.prepared().get(index).table().abortDatabaseCommit(state.prepared().get(index));
            } catch (RuntimeException | Error abortFailure) {
                failure = appendFailure(failure, abortFailure);
            }
        }
        for (int index = state.prepared().size(); index < state.participants().size(); index++) {
            DatabaseParticipant participant = state.participants().get(index);
            try {
                participant.table().abortDatabaseTransaction(participant.transaction());
            } catch (RuntimeException | Error abortFailure) {
                failure = appendFailure(failure, abortFailure);
            }
        }
        try {
            decisionStore.recordAborted(state.databaseTransactionId());
        } catch (RuntimeException | Error statusFailure) {
            failure = appendFailure(failure, statusFailure);
        }
        return failure;
    }

    private MvccCommitSequence nextCommitSequence(List<DatabaseParticipant> participants) {
        long sequence = newestCommitSequence;
        for (DatabaseParticipant participant : participants) {
            sequence = Math.max(sequence, participant.table().newestCommitSequenceForDatabaseCommit());
        }
        return new MvccCommitSequence(sequence + 1L);
    }

    private List<DatabaseParticipant> requireParticipants(List<Participant> participants) {
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("coordinated commit requires at least one participant");
        }
        Map<MvccInheritedTable, DatabaseParticipant> unique = new LinkedHashMap<>();
        for (Participant participant : participants) {
            if (!(participant.table() instanceof MvccInheritedTable table)) {
                throw new IllegalArgumentException(
                        "MVCC database coordinator received a non-MVCC table participant");
            }
            if (table.commitCoordinator() != this) {
                throw new IllegalArgumentException(
                        "MVCC table belongs to a different database commit coordinator");
            }
            DatabaseParticipant previous = unique.putIfAbsent(
                    table, new DatabaseParticipant(table, participant.transaction()));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate MVCC table participant: " + table.databaseCommitIdentity());
            }
        }
        return unique.values().stream()
                .sorted(Comparator.comparing(participant -> participant.table().databaseCommitIdentity()))
                .toList();
    }

    private Map<MvccTransactionId, MvccTransactionStatusRecord> recoveredDecisionStatuses() {
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses =
                new LinkedHashMap<>(decisionStore.recoverStatuses());
        for (DelosDatabaseCommitDecision decision
                : DelosDatabaseCommitDecision.recoverCommitted(databaseDirectory).values()) {
            MvccTransactionId transactionId = new MvccTransactionId(decision.transactionId());
            MvccCommitSequence commitSequence = new MvccCommitSequence(decision.commitSequence());
            MvccTransactionStatusRecord committed =
                    MvccTransactionStatusRecord.committed(transactionId, commitSequence);
            MvccTransactionStatusRecord existing = statuses.get(transactionId);
            if (existing != null
                    && existing.status() == MvccTransactionStatus.COMMITTED
                    && !existing.commitSequence().equals(commitSequence)) {
                throw new IllegalStateException(
                        "Conflicting commit sequence for database transaction " + transactionId);
            }
            // Raw-store recovery is authoritative for mixed transactions.
            statuses.put(transactionId, committed);
        }
        return Map.copyOf(statuses);
    }

    private void recoverCoordinatorState() {
        for (MvccTransactionStatusRecord record : recoveredDecisionStatuses().values()) {
            nextTransactionId = Math.max(nextTransactionId, record.transactionId().value() + 1L);
            if (record.status() == MvccTransactionStatus.COMMITTED) {
                newestCommitSequence = Math.max(
                        newestCommitSequence, record.commitSequence().value());
            }
        }
    }

    private static void lockTables(List<DatabaseParticipant> participants) {
        int locked = 0;
        try {
            for (; locked < participants.size(); locked++) {
                participants.get(locked).table().lockForDatabaseCommit();
            }
        } catch (RuntimeException | Error failure) {
            for (int index = locked - 1; index >= 0; index--) {
                participants.get(index).table().unlockForDatabaseCommit();
            }
            throw failure;
        }
    }

    private static void unlockTables(List<DatabaseParticipant> participants) {
        for (int index = participants.size() - 1; index >= 0; index--) {
            participants.get(index).table().unlockForDatabaseCommit();
        }
    }

    private DatabaseCommitRecoveryRequiredException recoveryRequired(
            PreparedState prepared,
            Throwable cause) {
        return new DatabaseCommitRecoveryRequiredException(
                prepared.databaseTransactionId().value(),
                prepared.commitSequence().value(),
                cause);
    }

    private void hit(
            MvccFailurePointRegistry.Point point,
            PreparedState prepared,
            int participantIndex) {
        failurePoints.hit(
                point,
                MvccFailurePointRegistry.Context.transaction(
                        prepared.databaseTransactionId().value(),
                        prepared.commitSequence().value(),
                        participantIndex,
                        prepared.participants().size()));
    }

    private static Throwable appendFailure(Throwable existing, Throwable additional) {
        if (existing == null) {
            return additional;
        }
        if (existing != additional) {
            existing.addSuppressed(additional);
        }
        return existing;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        throw new IllegalStateException(failure);
    }

    private record DatabaseParticipant(
            MvccInheritedTable table,
            org.apache.derby.iapi.store.types.DelosStorageTransaction transaction) {
    }

    private record PreparedState(
            List<DatabaseParticipant> participants,
            MvccTransactionId databaseTransactionId,
            MvccCommitSequence commitSequence,
            List<MvccInheritedTable.DatabasePreparedCommit> prepared) {
        private PreparedState {
            participants = List.copyOf(participants);
            prepared = List.copyOf(prepared);
        }
    }

    private final class RawStorePreparedCommit implements PreparedCommit {
        private final List<DatabaseParticipant> participants;
        private final PreparedState prepared;
        private final DelosStorageBackupCoordinator.Guard backupGuard;
        private boolean terminal;

        private RawStorePreparedCommit(
                List<DatabaseParticipant> participants,
                PreparedState prepared,
                DelosStorageBackupCoordinator.Guard backupGuard) {
            this.participants = List.copyOf(participants);
            this.prepared = prepared;
            this.backupGuard = backupGuard;
        }

        @Override
        public DelosDatabaseCommitDecision decision() {
            return new DelosDatabaseCommitDecision(
                    prepared.databaseTransactionId().value(),
                    prepared.commitSequence().value());
        }

        @Override
        public synchronized void beforeRawStoreCommit() {
            requireOpen();
            hit(
                    MvccFailurePointRegistry.Point.BEFORE_DERBY_RAW_STORE_COMMIT,
                    prepared,
                    0);
        }

        @Override
        public synchronized void afterRawStoreCommit() {
            requireOpen();
            try {
                hit(
                        MvccFailurePointRegistry.Point.AFTER_DERBY_RAW_STORE_COMMIT,
                        prepared,
                        0);
            } catch (RuntimeException | Error injectedFailure) {
                terminal = true;
                Throwable failure = releaseOwnership(injectedFailure);
                throw recoveryRequired(prepared, failure);
            }
        }

        @Override
        public synchronized void publishAfterRawStoreCommit() {
            requireOpen();
            Throwable failure = null;
            try {
                try {
                    // Secondary mirror. The committed raw-store decision is authoritative.
                    decisionStore.recordCommitted(
                            prepared.databaseTransactionId(), prepared.commitSequence());
                } catch (RuntimeException | Error statusFailure) {
                    failure = statusFailure;
                }
                newestCommitSequence = Math.max(
                        newestCommitSequence, prepared.commitSequence().value());
                try {
                    afterDecisionHook.run();
                } catch (RuntimeException | Error hookFailure) {
                    failure = appendFailure(failure, hookFailure);
                }
                failure = publishPrepared(prepared, failure);
            } finally {
                terminal = true;
                failure = releaseOwnership(failure);
            }
            if (failure != null) {
                throw recoveryRequired(prepared, failure);
            }
        }

        @Override
        public synchronized void releaseForRecovery() {
            requireOpen();
            Throwable failure = null;
            try {
                // The durable raw-store outcome is authoritative. Leave the
                // prepared participant records untouched for reopen recovery.
            } finally {
                terminal = true;
                failure = releaseOwnership(failure);
            }
            if (failure != null) {
                rethrow(failure);
            }
        }

        @Override
        public synchronized void abortBeforeRawStoreCommit() {
            requireOpen();
            Throwable failure = null;
            try {
                failure = abortBeforeDecision(prepared, null);
            } finally {
                terminal = true;
                failure = releaseOwnership(failure);
            }
            if (failure != null) {
                rethrow(failure);
            }
        }

        private void requireOpen() {
            if (terminal) {
                throw new IllegalStateException("database commit preparation is already terminal");
            }
        }

        private Throwable releaseOwnership(Throwable failure) {
            try {
                unlockTables(participants);
            } catch (RuntimeException | Error tableUnlockFailure) {
                failure = appendFailure(failure, tableUnlockFailure);
            }
            try {
                backupGuard.close();
            } catch (RuntimeException | Error guardFailure) {
                failure = appendFailure(failure, guardFailure);
            }
            try {
                multiTableLock.unlock();
            } catch (RuntimeException | Error lockFailure) {
                failure = appendFailure(failure, lockFailure);
            }
            return failure;
        }
    }

    static final class DatabaseCommitRecoveryRequiredException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private DatabaseCommitRecoveryRequiredException(
                long transactionId,
                long commitSequence,
                Throwable cause) {
            super("MVCC database transaction " + transactionId + " committed at sequence "
                    + commitSequence + ", but participant publication requires close and reopen; "
                    + "the transaction must not be retried", cause);
        }
    }
}
