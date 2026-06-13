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
     * Returns true only when the optimizer can safely replace Derby's native
     * cost with the provider estimate. Missing, zero, negative, non-finite, or
     * internally inconsistent estimates remain diagnostic-only and fall back to
     * Derby costing.
     */
    public boolean canSafelyReplaceDerbyCost() {
        return estimatePresent
                && Double.isFinite(providerStartupCost)
                && providerStartupCost >= 0.0d
                && Double.isFinite(providerTotalCost)
                && providerTotalCost > 0.0d
                && providerTotalCost >= providerStartupCost
                && providerEstimatedRows >= 0L;
    }

    public IndexProviderCostProbe withConsumptionFallback(String reason) {
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
                false,
                appendExplanation(explanation, reason));
    }

    public String plannerDecision() {
        if (consumed) {
            return "consumed";
        }
        if (canSafelyReplaceDerbyCost()) {
            return "available";
        }
        return "fallback";
    }

    /**
     * Names the cost source used by Derby after the DelosDB bridge evaluated
     * the provider estimate. This keeps diagnostics explicit: a provider
     * estimate can be visible without replacing Derby's native cost.
     */
    public String costSource() {
        return consumed ? "provider" : "derby";
    }

    public boolean usedProviderCost() {
        return consumed;
    }

    public boolean usedDerbyCost() {
        return !consumed;
    }

    /**
     * Returns a stable, provider-neutral diagnostic line for smoke tests and
     * future planner tracing. Keep this free of Derby implementation objects so
     * the diagnostic surface remains safe to expose outside the optimizer.
     */
    public String plannerDiagnosticLine() {
        return "DelosDBPlannerCost{"
                + "type=index-provider-cost"
                + ", mode=" + mode
                + ", provider=" + providerName
                + ", index=" + indexName
                + ", decision=" + plannerDecision()
                + ", costSource=" + costSource()
                + ", safeToConsume=" + canSafelyReplaceDerbyCost()
                + ", consumed=" + consumed
                + ", derbyCost=" + derbyCost
                + ", derbyRows=" + derbyEstimatedRows
                + ", providerTotalCost=" + providerTotalCost
                + ", providerRows=" + providerEstimatedRows
                + ", tableRows=" + tableRowCount
                + ", estimatePresent=" + estimatePresent
                + ", providerStartupCost=" + providerStartupCost
                + ", explanation=" + sanitize(explanation)
                + "}";
    }

    public String diagnosticSummary() {
        return plannerDiagnosticLine();
    }

    private static String appendExplanation(String current, String reason) {
        String sanitizedReason = sanitize(reason);
        if ("none".equals(sanitizedReason)) {
            return current == null ? "" : current;
        }
        String sanitizedCurrent = sanitize(current);
        if ("none".equals(sanitizedCurrent)) {
            return sanitizedReason;
        }
        return sanitizedCurrent + "; " + sanitizedReason;
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
