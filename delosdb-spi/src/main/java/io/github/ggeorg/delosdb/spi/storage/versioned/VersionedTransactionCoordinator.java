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

    /**
     * Returns a transaction context with a fresh statement snapshot while
     * keeping the same provider-local transaction identity.
     *
     * <p>Providers that use transaction-stable snapshots may return the
     * original context. MVCC providers should override this for
     * {@link VersionedIsolationLevel#READ_COMMITTED} semantics, where each
     * statement observes a new committed high-water mark while preserving
     * own-write visibility.</p>
     */
    default TxContext refresh(TxContext context) {
        return context;
    }

    void commit(TxContext context);

    void abort(TxContext context);
}
