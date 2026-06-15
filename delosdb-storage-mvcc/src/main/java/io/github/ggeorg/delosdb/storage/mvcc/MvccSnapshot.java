package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Set;
import java.util.TreeSet;

/**
 * Stable MVCC view captured for a transaction or statement.
 *
 * <p>The snapshot sees transactions committed at or before
 * {@link #visibleThrough()} and never sees transactions that were active when
 * the snapshot was taken, even if they commit later. The owning transaction's
 * own writes remain visible to itself.</p>
 */
public record MvccSnapshot(
        MvccTransactionId owner,
        MvccCommitSequence visibleThrough,
        Set<MvccTransactionId> activeAtCapture
) {
    public MvccSnapshot {
        if (owner == null || owner.isNone()) {
            throw new IllegalArgumentException("snapshot owner must be a real transaction id");
        }
        if (visibleThrough == null) {
            throw new IllegalArgumentException("visibleThrough must not be null");
        }
        if (activeAtCapture == null) {
            throw new IllegalArgumentException("activeAtCapture must not be null");
        }
        activeAtCapture = Set.copyOf(new TreeSet<>(activeAtCapture));
    }

    public boolean isTransactionVisible(MvccTransactionId transactionId, MvccTransactionCatalog catalog) {
        if (transactionId == null || transactionId.isNone()) {
            return false;
        }
        if (transactionId.equals(owner)) {
            return true;
        }
        if (activeAtCapture.contains(transactionId)) {
            return false;
        }
        if (catalog.statusOf(transactionId) != MvccTransactionStatus.COMMITTED) {
            return false;
        }
        return catalog.commitSequenceOf(transactionId)
                .map(sequence -> sequence.isAtOrBefore(visibleThrough))
                .orElse(false);
    }
}
