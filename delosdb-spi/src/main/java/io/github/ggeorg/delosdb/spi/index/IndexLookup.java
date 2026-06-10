package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Provider-neutral lookup request for a physical index cursor.
 *
 * <p>The lookup model is deliberately small: equality, range, and full scan.
 * Predicate translation remains an internal DelosDB/Derby bridge concern.</p>
 */
@ExperimentalSpi("Initial index lookup shape; predicate translation is not yet finalized.")
public record IndexLookup(
        IndexKey lowerBound,
        boolean lowerInclusive,
        IndexKey upperBound,
        boolean upperInclusive,
        boolean equalityLookup,
        boolean reverse
) {
    public IndexLookup {
        if (equalityLookup) {
            if (lowerBound == null || upperBound == null) {
                throw new IllegalArgumentException("equality lookup requires both bounds");
            }
            if (!lowerBound.equals(upperBound)) {
                throw new IllegalArgumentException("equality lookup bounds must be the same key");
            }
            lowerInclusive = true;
            upperInclusive = true;
        }
    }

    public static IndexLookup equality(IndexKey key) {
        return new IndexLookup(key, true, key, true, true, false);
    }

    public static IndexLookup range(IndexKey lowerBound, boolean lowerInclusive, IndexKey upperBound, boolean upperInclusive) {
        return new IndexLookup(lowerBound, lowerInclusive, upperBound, upperInclusive, false, false);
    }

    public static IndexLookup fullScan() {
        return new IndexLookup(null, true, null, true, false, false);
    }

    public IndexLookup reversed() {
        return new IndexLookup(lowerBound, lowerInclusive, upperBound, upperInclusive, equalityLookup, true);
    }
}
