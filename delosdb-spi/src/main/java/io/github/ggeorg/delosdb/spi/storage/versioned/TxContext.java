package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Transaction handle passed from DelosDB core to a versioned storage provider.
 *
 * <p>The id is intentionally primitive so providers can keep their own internal
 * transaction objects private. The current view captures the snapshot that
 * write operations should use for conflict checks.</p>
 */
public interface TxContext {
    long transactionId();

    TxView currentView();
}
