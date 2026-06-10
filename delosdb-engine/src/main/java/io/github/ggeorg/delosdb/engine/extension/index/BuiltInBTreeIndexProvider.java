package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexCostEstimate;
import io.github.ggeorg.delosdb.spi.index.IndexCostRequest;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.Optional;

/**
 * Internal identity adapter for Derby's built-in B-tree index implementation.
 *
 * <p>This class connects the built-in {@code btree} descriptor to the public
 * experimental {@link IndexProvider} contract without exposing Derby access
 * methods, conglomerates, scan controllers, or optimizer classes.</p>
 *
 * <p>The provider can produce a provider-neutral baseline estimate. Derby's
 * existing optimizer remains authoritative until a reviewed optimizer island
 * explicitly consumes provider estimates.</p>
 */
@InternalApi
final class BuiltInBTreeIndexProvider implements IndexProvider {
    static final BuiltInBTreeIndexProvider INSTANCE = new BuiltInBTreeIndexProvider();

    private BuiltInBTreeIndexProvider() {
    }

    @Override
    public String name() {
        return BuiltInExtensions.BTREE_INDEX_PROVIDER;
    }

    @Override
    public IndexCapabilities capabilities(IndexMetadata metadata) {
        return IndexCapabilities.btree();
    }

    @Override
    public Optional<IndexCostEstimate> estimateCost(IndexCostRequest request) {
        long estimatedRows = boundedEstimatedRows(request);
        double startupCost = startupCostFor(request);
        double totalCost = startupCost
                + Math.max(1.0d, estimatedRows)
                + Math.max(1, request.metadata().keyColumns().size());
        return Optional.of(new IndexCostEstimate(
                startupCost,
                totalCost,
                estimatedRows,
                "built-in btree provider estimate; Derby cost remains authoritative"));
    }

    private static long boundedEstimatedRows(IndexCostRequest request) {
        long tableRows = request.tableRowCount();
        long qualifiedRows = request.estimatedQualifiedRowCount();
        if (tableRows == 0L) {
            return qualifiedRows;
        }
        if (qualifiedRows == 0L) {
            return request.equalityPredicate() ? 1L : tableRows;
        }
        return Math.min(tableRows, qualifiedRows);
    }

    private static double startupCostFor(IndexCostRequest request) {
        if (request.equalityPredicate()) {
            return 1.0d;
        }
        if (request.rangePredicate()) {
            return 2.0d;
        }
        return 4.0d;
    }
}
