package io.github.ggeorg.delosdb.engine.rdbms.trace;

import io.github.ggeorg.delosdb.engine.rdbms.pipeline.RdbmsLifecycleStage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A small immutable observation emitted by the DelosDB modern RDBMS model.
 *
 * <p>Events should explain real Derby/DelosDB behavior. They are intentionally generic in the
 * first pass so the model can observe compiler, execution, storage, and MVCC paths without forcing
 * a premature project layout.</p>
 */
public record RdbmsTraceEvent(
        Instant timestamp,
        RdbmsLifecycleStage stage,
        String subject,
        Map<String, String> attributes) {

    public RdbmsTraceEvent {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        stage = Objects.requireNonNull(stage, "stage");
        subject = Objects.requireNonNull(subject, "subject");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    public static RdbmsTraceEvent of(RdbmsLifecycleStage stage, String subject) {
        return new RdbmsTraceEvent(Instant.now(), stage, subject, Map.of());
    }

    public static RdbmsTraceEvent of(
            RdbmsLifecycleStage stage,
            String subject,
            Map<String, String> attributes) {
        return new RdbmsTraceEvent(Instant.now(), stage, subject, attributes);
    }
}
