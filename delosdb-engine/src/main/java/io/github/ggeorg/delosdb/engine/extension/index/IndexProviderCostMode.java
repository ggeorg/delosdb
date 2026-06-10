package io.github.ggeorg.delosdb.engine.extension.index;

import java.util.Locale;

/**
 * Internal optimizer switch for DelosDB IndexProvider cost integration.
 *
 * <p>The default remains Derby-compatible: provider costs are not consulted.
 * Diagnostic mode records provider estimates without changing the Derby cost.
 * Enabled mode may replace the Derby cost with a valid provider estimate at the
 * narrow index-cost bridge point.</p>
 */
public enum IndexProviderCostMode {
    OFF(false, false),
    DIAGNOSTIC(true, false),
    ENABLED(true, true);

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
