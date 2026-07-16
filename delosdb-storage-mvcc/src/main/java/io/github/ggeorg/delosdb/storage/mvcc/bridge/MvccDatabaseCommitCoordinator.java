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

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageCommitCoordinator;

/**
 * Database-scoped commit authority for transactions spanning MVCC tables.
 *
 * <p>All participant payloads are durably staged before one database decision
 * is forced. Publication may then be repeated during table recovery because
 * each staged payload names the same database transaction id.</p>
 */
final class MvccDatabaseCommitCoordinator implements DelosStorageCommitCoordinator {
    private static final long FIRST_DATABASE_TRANSACTION_ID = 1L << 62;

    private final MvccTransactionStatusStore decisionStore;
    private final ReentrantReadWriteLock coordinationLock = new ReentrantReadWriteLock();
    private final Lock singleTableLock = coordinationLock.readLock();
    private final Lock multiTableLock = coordinationLock.writeLock();
    private long nextTransactionId = FIRST_DATABASE_TRANSACTION_ID;
    private long newestCommitSequence;
    private volatile Runnable afterDecisionHook = () -> { };

    MvccDatabaseCommitCoordinator(Path databaseDirectory) {
        Path statusFile = PageVolumeMvccPaths.databaseTransactionStatusFile(databaseDirectory);
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

    Map<MvccTransactionId, MvccTransactionStatusRecord> recoveredStatuses() {
        return decisionStore.recoverStatuses();
    }

    MvccCommitSequence newestRecoveredCommitSequence() {
        return new MvccCommitSequence(newestCommitSequence);
    }

    void afterDecisionHookForTesting(Runnable hook) {
        afterDecisionHook = Objects.requireNonNull(hook, "hook");
    }

    private void commitMultiple(List<DatabaseParticipant> participants) {
        DelosStorageBackupCoordinator backupCoordinator = participants.getFirst()
                .table().databaseBackupCoordinator();
        for (DatabaseParticipant participant : participants) {
            if (participant.table().databaseBackupCoordinator() != backupCoordinator) {
                throw new IllegalArgumentException(
                        "coordinated MVCC participants must share one database backup coordinator");
            }
        }

        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(
                             DelosStorageBackupCoordinator.Mutation.COMMIT_PUBLICATION)) {
            lockTables(participants);
            try {
                commitMultipleLocked(participants);
            } finally {
                unlockTables(participants);
            }
        }
    }

    private void commitMultipleLocked(List<DatabaseParticipant> participants) {
        MvccTransactionId databaseTransactionId = new MvccTransactionId(nextTransactionId++);
        MvccCommitSequence commitSequence = nextCommitSequence(participants);
        List<MvccInheritedTable.DatabasePreparedCommit> prepared = new ArrayList<>();
        boolean committedDecision = false;
        Throwable failure = null;

        try {
            decisionStore.recordActive(databaseTransactionId);
            for (DatabaseParticipant participant : participants) {
                prepared.add(participant.table().prepareDatabaseCommit(
                        participant.transaction(), databaseTransactionId, commitSequence));
            }
            forceCommittedDecision(databaseTransactionId, commitSequence);
            committedDecision = true;
            newestCommitSequence = Math.max(newestCommitSequence, commitSequence.value());
            afterDecisionHook.run();
        } catch (RuntimeException | Error commitFailure) {
            failure = commitFailure;
            if (!committedDecision) {
                MvccTransactionStatusRecord recovered =
                        decisionStore.recoverStatuses().get(databaseTransactionId);
                committedDecision = recovered != null
                        && recovered.status() == MvccTransactionStatus.COMMITTED;
                if (committedDecision) {
                    newestCommitSequence = Math.max(
                            newestCommitSequence, recovered.commitSequence().value());
                }
            }
        }

        if (!committedDecision) {
            Throwable abortedFailure = abortBeforeDecision(
                    participants, prepared, databaseTransactionId, failure);
            rethrow(Objects.requireNonNullElseGet(
                    abortedFailure, () -> new IllegalStateException("MVCC database commit was aborted")));
            return;
        }

        Throwable publicationFailure = failure;
        for (MvccInheritedTable.DatabasePreparedCommit participant : prepared) {
            try {
                participant.table().publishDatabaseCommit(participant);
            } catch (RuntimeException | Error memberFailure) {
                publicationFailure = appendFailure(publicationFailure, memberFailure);
            }
        }
        if (publicationFailure != null) {
            throw new DatabaseCommitRecoveryRequiredException(
                    databaseTransactionId.value(), commitSequence.value(), publicationFailure);
        }
    }

    private void forceCommittedDecision(
            MvccTransactionId databaseTransactionId,
            MvccCommitSequence commitSequence) {
        try {
            decisionStore.recordCommitted(databaseTransactionId, commitSequence);
        } catch (RuntimeException | Error forceFailure) {
            MvccTransactionStatusRecord recovered =
                    decisionStore.recoverStatuses().get(databaseTransactionId);
            if (recovered != null && recovered.status() == MvccTransactionStatus.COMMITTED) {
                return;
            }
            throw forceFailure;
        }
    }

    private Throwable abortBeforeDecision(
            List<DatabaseParticipant> participants,
            List<MvccInheritedTable.DatabasePreparedCommit> prepared,
            MvccTransactionId databaseTransactionId,
            Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = prepared.size() - 1; index >= 0; index--) {
            try {
                prepared.get(index).table().abortDatabaseCommit(prepared.get(index));
            } catch (RuntimeException | Error abortFailure) {
                failure = appendFailure(failure, abortFailure);
            }
        }
        for (int index = prepared.size(); index < participants.size(); index++) {
            DatabaseParticipant participant = participants.get(index);
            try {
                participant.table().abortDatabaseTransaction(participant.transaction());
            } catch (RuntimeException | Error abortFailure) {
                failure = appendFailure(failure, abortFailure);
            }
        }
        try {
            decisionStore.recordAborted(databaseTransactionId);
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

    private void recoverCoordinatorState() {
        for (MvccTransactionStatusRecord record : decisionStore.recoverStatuses().values()) {
            nextTransactionId = Math.max(nextTransactionId, record.transactionId().value() + 1L);
            if (record.status() == MvccTransactionStatus.COMMITTED) {
                newestCommitSequence = Math.max(
                        newestCommitSequence, record.commitSequence().value());
            }
        }
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
