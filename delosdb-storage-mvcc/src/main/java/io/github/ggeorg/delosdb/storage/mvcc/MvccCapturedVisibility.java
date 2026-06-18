package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable transaction-status view captured for MVCC visibility checks.
 *
 * <p>A51 keeps the existing {@link MvccSnapshot} semantics but lets readers use
 * a frozen transaction catalog instead of consulting the live transaction
 * manager for every row/version decision. Transactions that were not known at
 * capture time are treated as still in progress, which makes versions written
 * after the captured visibility boundary invisible instead of forcing a live
 * catalog lookup.</p>
 */
public final class MvccCapturedVisibility implements MvccTransactionCatalog {
    private final MvccTransactionId owner;
    private final MvccCommitSequence visibleThrough;
    private final MvccCommandSequence ownerVisibleThroughCommand;
    private final Map<MvccTransactionId, CapturedTransaction> transactions;

    MvccCapturedVisibility(
            MvccSnapshot snapshot,
            Map<MvccTransactionId, CapturedTransaction> transactions) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.owner = snapshot.owner();
        this.visibleThrough = snapshot.visibleThrough();
        this.ownerVisibleThroughCommand = snapshot.visibleThroughCommand();
        this.transactions = Map.copyOf(new TreeMap<>(Objects.requireNonNull(transactions, "transactions")));
    }

    public MvccTransactionId owner() {
        return owner;
    }

    public MvccCommitSequence visibleThrough() {
        return visibleThrough;
    }

    public MvccCommandSequence ownerVisibleThroughCommand() {
        return ownerVisibleThroughCommand;
    }

    public int knownTransactionCount() {
        return transactions.size();
    }

    @Override
    public MvccTransactionStatus statusOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return MvccTransactionStatus.ABORTED;
        }
        CapturedTransaction transaction = transactions.get(transactionId);
        if (transaction == null) {
            return MvccTransactionStatus.ACTIVE;
        }
        return transaction.status();
    }

    @Override
    public Optional<MvccCommitSequence> commitSequenceOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return Optional.empty();
        }
        CapturedTransaction transaction = transactions.get(transactionId);
        if (transaction == null || transaction.status() != MvccTransactionStatus.COMMITTED) {
            return Optional.empty();
        }
        return Optional.of(transaction.commitSequence());
    }

    @Override
    public String toString() {
        return "MvccCapturedVisibility[owner=" + owner
                + ", visibleThrough=" + visibleThrough
                + ", ownerVisibleThroughCommand=" + ownerVisibleThroughCommand
                + ", knownTransactionCount=" + transactions.size() + ']';
    }

    record CapturedTransaction(
            MvccTransactionStatus status,
            MvccCommitSequence commitSequence) {
        CapturedTransaction {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(commitSequence, "commitSequence");
        }
    }
}
