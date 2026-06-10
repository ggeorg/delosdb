package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Provider-neutral physical access contract for an index implementation.
 *
 * <p>This is the first DelosDB SPI shape that describes real index lifecycle
 * operations. It is intentionally independent from Derby internals such as
 * {@code Conglomerate}, {@code TransactionController}, {@code ScanController},
 * {@code RowLocation}, {@code DataValueDescriptor}, optimizer nodes, and store
 * cost controllers.</p>
 */
@ExperimentalSpi("Initial physical index access contract; not yet wired to Derby execution.")
public interface IndexAccess extends AutoCloseable {
    /**
     * Inserts an index key pointing to a provider-neutral row reference.
     */
    void insert(IndexKey key, RowReference rowReference) throws IndexAccessException;

    /**
     * Removes an index key pointing to a provider-neutral row reference.
     */
    void delete(IndexKey key, RowReference rowReference) throws IndexAccessException;

    /**
     * Updates an index entry. Providers may override this with a more efficient
     * implementation when their storage format supports it.
     */
    default void update(
            IndexKey oldKey,
            RowReference oldRowReference,
            IndexKey newKey,
            RowReference newRowReference
    ) throws IndexAccessException {
        delete(oldKey, oldRowReference);
        insert(newKey, newRowReference);
    }

    /**
     * Opens a provider cursor for the supplied lookup description.
     */
    IndexCursor find(IndexLookup lookup) throws IndexAccessException;

    /**
     * Removes all entries from this physical index.
     */
    void truncate() throws IndexAccessException;

    /**
     * Drops the physical index state owned by this access object.
     */
    void drop() throws IndexAccessException;

    /**
     * Returns a best-effort row-count estimate for diagnostics and costing.
     */
    long estimatedRowCount() throws IndexAccessException;

    /**
     * Releases provider resources associated with this access object.
     */
    @Override
    default void close() throws IndexAccessException {
        // Default no-op for stateless adapters.
    }
}
