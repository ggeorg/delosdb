package io.github.ggeorg.delosdb.spi.storage.versioned;

/** Lightweight statistics exposed by versioned storage for diagnostics/costing. */
public record VersionedTableStats(
        long logicalRowCount,
        long visibleRowCount,
        long physicalVersionCount,
        long deadVersionEstimate
) {
    public VersionedTableStats {
        if (logicalRowCount < 0 || visibleRowCount < 0 || physicalVersionCount < 0 || deadVersionEstimate < 0) {
            throw new IllegalArgumentException("versioned table statistics must be non-negative");
        }
    }
}
