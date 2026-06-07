package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Objects;

/**
 * Provider-neutral request for estimating whether an index should be considered
 * for a query access path.
 */
@ExperimentalSpi("Initial cost request shape; avoids Derby optimizer implementation classes.")
public record IndexCostRequest(
        IndexMetadata metadata,
        long tableRowCount,
        long estimatedQualifiedRowCount,
        boolean equalityPredicate,
        boolean rangePredicate,
        boolean orderingRequired
) {
    public IndexCostRequest {
        Objects.requireNonNull(metadata, "metadata");
        requireNonNegative(tableRowCount, "tableRowCount");
        requireNonNegative(estimatedQualifiedRowCount, "estimatedQualifiedRowCount");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
