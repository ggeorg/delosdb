package io.github.ggeorg.delosdb.engine.extension.index;

/**
 * Internal diagnostic surface for the current IndexProvider optimizer bridge.
 *
 * <p>This is not a public SQL feature. Diagnostics are scoped per thread so
 * concurrent planning cannot overwrite another query's last provider-cost
 * probe.</p>
 */
public final class IndexProviderCostDiagnostics {
    private static final ThreadLocal<IndexProviderCostProbe> LAST_PROBE = new ThreadLocal<>();

    private IndexProviderCostDiagnostics() {
    }

    public static void record(IndexProviderCostProbe probe) {
        LAST_PROBE.set(probe);
    }

    public static IndexProviderCostProbe lastProbe() {
        return LAST_PROBE.get();
    }

    public static void clear() {
        LAST_PROBE.remove();
    }
}
