package io.github.ggeorg.delosdb.engine.extension.cost;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.Optional;

/**
 * Internal prototype for DelosDB cost-model providers.
 *
 * <p>This is deliberately not a public SPI yet. The purpose of this proof is
 * to validate the correct Derby integration point first: an adapter around
 * {@code StoreCostController}, not another optimizer-side reflection hook.</p>
 */
@InternalApi
public interface CostModelProvider {
    String name();

    Optional<CostModelEstimate> estimateScanCost(CostModelRequest request);
}
