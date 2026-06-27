package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Set;

/** Immutable MVCC visibility snapshot. */
public record MvccSnapshot(long xmin,
                           long xmax,
                           Set<MvccTransactionId> activeTransactions,
                           MvccCommitSequence commitSequence) {
    public MvccSnapshot {
        if (xmin < 0) {
            throw new IllegalArgumentException("xmin must be non-negative: " + xmin);
        }
        if (xmax < xmin) {
            throw new IllegalArgumentException("xmax must be >= xmin: " + xmax + " < " + xmin);
        }
        activeTransactions = Set.copyOf(activeTransactions);
    }
}
