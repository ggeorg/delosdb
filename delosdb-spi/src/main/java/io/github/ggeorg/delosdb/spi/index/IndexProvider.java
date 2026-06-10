package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Objects;
import java.util.Optional;

/**
 * Experimental DelosDB contract for index provider implementations.
 *
 * <p>This interface is deliberately small. It establishes provider identity,
 * capability reporting, optional cost estimation, and an initial physical
 * access hook without exposing Derby internals such as {@code AccessFactory},
 * {@code Conglomerate}, {@code ScanController}, {@code StoreCostController},
 * or optimizer classes.</p>
 *
 * <p>The physical access hook is optional in v0 so existing providers can remain
 * metadata/cost-only while DelosDB builds the Derby adapter layer.</p>
 */
@ExperimentalSpi("Initial index provider contract; physical access hook is optional while adapters mature.")
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

    /**
     * Optionally opens physical access for an index owned by this provider.
     *
     * <p>The default keeps v0 providers metadata/cost-only. Providers that return
     * an {@link IndexAccess} must translate engine-specific state through
     * provider-neutral {@link IndexKey}, {@link RowReference}, and
     * {@link IndexLookup} values rather than exposing Derby internals.</p>
     */
    default Optional<IndexAccess> openAccess(IndexOpenRequest request) throws IndexAccessException {
        Objects.requireNonNull(request, "request");
        return Optional.empty();
    }
}
