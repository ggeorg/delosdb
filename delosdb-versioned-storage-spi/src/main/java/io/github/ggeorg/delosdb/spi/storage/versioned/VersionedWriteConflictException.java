package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Provider-neutral signal for an unsafe write/write conflict in a versioned
 * storage engine.
 *
 * <p>The engine maps this exception to a transaction-conflict SQLState while
 * keeping provider-specific MVCC implementation classes out of the SQL bridge.</p>
 */
public class VersionedWriteConflictException extends RuntimeException {
    public VersionedWriteConflictException(String message) {
        super(message);
    }

    public VersionedWriteConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
