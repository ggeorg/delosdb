package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
    private final MvccTransactionId compactedTransactionIdThrough;
    private final MvccCommitSequence compactedCommittedVisibleThrough;
    private final Set<MvccTransactionId> compactedAbortedTransactions;
    private final Map<MvccTransactionId, MvccCommitSequence> compactedCommittedSequences;

    MvccCapturedVisibility(
            MvccSnapshot snapshot,
            Map<MvccTransactionId, CapturedTransaction> transactions) {
        this(snapshot, transactions, MvccTransactionId.NONE, MvccCommitSequence.NONE, Set.of(), Map.of());
    }

    MvccCapturedVisibility(
            MvccSnapshot snapshot,
            Map<MvccTransactionId, CapturedTransaction> transactions,
            MvccTransactionId compactedTransactionIdThrough,
            MvccCommitSequence compactedCommittedVisibleThrough,
            Set<MvccTransactionId> compactedAbortedTransactions,
            Map<MvccTransactionId, MvccCommitSequence> compactedCommittedSequences) {
        Objects.requireNonNull(snapshot, "snapshot");
        this.owner = snapshot.owner();
        this.visibleThrough = snapshot.visibleThrough();
        this.ownerVisibleThroughCommand = snapshot.visibleThroughCommand();
        this.transactions = Map.copyOf(new TreeMap<>(Objects.requireNonNull(transactions, "transactions")));
        this.compactedTransactionIdThrough = Objects.requireNonNull(
                compactedTransactionIdThrough,
                "compactedTransactionIdThrough");
        this.compactedCommittedVisibleThrough = Objects.requireNonNull(
                compactedCommittedVisibleThrough,
                "compactedCommittedVisibleThrough");
        this.compactedAbortedTransactions = Set.copyOf(new TreeSet<>(Objects.requireNonNull(
                compactedAbortedTransactions,
                "compactedAbortedTransactions")));
        this.compactedCommittedSequences = Map.copyOf(new TreeMap<>(Objects.requireNonNull(
                compactedCommittedSequences,
                "compactedCommittedSequences")));
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

    public MvccTransactionId compactedTransactionIdThrough() {
        return compactedTransactionIdThrough;
    }

    @Override
    public MvccTransactionStatus statusOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return MvccTransactionStatus.ABORTED;
        }
        CapturedTransaction transaction = transactions.get(transactionId);
        if (transaction != null) {
            return transaction.status();
        }
        if (transactionId.compareTo(compactedTransactionIdThrough) <= 0) {
            return compactedAbortedTransactions.contains(transactionId)
                    ? MvccTransactionStatus.ABORTED
                    : MvccTransactionStatus.COMMITTED;
        }
        return MvccTransactionStatus.ACTIVE;
    }

    @Override
    public Optional<MvccCommitSequence> commitSequenceOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return Optional.empty();
        }
        CapturedTransaction transaction = transactions.get(transactionId);
        if (transaction != null && transaction.status() == MvccTransactionStatus.COMMITTED) {
            return Optional.of(transaction.commitSequence());
        }
        if (transactionId.compareTo(compactedTransactionIdThrough) <= 0
                && !compactedAbortedTransactions.contains(transactionId)) {
            MvccCommitSequence compactedExact = compactedCommittedSequences.get(transactionId);
            return Optional.of(compactedExact == null ? compactedCommittedVisibleThrough : compactedExact);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "MvccCapturedVisibility[owner=" + owner
                + ", visibleThrough=" + visibleThrough
                + ", ownerVisibleThroughCommand=" + ownerVisibleThroughCommand
                + ", knownTransactionCount=" + transactions.size()
                + ", compactedTransactionIdThrough=" + compactedTransactionIdThrough + ']';
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
