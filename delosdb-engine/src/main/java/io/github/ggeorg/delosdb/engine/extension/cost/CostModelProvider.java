package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.Optional;

/**
 * Internal DelosDB cost provider shape.
 *
 * <p>This is not public SPI yet. It deliberately models only the data that
 * DelosDB can safely adapt into Derby's native {@code StoreCostController}
 * path.</p>
 */
public interface CostModelProvider {
    String name();

    /**
     * Derby access-method factory id handled by this provider.
     *
     * <p>Today this is still tied to inherited Derby factory ids: heap is 0
     * and B-tree is 1. Keeping it on the provider avoids hardcoded provider
     * selection inside the store-cost bridge.</p>
     */
    int accessMethodFactoryId();

    Optional<CostModelEstimate> estimateScanCost(CostModelRequest request);
}
