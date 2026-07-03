package io.github.ggeorg.delosdb.engine.extension.cost;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostics for the native StoreCostController adapter proof.
 *
 * <p>The optimizer can request store-cost estimates while Derby is compiling a
 * statement through nested compiler/language contexts.  Keep the last probe in
 * a thread-local slot for local debugging, but keep the test-visible probe list
 * process-wide and explicitly cleared by the DelosDB gate.  That makes the
 * opt-in proof observe probes even when the cost controller work crosses an
 * internal Derby context boundary.</p>
 */
public final class CostModelDiagnostics {
    private static final Object LOCK = new Object();
    private static final ThreadLocal<CostModelProbe> LAST_PROBE = new ThreadLocal<>();
    private static final List<CostModelProbe> PROBES = new ArrayList<>();

    private CostModelDiagnostics() {
    }

    public static void record(CostModelProbe probe) {
        if (probe == null) {
            clear();
            return;
        }
        LAST_PROBE.set(probe);
        synchronized (LOCK) {
            PROBES.add(probe);
        }
    }

    public static CostModelProbe lastProbe() {
        synchronized (LOCK) {
            if (PROBES.isEmpty()) {
                return LAST_PROBE.get();
            }
            return PROBES.get(PROBES.size() - 1);
        }
    }

    public static List<CostModelProbe> probes() {
        synchronized (LOCK) {
            return List.copyOf(PROBES);
        }
    }

    public static void clear() {
        LAST_PROBE.remove();
        synchronized (LOCK) {
            PROBES.clear();
        }
    }
}
