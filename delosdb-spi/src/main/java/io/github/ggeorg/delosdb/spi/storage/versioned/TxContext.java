package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Transaction handle passed from DelosDB core to a versioned storage provider.
 *
 * <p>The id is intentionally primitive so providers can keep their own internal
 * transaction objects private. The current view captures the snapshot that
 * write operations should use for conflict checks.</p>
 */
public interface TxContext {
    /**
     * Sentinel returned by providers that do not expose a statement command
     * sequence through this experimental SPI.
     */
    long UNKNOWN_STATEMENT_COMMAND_SEQUENCE = -1L;

    long transactionId();

    TxView currentView();

    /**
     * Returns the provider-local statement command sequence for this context
     * when available. This is a diagnostic hook for the experimental MVCC SQL
     * bridge; providers that do not model statement commands may keep the
     * default unknown value.
     */
    default long statementCommandSequence() {
        return UNKNOWN_STATEMENT_COMMAND_SEQUENCE;
    }
}
