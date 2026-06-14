package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local diagnostics for the native StoreCostController adapter proof.
 */
public final class CostModelDiagnostics {
    private static final ThreadLocal<CostModelProbe> LAST_PROBE = new ThreadLocal<>();
    private static final ThreadLocal<List<CostModelProbe>> PROBES = ThreadLocal.withInitial(ArrayList::new);

    private CostModelDiagnostics() {
    }

    public static void record(CostModelProbe probe) {
        if (probe == null) {
            clear();
        } else {
            LAST_PROBE.set(probe);
            PROBES.get().add(probe);
        }
    }

    public static CostModelProbe lastProbe() {
        return LAST_PROBE.get();
    }

    public static List<CostModelProbe> probes() {
        return List.copyOf(PROBES.get());
    }

    public static void clear() {
        LAST_PROBE.remove();
        PROBES.remove();
    }
}
