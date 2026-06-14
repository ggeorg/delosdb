package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCostEstimate;
import io.github.ggeorg.delosdb.spi.index.IndexCostRequest;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;
import org.apache.derby.catalog.IndexDescriptor;

import java.util.Objects;
import java.util.Optional;

/**
 * Legacy diagnostic bridge from Derby index descriptors to DelosDB
 * index-provider cost requests.
 *
 * <p>This class prepares provider-neutral {@link IndexCostRequest} instances
 * and invokes {@link IndexProvider#estimateCost(IndexCostRequest)} without
 * exposing Derby optimizer implementation classes to the SPI. It no longer
 * feeds provider cost back into the planner. Native provider cost consumption
 * is handled by {@code CostModelProvider} through Derby's
 * {@code StoreCostController} seam.</p>
 */
@InternalApi
public final class IndexProviderCostBridge {
    private IndexProviderCostBridge() {
    }


    /**
     * Returns an observable provider-cost probe using the built-in registry.
     */
    public static IndexProviderCostProbe builtInCostProbeFor(
            String mode,
            String indexName,
            IndexDescriptor descriptor,
            long tableRowCount,
            long estimatedQualifiedRowCount,
            double derbyCost,
            boolean equalityPredicate,
            boolean rangePredicate,
            boolean orderingRequired) {
        IndexCostRequest request = costRequestFor(
                indexName,
                descriptor,
                tableRowCount,
                estimatedQualifiedRowCount,
                equalityPredicate,
                rangePredicate,
                orderingRequired);
        IndexProvider provider = IndexProviderResolver
                .builtIns()
                .requireEnabled(request.metadata().providerName());
        Optional<IndexCostEstimate> estimate = provider.estimateCost(request);
        if (estimate.isEmpty()) {
            return IndexProviderCostProbe.unavailable(
                    mode,
                    request.metadata().providerName(),
                    request.metadata().indexName(),
                    tableRowCount,
                    estimatedQualifiedRowCount,
                    derbyCost,
                    "provider returned no estimate");
        }
        IndexCostEstimate value = estimate.get();
        return new IndexProviderCostProbe(
                mode,
                request.metadata().providerName(),
                request.metadata().indexName(),
                tableRowCount,
                estimatedQualifiedRowCount,
                derbyCost,
                true,
                value.startupCost(),
                value.totalCost(),
                value.estimatedRows(),
                false,
                value.explanation());
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
                IndexProviderResolver.builtIns(),
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
