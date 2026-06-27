package io.github.ggeorg.delosdb.storage.mvcc.api;

/** MVCC transaction identifier independent of a specific adapter. */
public record MvccTransactionId(long value) {
    public MvccTransactionId {
        if (value <= 0) {
            throw new IllegalArgumentException("transaction id must be positive: " + value);
        }
    }
}
