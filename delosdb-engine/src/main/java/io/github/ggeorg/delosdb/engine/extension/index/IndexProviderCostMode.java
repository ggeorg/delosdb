package io.github.ggeorg.delosdb.engine.extension.index;

import java.util.Locale;

/**
 * Legacy optimizer-side switch for DelosDB IndexProvider cost diagnostics.
 *
 * <p>The native cost-consumption path is now {@code CostModelProvider} through
 * Derby's {@code StoreCostController} seam. This older bridge remains only as
 * a diagnostic checkpoint for IndexProvider metadata and catalog plumbing. Both
 * diagnostic and enabled spellings record provider estimates without replacing
 * Derby's optimizer cost.</p>
 */
public enum IndexProviderCostMode {
    OFF(false, false),
    DIAGNOSTIC(true, false),
    ENABLED(true, false);

    public static final String PROPERTY_NAME = "delosdb.optimizer.indexProviderCost";

    private final boolean probesProviderCost;
    private final boolean consumesProviderCost;

    IndexProviderCostMode(boolean probesProviderCost, boolean consumesProviderCost) {
        this.probesProviderCost = probesProviderCost;
        this.consumesProviderCost = consumesProviderCost;
    }

    public boolean probesProviderCost() {
        return probesProviderCost;
    }

    public boolean consumesProviderCost() {
        return consumesProviderCost;
    }

    public boolean legacyDiagnosticOnly() {
        return probesProviderCost && !consumesProviderCost;
    }

    public static IndexProviderCostMode fromSystemProperties() {
        String configured = null;
        try {
            configured = System.getProperty(PROPERTY_NAME);
        } catch (SecurityException ignored) {
            return OFF;
        }
        return from(configured);
    }

    public static IndexProviderCostMode from(String configured) {
        if (configured == null || configured.isBlank()) {
            return OFF;
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "diagnostic", "diagnostics", "trace" -> DIAGNOSTIC;
            case "enabled", "on", "true" -> ENABLED;
            default -> OFF;
        };
    }
}
