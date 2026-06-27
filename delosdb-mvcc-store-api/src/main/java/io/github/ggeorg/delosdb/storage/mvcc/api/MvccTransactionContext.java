package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Adapter-neutral transaction context supplied to MVCC table sessions. */
public interface MvccTransactionContext {
    MvccTransactionId transactionId();

    MvccSnapshot snapshot();
}
