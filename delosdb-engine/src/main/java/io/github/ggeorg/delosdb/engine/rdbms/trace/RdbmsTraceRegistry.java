package io.github.ggeorg.delosdb.engine.rdbms.trace;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local trace sink registry for the first DelosDB modern RDBMS model pass.
 *
 * <p>The default sink is no-op, so adding the model does not change query behavior. Future
 * overlays can wire real Derby execution points to this registry and replace the sink in focused
 * tests or diagnostics.</p>
 */
public final class RdbmsTraceRegistry {
    private static final AtomicReference<RdbmsTraceSink> SINK =
            new AtomicReference<>(RdbmsTraceSink.noop());

    private RdbmsTraceRegistry() {
    }

    public static RdbmsTraceSink sink() {
        return SINK.get();
    }

    public static void setSink(RdbmsTraceSink sink) {
        SINK.set(Objects.requireNonNull(sink, "sink"));
    }

    public static void reset() {
        SINK.set(RdbmsTraceSink.noop());
    }

    public static void emit(RdbmsTraceEvent event) {
        SINK.get().onEvent(Objects.requireNonNull(event, "event"));
    }
}
