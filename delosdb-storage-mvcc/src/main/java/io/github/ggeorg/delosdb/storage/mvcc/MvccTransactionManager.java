package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Small transaction table for the experimental MVCC kernel.
 *
 * <p>This class intentionally models only the MVCC vocabulary needed before any
 * Derby store integration: transaction id allocation, commit sequence
 * assignment, active-transaction snapshots, and oldest active snapshot
 * discovery. It is synchronized to keep the prototype deterministic and safe
 * for focused tests.</p>
 */
public final class MvccTransactionManager implements MvccTransactionCatalog {
    private long nextTransactionId = 1L;
    private long currentCommitSequence = 0L;
    private final Map<MvccTransactionId, TransactionState> transactions = new HashMap<>();

    public synchronized MvccTransaction begin() {
        MvccTransactionId id = new MvccTransactionId(nextTransactionId++);
        transactions.put(id, new TransactionState(
                MvccTransactionStatus.ACTIVE,
                MvccCommitSequence.NONE,
                new MvccCommitSequence(currentCommitSequence)));
        return new MvccTransaction(id);
    }

    public synchronized MvccSnapshot snapshot(MvccTransaction transaction) {
        requireActive(transaction);
        Set<MvccTransactionId> active = new LinkedHashSet<>();
        for (Map.Entry<MvccTransactionId, TransactionState> entry : transactions.entrySet()) {
            if (entry.getValue().status == MvccTransactionStatus.ACTIVE) {
                active.add(entry.getKey());
            }
        }
        active.remove(transaction.id());
        return new MvccSnapshot(transaction.id(), new MvccCommitSequence(currentCommitSequence), active);
    }

    public synchronized MvccCommitSequence commit(MvccTransaction transaction) {
        TransactionState state = requireActive(transaction);
        MvccCommitSequence sequence = new MvccCommitSequence(++currentCommitSequence);
        state.status = MvccTransactionStatus.COMMITTED;
        state.commitSequence = sequence;
        return sequence;
    }

    public synchronized void abort(MvccTransaction transaction) {
        TransactionState state = requireActive(transaction);
        state.status = MvccTransactionStatus.ABORTED;
    }

    public synchronized MvccCommitSequence newestCommitSequence() {
        return new MvccCommitSequence(currentCommitSequence);
    }

    public synchronized int activeTransactionCount() {
        int active = 0;
        for (TransactionState state : transactions.values()) {
            if (state.status == MvccTransactionStatus.ACTIVE) {
                active++;
            }
        }
        return active;
    }

    /**
     * Returns the oldest snapshot high-water mark still held by an active
     * transaction. If no transaction is active, cleanup may use the current
     * commit sequence as the safe high-water mark.
     */
    public synchronized MvccCommitSequence oldestActiveVisibleThrough() {
        MvccCommitSequence oldest = null;
        for (TransactionState state : transactions.values()) {
            if (state.status == MvccTransactionStatus.ACTIVE) {
                if (oldest == null || state.snapshotSequence.compareTo(oldest) < 0) {
                    oldest = state.snapshotSequence;
                }
            }
        }
        return oldest == null ? newestCommitSequence() : oldest;
    }

    @Override
    public synchronized MvccTransactionStatus statusOf(MvccTransactionId transactionId) {
        if (transactionId == null || transactionId.isNone()) {
            return MvccTransactionStatus.ABORTED;
        }
        TransactionState state = transactions.get(transactionId);
        if (state == null) {
            throw new IllegalArgumentException("unknown transaction id: " + transactionId);
        }
        return state.status;
    }

    @Override
    public synchronized Optional<MvccCommitSequence> commitSequenceOf(MvccTransactionId transactionId) {
        TransactionState state = transactions.get(transactionId);
        if (state == null || state.status != MvccTransactionStatus.COMMITTED) {
            return Optional.empty();
        }
        return Optional.of(state.commitSequence);
    }

    private TransactionState requireActive(MvccTransaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
        TransactionState state = transactions.get(transaction.id());
        if (state == null) {
            throw new IllegalArgumentException("unknown transaction id: " + transaction.id());
        }
        if (state.status != MvccTransactionStatus.ACTIVE) {
            throw new IllegalStateException("transaction is not active: " + transaction.id() + " status=" + state.status);
        }
        return state;
    }

    private static final class TransactionState {
        private MvccTransactionStatus status;
        private MvccCommitSequence commitSequence;
        private final MvccCommitSequence snapshotSequence;

        private TransactionState(
                MvccTransactionStatus status,
                MvccCommitSequence commitSequence,
                MvccCommitSequence snapshotSequence) {
            this.status = status;
            this.commitSequence = commitSequence;
            this.snapshotSequence = snapshotSequence;
        }
    }
}
