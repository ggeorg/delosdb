package io.github.ggeorg.delosdb.storage.mvcc;

/** Lightweight transaction handle for the experimental MVCC kernel. */
public record MvccTransaction(MvccTransactionId id) {
    public MvccTransaction {
        if (id == null || id.isNone()) {
            throw new IllegalArgumentException("transaction must have a real id");
        }
    }
}
