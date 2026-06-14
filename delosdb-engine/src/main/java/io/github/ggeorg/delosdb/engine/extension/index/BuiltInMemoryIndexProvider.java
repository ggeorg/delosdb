package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexAccess;
import io.github.ggeorg.delosdb.spi.index.IndexAccessException;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexCostEstimate;
import io.github.ggeorg.delosdb.spi.index.IndexCostRequest;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexOpenRequest;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.Objects;
import java.util.Optional;

/**
 * Internal in-memory IndexProvider used to prove the IndexProvider abstraction is
 * not only a Derby B-tree identity wrapper.
 *
 * <p>This provider is deliberately not SQL DDL-backed. {@code CREATE INDEX USING
 * memory} is rejected until DelosDB has an executor/storage bridge that can
 * route provider-owned index lifecycle operations from real table/index
 * metadata. The provider is still a real SPI implementation: it can open
 * provider-neutral {@link IndexAccess}, mutate entries, perform lookups, and
 * report row-count estimates without exposing Derby internals.</p>
 */
@InternalApi
final class BuiltInMemoryIndexProvider implements IndexProvider {
    static final BuiltInMemoryIndexProvider INSTANCE = new BuiltInMemoryIndexProvider();

    private BuiltInMemoryIndexProvider() {
    }

    @Override
    public String name() {
        return BuiltInExtensions.MEMORY_INDEX_PROVIDER;
    }

    @Override
    public IndexCapabilities capabilities(IndexMetadata metadata) {
        return new IndexCapabilities(
                true,  // equality lookup
                true,  // range scan
                true,  // ordered scan
                false, // unique constraints are not Derby-backed yet
                false  // null-key encoding is not specified yet
        );
    }

    @Override
    public Optional<IndexCostEstimate> estimateCost(IndexCostRequest request) {
        Objects.requireNonNull(request, "request");
        long estimatedRows = request.estimatedQualifiedRowCount() > 0
                ? request.estimatedQualifiedRowCount()
                : Math.max(1L, request.tableRowCount());
        double startupCost = 0.25d;
        double lookupCost = request.equalityPredicate() ? 0.75d : Math.max(1.0d, estimatedRows / 4.0d);
        return Optional.of(new IndexCostEstimate(
                startupCost,
                startupCost + lookupCost,
                estimatedRows,
                "built-in memory index provider estimate"));
    }

    @Override
    public Optional<IndexAccess> openAccess(IndexOpenRequest request) throws IndexAccessException {
        Objects.requireNonNull(request, "request");
        if (!name().equals(request.metadata().providerName())) {
            return Optional.empty();
        }
        return Optional.of(new MemoryIndexAccess(request));
    }
}
