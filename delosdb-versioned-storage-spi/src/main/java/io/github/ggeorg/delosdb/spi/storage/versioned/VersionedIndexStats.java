package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Lightweight statistics for a provider-owned versioned index lookup.
 *
 * <p>The candidate count is the number of row identifiers held by the index
 * bucket before MVCC visibility recheck. The visible-match count is the number
 * of rows that remain visible after consulting the authoritative table/version
 * chain. Costing must treat index entries as candidates, not as independently
 * visible rows.</p>
 */
public record VersionedIndexStats(
        long indexedKeyCount,
        long candidateCount,
        long visibleMatchCount,
        long estimatedLookupCost
) {
    public VersionedIndexStats {
        if (indexedKeyCount < 0 || candidateCount < 0 || visibleMatchCount < 0 || estimatedLookupCost < 0) {
            throw new IllegalArgumentException("versioned index statistics must be non-negative");
        }
    }
}
