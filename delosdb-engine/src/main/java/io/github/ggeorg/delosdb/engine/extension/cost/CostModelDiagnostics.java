package io.github.ggeorg.delosdb.engine.extension.cost;

/**
 * Thread-local diagnostics for the native StoreCostController adapter proof.
 */
public final class CostModelDiagnostics {
    private static final ThreadLocal<CostModelProbe> LAST_PROBE = new ThreadLocal<>();

    private CostModelDiagnostics() {
    }

    public static void record(CostModelProbe probe) {
        if (probe == null) {
            LAST_PROBE.remove();
        } else {
            LAST_PROBE.set(probe);
        }
    }

    public static CostModelProbe lastProbe() {
        return LAST_PROBE.get();
    }

    public static void clear() {
        LAST_PROBE.remove();
    }
}
