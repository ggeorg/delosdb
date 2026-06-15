package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;
import java.util.Optional;

/** A physical row version in the experimental MVCC kernel. */
public final class MvccVersion<V> {
    private final V value;
    private final MvccTransactionId createdBy;
    private MvccTransactionId deletedBy;

    public MvccVersion(V value, MvccTransactionId createdBy) {
        if (createdBy == null || createdBy.isNone()) {
            throw new IllegalArgumentException("createdBy must be a real transaction id");
        }
        this.value = Objects.requireNonNull(value, "value");
        this.createdBy = createdBy;
        this.deletedBy = MvccTransactionId.NONE;
    }

    public V value() {
        return value;
    }

    public MvccTransactionId createdBy() {
        return createdBy;
    }

    public Optional<MvccTransactionId> deletedBy() {
        return deletedBy.isNone() ? Optional.empty() : Optional.of(deletedBy);
    }

    public void markDeletedBy(MvccTransactionId deletingTransaction, MvccTransactionCatalog catalog) {
        if (deletingTransaction == null || deletingTransaction.isNone()) {
            throw new IllegalArgumentException("deleting transaction must be a real transaction id");
        }
        if (!deletedBy.isNone()
                && !deletedBy.equals(deletingTransaction)
                && catalog.statusOf(deletedBy) != MvccTransactionStatus.ABORTED) {
            throw new MvccWriteConflictException("version is already deleted by " + deletedBy);
        }
        deletedBy = deletingTransaction;
    }

    boolean wasCreatedByAbortedTransaction(MvccTransactionCatalog catalog) {
        return catalog.statusOf(createdBy) == MvccTransactionStatus.ABORTED;
    }

    boolean isDeletedBeforeOrAt(MvccCommitSequence highWaterMark, MvccTransactionCatalog catalog) {
        if (deletedBy.isNone()) {
            return false;
        }
        if (catalog.statusOf(deletedBy) != MvccTransactionStatus.COMMITTED) {
            return false;
        }
        return catalog.commitSequenceOf(deletedBy)
                .map(sequence -> sequence.isAtOrBefore(highWaterMark))
                .orElse(false);
    }
}
