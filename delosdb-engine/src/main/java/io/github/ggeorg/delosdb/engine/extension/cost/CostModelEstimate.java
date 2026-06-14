package io.github.ggeorg.delosdb.engine.extension.cost;

/**
 * Provider-neutral replacement estimate for Derby store-cost output.
 */
public record CostModelEstimate(
        double startupCost,
        double totalCost,
        long estimatedRows,
        String explanation
) {
    public boolean safeToConsume() {
        return Double.isFinite(startupCost)
                && startupCost >= 0.0d
                && Double.isFinite(totalCost)
                && totalCost > 0.0d
                && totalCost >= startupCost
                && estimatedRows >= 0L;
    }
}
