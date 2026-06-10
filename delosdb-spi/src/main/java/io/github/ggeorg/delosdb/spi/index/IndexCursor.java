package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Provider-neutral cursor over row references returned by a physical index.
 */
@ExperimentalSpi("Initial physical index cursor contract; row materialization remains engine-adapted.")
public interface IndexCursor extends AutoCloseable {
    /**
     * Advances to the next matching row reference.
     */
    boolean next() throws IndexAccessException;

    /**
     * Returns the row reference for the current cursor position.
     */
    RowReference rowReference() throws IndexAccessException;

    /**
     * Releases provider resources associated with this cursor.
     */
    @Override
    default void close() throws IndexAccessException {
        // Default no-op for in-memory/stateless cursors.
    }
}
