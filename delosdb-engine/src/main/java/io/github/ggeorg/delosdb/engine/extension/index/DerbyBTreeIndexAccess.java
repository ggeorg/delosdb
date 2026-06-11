package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexAccess;
import io.github.ggeorg.delosdb.spi.index.IndexAccessException;
import io.github.ggeorg.delosdb.spi.index.IndexCursor;
import io.github.ggeorg.delosdb.spi.index.IndexKey;
import io.github.ggeorg.delosdb.spi.index.IndexLookup;
import io.github.ggeorg.delosdb.spi.index.IndexOpenRequest;
import io.github.ggeorg.delosdb.spi.index.RowReference;

import java.util.Objects;

/**
 * Internal first-release adapter from the built-in {@code btree} provider to the
 * provider-neutral {@link IndexAccess} SPI.
 *
 * <p>This class is deliberately structural. It proves that the Derby-compatible
 * B-tree provider can be represented as an {@code IndexAccess} without exposing
 * Derby internals through the public SPI. Derby's existing conglomerate, scan,
 * transaction, locking, and recovery paths remain authoritative for first
 * release compatibility.</p>
 *
 * <p>Provider-owned mutation and cursor operations are intentionally unsupported
 * until DelosDB introduces an executor/storage bridge. This avoids creating a
 * second physical indexing path while the first release target remains 100%
 * Derby-compatible behavior on a modern Java 21 modular build.</p>
 */
@InternalApi
final class DerbyBTreeIndexAccess implements IndexAccess {
    private static final String ESTIMATED_ROW_COUNT_PROPERTY = "estimatedRowCount";

    private final IndexOpenRequest request;

    DerbyBTreeIndexAccess(IndexOpenRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        String providerName = request.metadata().providerName();
        if (!BuiltInExtensions.BTREE_INDEX_PROVIDER.equals(providerName)) {
            throw new IllegalArgumentException("DerbyBTreeIndexAccess can only adapt the built-in btree provider: " + providerName);
        }
    }

    IndexOpenRequest request() {
        return request;
    }

    @Override
    public void insert(IndexKey key, RowReference rowReference) throws IndexAccessException {
        throw unsupported("insert");
    }

    @Override
    public void delete(IndexKey key, RowReference rowReference) throws IndexAccessException {
        throw unsupported("delete");
    }

    @Override
    public IndexCursor find(IndexLookup lookup) throws IndexAccessException {
        throw unsupported("find");
    }

    @Override
    public void truncate() throws IndexAccessException {
        throw unsupported("truncate");
    }

    @Override
    public void drop() throws IndexAccessException {
        throw unsupported("drop");
    }

    @Override
    public long estimatedRowCount() {
        String value = request.properties().get(ESTIMATED_ROW_COUNT_PROPERTY);
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static IndexAccessException unsupported(String operation) {
        return new IndexAccessException("Built-in btree IndexAccess " + operation
                + " is structural only; Derby's existing B-tree path remains authoritative in the first DelosDB release");
    }
}
