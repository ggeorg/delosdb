package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.Locale;

/**
 * Internal switch for the native Derby StoreCostController adapter proof.
 *
 * <p>The default is Derby-compatible. Diagnostic mode records what a provider
 * would return. Enabled mode lets a valid provider estimate replace the cost
 * inside Derby's native StoreCostController path.</p>
 */
public enum CostModelMode {
    OFF(false, false),
    DIAGNOSTIC(true, false),
    ENABLED(true, true);

    public static final String PROPERTY_NAME = "delosdb.optimizer.costModelProvider";

    private final boolean probesProviderCost;
    private final boolean consumesProviderCost;

    CostModelMode(boolean probesProviderCost, boolean consumesProviderCost) {
        this.probesProviderCost = probesProviderCost;
        this.consumesProviderCost = consumesProviderCost;
    }

    public boolean probesProviderCost() {
        return probesProviderCost;
    }

    public boolean consumesProviderCost() {
        return consumesProviderCost;
    }

    public static CostModelMode fromSystemProperties() {
        String configured = null;
        try {
            configured = System.getProperty(PROPERTY_NAME);
        } catch (SecurityException ignored) {
            return OFF;
        }
        return from(configured);
    }

    public static CostModelMode from(String configured) {
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
