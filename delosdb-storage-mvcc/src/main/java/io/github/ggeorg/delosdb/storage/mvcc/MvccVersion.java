package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;
import java.util.Optional;

/** A physical row version in the experimental MVCC kernel. */
public final class MvccVersion<V> {
    private final V value;
    private final MvccTransactionId createdBy;
    private final MvccCommandSequence createdAtCommand;
    private MvccTransactionId deletedBy;
    private MvccCommandSequence deletedAtCommand;

    public MvccVersion(V value, MvccTransactionId createdBy) {
        this(value, createdBy, MvccCommandSequence.FIRST);
    }

    public MvccVersion(V value, MvccTransactionId createdBy, MvccCommandSequence createdAtCommand) {
        if (createdBy == null || createdBy.isNone()) {
            throw new IllegalArgumentException("createdBy must be a real transaction id");
        }
        this.value = Objects.requireNonNull(value, "value");
        this.createdBy = createdBy;
        this.createdAtCommand = Objects.requireNonNull(createdAtCommand, "createdAtCommand");
        this.deletedBy = MvccTransactionId.NONE;
        this.deletedAtCommand = MvccCommandSequence.FIRST;
    }

    public V value() {
        return value;
    }

    public MvccTransactionId createdBy() {
        return createdBy;
    }

    public MvccCommandSequence createdAtCommand() {
        return createdAtCommand;
    }

    public Optional<MvccTransactionId> deletedBy() {
        return deletedBy.isNone() ? Optional.empty() : Optional.of(deletedBy);
    }

    public Optional<MvccCommandSequence> deletedAtCommand() {
        return deletedBy.isNone() ? Optional.empty() : Optional.of(deletedAtCommand);
    }

    public void markDeletedBy(MvccTransactionId deletingTransaction, MvccTransactionCatalog catalog) {
        markDeletedBy(deletingTransaction, catalog, MvccCommandSequence.FIRST);
    }

    public void markDeletedBy(
            MvccTransactionId deletingTransaction,
            MvccTransactionCatalog catalog,
            MvccCommandSequence deletedAtCommand) {
        if (deletingTransaction == null || deletingTransaction.isNone()) {
            throw new IllegalArgumentException("deleting transaction must be a real transaction id");
        }
        // Keep this conflict check before the assignment. MvccVersionChain.update()
        // relies on this method being check-before-mutate so an update cannot
        // mark the old version deleted unless it is also allowed to append the
        // replacement version.
        Objects.requireNonNull(deletedAtCommand, "deletedAtCommand");
        if (!deletedBy.isNone()
                && !deletedBy.equals(deletingTransaction)
                && catalog.statusOf(deletedBy) != MvccTransactionStatus.ABORTED) {
            throw new MvccWriteConflictException("version is already deleted by " + deletedBy);
        }
        deletedBy = deletingTransaction;
        this.deletedAtCommand = deletedAtCommand;
    }


    boolean wasCreatedBy(MvccTransactionId transactionId) {
        return createdBy.equals(transactionId);
    }

    boolean wasCreatedAfter(MvccCommandSequence boundary) {
        return createdAtCommand.compareTo(boundary) > 0;
    }

    void clearDeletionByAfter(MvccTransactionId transactionId, MvccCommandSequence boundary) {
        if (deletedBy.equals(transactionId) && deletedAtCommand.compareTo(boundary) > 0) {
            deletedBy = MvccTransactionId.NONE;
            deletedAtCommand = MvccCommandSequence.FIRST;
        }
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

    MvccPrunedVersionMarker prunedVersionMarker(MvccTransactionCatalog catalog) {
        if (wasCreatedByAbortedTransaction(catalog)) {
            return null;
        }
        if (deletedBy.isNone() || catalog.statusOf(deletedBy) != MvccTransactionStatus.COMMITTED) {
            return null;
        }
        return new MvccPrunedVersionMarker(createdBy, createdAtCommand, deletedBy, deletedAtCommand);
    }
}
