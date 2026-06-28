package io.github.ggeorg.delosdb.engine.trace;

/**
 * Consumer for DelosDB modern RDBMS trace events.
 */
@FunctionalInterface
public interface RdbmsTraceSink {
    RdbmsTraceSink NOOP = event -> { };

    void onEvent(RdbmsTraceEvent event);

    static RdbmsTraceSink noop() {
        return NOOP;
    }
}
