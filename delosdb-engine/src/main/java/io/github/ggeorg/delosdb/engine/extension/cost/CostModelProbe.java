package io.github.ggeorg.delosdb.engine.extension.cost;

/**
 * Stable diagnostic record for the native StoreCostController adapter proof.
 */
public record CostModelProbe(
        String mode,
        String providerName,
        long conglomerateId,
        int factoryId,
        int scanType,
        long inputRowCount,
        double derbyCost,
        long derbyRows,
        boolean estimatePresent,
        double providerStartupCost,
        double providerTotalCost,
        long providerRows,
        boolean consumed,
        String explanation
) {
    /**
     * Stable diagnostic label for the native DelosDB cost path. The legacy
     * optimizer-side IndexProviderCostBridge must not use this label.
     */
    public static final String ADAPTER_PATH = "store-cost-controller";

    public String adapterPath() {
        return ADAPTER_PATH;
    }

    /**
     * Stable access-method label derived from Derby's inherited access-method
     * factory id. This is intentionally diagnostic-only; it does not expose
     * Derby store objects as public SPI.
     */
    public String accessMethod() {
        return switch (factoryId) {
            case 0 -> "heap";
            case 1 -> "btree";
            default -> "factory-" + factoryId;
        };
    }

    public boolean canSafelyReplaceDerbyCost() {
        return estimatePresent
                && Double.isFinite(providerStartupCost)
                && providerStartupCost >= 0.0d
                && Double.isFinite(providerTotalCost)
                && providerTotalCost > 0.0d
                && providerTotalCost >= providerStartupCost
                && providerRows >= 0L;
    }

    public CostModelProbe withConsumed(boolean consumed) {
        return new CostModelProbe(
                mode,
                providerName,
                conglomerateId,
                factoryId,
                scanType,
                inputRowCount,
                derbyCost,
                derbyRows,
                estimatePresent,
                providerStartupCost,
                providerTotalCost,
                providerRows,
                consumed,
                explanation);
    }

    public String decision() {
        if (consumed) {
            return "consumed";
        }
        if (canSafelyReplaceDerbyCost()) {
            return "available";
        }
        return "fallback";
    }

    public String costSource() {
        return consumed ? "provider" : "derby";
    }

    public String diagnosticLine() {
        return "DelosDBStoreCost{"
                + "type=cost-model-provider"
                + ", path=" + adapterPath()
                + ", mode=" + mode
                + ", provider=" + providerName
                + ", accessMethod=" + accessMethod()
                + ", conglomId=" + conglomerateId
                + ", factoryId=" + factoryId
                + ", scanType=" + scanType
                + ", decision=" + decision()
                + ", costSource=" + costSource()
                + ", safeToConsume=" + canSafelyReplaceDerbyCost()
                + ", consumed=" + consumed
                + ", derbyCost=" + derbyCost
                + ", derbyRows=" + derbyRows
                + ", providerTotalCost=" + providerTotalCost
                + ", providerRows=" + providerRows
                + ", inputRows=" + inputRowCount
                + ", estimatePresent=" + estimatePresent
                + ", providerStartupCost=" + providerStartupCost
                + ", explanation=" + sanitize(explanation)
                + "}";
    }

    static CostModelProbe unavailable(
            String mode,
            String providerName,
            long conglomerateId,
            int factoryId,
            int scanType,
            long inputRowCount,
            double derbyCost,
            long derbyRows,
            String explanation) {
        return new CostModelProbe(
                mode,
                providerName,
                conglomerateId,
                factoryId,
                scanType,
                inputRowCount,
                derbyCost,
                derbyRows,
                false,
                0.0d,
                0.0d,
                0L,
                false,
                explanation);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
