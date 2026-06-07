package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Capability summary for an index provider or provider-backed index.
 */
@ExperimentalSpi("Initial index capability model; expected to grow with optimizer integration.")
public record IndexCapabilities(
        boolean supportsEqualityLookup,
        boolean supportsRangeScan,
        boolean supportsOrdering,
        boolean supportsUniqueConstraint,
        boolean supportsNullableKeys
) {
    /**
     * Conservative capabilities for a provider that does not advertise access
     * path support yet.
     */
    public static IndexCapabilities none() {
        return new IndexCapabilities(false, false, false, false, false);
    }

    /**
     * Baseline capabilities for the built-in Derby-backed B-tree provider.
     */
    public static IndexCapabilities btree() {
        return new IndexCapabilities(true, true, true, true, true);
    }
}
