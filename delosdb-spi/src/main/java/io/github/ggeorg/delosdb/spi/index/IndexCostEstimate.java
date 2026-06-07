package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Objects;

/**
 * Provider-neutral cost estimate returned by an index provider.
 */
@ExperimentalSpi("Initial cost estimate shape; subject to optimizer bridge feedback.")
public record IndexCostEstimate(
        double startupCost,
        double totalCost,
        long estimatedRows,
        String explanation
) {
    public IndexCostEstimate {
        requireFiniteNonNegative(startupCost, "startupCost");
        requireFiniteNonNegative(totalCost, "totalCost");
        if (totalCost < startupCost) {
            throw new IllegalArgumentException("totalCost must be greater than or equal to startupCost");
        }
        if (estimatedRows < 0) {
            throw new IllegalArgumentException("estimatedRows must not be negative");
        }
        explanation = Objects.requireNonNullElse(explanation, "");
    }

    public static IndexCostEstimate of(double startupCost, double totalCost, long estimatedRows) {
        return new IndexCostEstimate(startupCost, totalCost, estimatedRows, "");
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
