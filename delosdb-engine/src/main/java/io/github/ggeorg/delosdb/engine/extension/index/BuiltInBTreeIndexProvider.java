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
 * Internal identity adapter for Derby's built-in B-tree index implementation.
 *
 * <p>This class connects the built-in {@code btree} descriptor to the public
 * experimental {@link IndexProvider} contract without exposing Derby access
 * methods, conglomerates, scan controllers, or optimizer classes.</p>
 *
 * <p>The baseline cost estimate is provider-neutral and deterministic. Derby
 * remains authoritative unless the optimizer bridge is explicitly enabled.</p>
 *
 * <p>The {@link #openAccess(IndexOpenRequest)} hook returns a structural B-tree
 * access adapter. It does not replace Derby's physical B-tree execution path;
 * first-release behavior remains Derby-compatible.</p>
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
        long estimatedRows = request.estimatedQualifiedRowCount() > 0
                ? request.estimatedQualifiedRowCount()
                : Math.max(1L, request.tableRowCount());
        double startupCost = Math.max(1.0d, log2(Math.max(2L, request.tableRowCount())));
        double rowVisitCost = Math.max(1.0d, estimatedRows);
        return Optional.of(new IndexCostEstimate(
                startupCost,
                startupCost + rowVisitCost,
                estimatedRows,
                "built-in btree baseline estimate"));
    }


    @Override
    public Optional<IndexAccess> openAccess(IndexOpenRequest request) throws IndexAccessException {
        Objects.requireNonNull(request, "request");
        if (!name().equals(request.metadata().providerName())) {
            return Optional.empty();
        }
        return Optional.of(new DerbyBTreeIndexAccess(request));
    }

    private static double log2(long value) {
        return Math.log(value) / Math.log(2.0d);
    }
}
