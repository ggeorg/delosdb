package io.github.ggeorg.delosdb.engine.extension.index;

/**
 * Internal diagnostic surface for the current IndexProvider optimizer bridge.
 *
 * <p>This is not a public SQL feature. It exists so DelosDB can prove that the
 * optimizer sees provider cost estimates and, when explicitly enabled, can
 * consume them without introducing fake provider names or public debug syntax.</p>
 */
public final class IndexProviderCostDiagnostics {
    private static volatile IndexProviderCostProbe lastProbe;

    private IndexProviderCostDiagnostics() {
    }

    public static void record(IndexProviderCostProbe probe) {
        lastProbe = probe;
    }

    public static IndexProviderCostProbe lastProbe() {
        return lastProbe;
    }

    public static void clear() {
        lastProbe = null;
    }
}
