package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/** Durable status entry for one MVCC transaction. */
public record MvccTransactionStatusRecord(
        MvccTransactionId transactionId,
        MvccTransactionStatus status,
        MvccCommitSequence commitSequence) {
    public MvccTransactionStatusRecord {
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        status = Objects.requireNonNull(status, "status");
        commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
        if (transactionId.isNone()) {
            throw new IllegalArgumentException("transaction id must be present");
        }
        if (status == MvccTransactionStatus.COMMITTED && commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException("committed transaction status must carry a commit sequence");
        }
        if (status != MvccTransactionStatus.COMMITTED && !commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException(status + " transaction status must not carry a commit sequence");
        }
    }

    public MvccTransactionOutcome outcome() {
        return MvccTransactionOutcome.fromStatus(status);
    }

    public static MvccTransactionStatusRecord active(MvccTransactionId transactionId) {
        return new MvccTransactionStatusRecord(
                transactionId,
                MvccTransactionStatus.ACTIVE,
                MvccCommitSequence.NONE);
    }

    public static MvccTransactionStatusRecord committed(
            MvccTransactionId transactionId,
            MvccCommitSequence commitSequence) {
        return new MvccTransactionStatusRecord(
                transactionId,
                MvccTransactionStatus.COMMITTED,
                commitSequence);
    }

    public static MvccTransactionStatusRecord aborted(MvccTransactionId transactionId) {
        return new MvccTransactionStatusRecord(
                transactionId,
                MvccTransactionStatus.ABORTED,
                MvccCommitSequence.NONE);
    }

    public static MvccTransactionStatusRecord recoveryPending(MvccTransactionId transactionId) {
        return new MvccTransactionStatusRecord(
                transactionId,
                MvccTransactionStatus.RECOVERY_PENDING,
                MvccCommitSequence.NONE);
    }
}
