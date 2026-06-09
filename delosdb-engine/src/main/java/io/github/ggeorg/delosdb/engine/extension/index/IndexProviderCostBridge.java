package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCostEstimate;
import io.github.ggeorg.delosdb.spi.index.IndexCostRequest;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;
import org.apache.derby.catalog.IndexDescriptor;

import java.util.Objects;
import java.util.Optional;

/**
 * Internal bridge from Derby index descriptors to DelosDB index-provider cost
 * requests.
 *
 * <p>This class prepares provider-neutral {@link IndexCostRequest} instances
 * and invokes {@link IndexProvider#estimateCost(IndexCostRequest)} without
 * changing Derby optimizer decisions. An empty result means the existing Derby
 * cost path remains authoritative.</p>
 */
@InternalApi
public final class IndexProviderCostBridge {
    private IndexProviderCostBridge() {
    }

    /**
     * Requests an optional cost estimate using the built-in provider registry.
     */
    public static Optional<IndexCostEstimate> builtInCostEstimateFor(
            String indexName,
            IndexDescriptor descriptor,
            long tableRowCount,
            long estimatedQualifiedRowCount,
            boolean equalityPredicate,
            boolean rangePredicate,
            boolean orderingRequired) {
        return estimateCostFor(
                IndexProviderResolver.builtIns(BuiltInExtensions.newRegistryWithBuiltIns()),
                indexName,
                descriptor,
                tableRowCount,
                estimatedQualifiedRowCount,
                equalityPredicate,
                rangePredicate,
                orderingRequired);
    }

    /**
     * Requests an optional cost estimate through the supplied provider resolver.
     */
    public static Optional<IndexCostEstimate> estimateCostFor(
            IndexProviderResolver resolver,
            String indexName,
            IndexDescriptor descriptor,
            long tableRowCount,
            long estimatedQualifiedRowCount,
            boolean equalityPredicate,
            boolean rangePredicate,
            boolean orderingRequired) {
        Objects.requireNonNull(resolver, "resolver");
        IndexCostRequest request = costRequestFor(
                indexName,
                descriptor,
                tableRowCount,
                estimatedQualifiedRowCount,
                equalityPredicate,
                rangePredicate,
                orderingRequired);
        IndexProvider provider = resolver.requireEnabled(request.metadata().providerName());
        return provider.estimateCost(request);
    }

    /**
     * Builds provider-neutral cost input from Derby descriptor metadata.
     */
    public static IndexCostRequest costRequestFor(
            String indexName,
            IndexDescriptor descriptor,
            long tableRowCount,
            long estimatedQualifiedRowCount,
            boolean equalityPredicate,
            boolean rangePredicate,
            boolean orderingRequired) {
        IndexMetadata metadata = IndexMetadataBridge.from(indexName, descriptor);
        return new IndexCostRequest(
                metadata,
                tableRowCount,
                estimatedQualifiedRowCount,
                equalityPredicate,
                rangePredicate,
                orderingRequired);
    }
}
