package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Provider-owned transaction coordinator for experimental versioned storage.
 *
 * <p>The provider owns the MVCC transaction objects. The DelosDB engine may map
 * JDBC commit/rollback to these callbacks, but the provider remains isolated
 * from Derby heap, lock, and log internals.</p>
 */
public interface VersionedTransactionCoordinator {
    TxContext begin();

    void commit(TxContext context);

    void abort(TxContext context);
}
