package io.github.ggeorg.delosdb.engine.extension.index;

/**
 * Provider-neutral diagnostic record for an optimizer/index-provider cost probe.
 *
 * <p>This type intentionally contains only strings, numbers, and booleans so
 * diagnostics can be observed without exposing Derby optimizer or store objects
 * as public SPI.</p>
 */
public record IndexProviderCostProbe(
        String mode,
        String providerName,
        String indexName,
        long tableRowCount,
        long derbyEstimatedRows,
        double derbyCost,
        boolean estimatePresent,
        double providerStartupCost,
        double providerTotalCost,
        long providerEstimatedRows,
        boolean consumed,
        String explanation
) {
    public IndexProviderCostProbe withConsumed(boolean consumed) {
        return new IndexProviderCostProbe(
                mode,
                providerName,
                indexName,
                tableRowCount,
                derbyEstimatedRows,
                derbyCost,
                estimatePresent,
                providerStartupCost,
                providerTotalCost,
                providerEstimatedRows,
                consumed,
                explanation);
    }

    /**
     * Returns a stable, provider-neutral diagnostic line for smoke tests and
     * future planner tracing. Keep this free of Derby implementation objects so
     * the diagnostic surface remains safe to expose outside the optimizer.
     */
    public String diagnosticSummary() {
        return "IndexProviderCostProbe{"
                + "mode=" + mode
                + ", provider=" + providerName
                + ", index=" + indexName
                + ", tableRows=" + tableRowCount
                + ", derbyRows=" + derbyEstimatedRows
                + ", derbyCost=" + derbyCost
                + ", estimatePresent=" + estimatePresent
                + ", providerStartupCost=" + providerStartupCost
                + ", providerTotalCost=" + providerTotalCost
                + ", providerRows=" + providerEstimatedRows
                + ", consumed=" + consumed
                + ", explanation=" + sanitize(explanation)
                + "}";
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static IndexProviderCostProbe unavailable(
            String mode,
            String providerName,
            String indexName,
            long tableRowCount,
            long derbyEstimatedRows,
            double derbyCost,
            String explanation) {
        return new IndexProviderCostProbe(
                mode,
                providerName,
                indexName,
                tableRowCount,
                derbyEstimatedRows,
                derbyCost,
                false,
                0.0d,
                0.0d,
                0L,
                false,
                explanation == null ? "" : explanation);
    }
}
