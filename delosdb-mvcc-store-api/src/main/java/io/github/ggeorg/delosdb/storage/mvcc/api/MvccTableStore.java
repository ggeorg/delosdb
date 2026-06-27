package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Adapter-neutral entry point for a native MVCC table implementation. */
public interface MvccTableStore<T> {
    MvccTableSession<T> openSession(MvccTransactionContext context);
}
