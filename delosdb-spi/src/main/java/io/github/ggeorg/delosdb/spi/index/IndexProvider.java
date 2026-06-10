package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Optional;

/**
 * Experimental DelosDB contract for index provider implementations.
 *
 * <p>This interface is deliberately small. It establishes provider identity,
 * capability reporting, and optional cost estimation without exposing Derby
 * internals such as {@code AccessFactory}, {@code Conglomerate},
 * {@code ScanController}, {@code StoreCostController}, or optimizer classes.</p>
 *
 * <p>Runtime open/create/drop hooks will be introduced only after the registry,
 * catalog, and optimizer bridge designs are proven.</p>
 */
@ExperimentalSpi("Initial index provider contract; no runtime adapter or optimizer bridge yet.")
public interface IndexProvider {
    /**
     * Returns the stable provider name used by DelosDB metadata.
     *
     * <p>The built-in provider is {@code btree}. Future provider names should be
     * lowercase, registered explicitly, and stable across releases of the same
     * provider family.</p>
     */
    String name();

    /**
     * Describes the capabilities this provider offers for a specific index.
     */
    IndexCapabilities capabilities(IndexMetadata metadata);

    /**
     * Optionally estimates the cost of using this provider for an access path.
     *
     * <p>An empty result means DelosDB should fall back to its default costing
     * path. Implementations must not depend on Derby optimizer classes here.</p>
     */
    Optional<IndexCostEstimate> estimateCost(IndexCostRequest request);
}
