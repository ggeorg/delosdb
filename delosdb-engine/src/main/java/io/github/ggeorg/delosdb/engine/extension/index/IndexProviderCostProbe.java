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
