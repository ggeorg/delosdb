package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Provider-owned transaction coordinator for experimental versioned storage.
 *
 * <p>This is intentionally not Derby transaction integration. It gives the
 * first SQL table-scan bridge a provider-local transaction lifecycle while the
 * Derby transaction-to-MVCC mapping remains a later phase.</p>
 */
public interface VersionedTransactionCoordinator {
    TxContext begin();

    void commit(TxContext context);

    void abort(TxContext context);
}
