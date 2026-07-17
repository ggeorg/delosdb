package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

import io.github.ggeorg.delosdb.storage.mvcc.failure.MvccStorageFailureHook;

/**
 * Database-scoped, test/research-only deterministic failure-point authority.
 *
 * <p>The registry is package-private and has no SQL, system-property, service,
 * or public provider configuration surface. Normal stores own a disabled
 * instance. Focused tests in this package may construct an explicit schedule
 * and pass it through the package-private store constructor.</p>
 */
final class MvccFailurePointRegistry {
    static final int REGISTRY_VERSION = 1;

    private final Path databaseDirectory;
    private final Schedule schedule;
    private final ProcessTerminator processTerminator;
    private final EnumMap<Point, Long> occurrences = new EnumMap<>(Point.class);
    private final List<Hit> hits = new ArrayList<>();

    private MvccFailurePointRegistry(
            Path databaseDirectory,
            Schedule schedule,
            ProcessTerminator processTerminator) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.processTerminator = Objects.requireNonNull(processTerminator, "processTerminator");
    }

    static MvccFailurePointRegistry disabled(Path databaseDirectory) {
        return new MvccFailurePointRegistry(
                databaseDirectory, Schedule.disabled(), Runtime.getRuntime()::halt);
    }

    static MvccFailurePointRegistry scheduled(Path databaseDirectory, Schedule schedule) {
        return new MvccFailurePointRegistry(
                databaseDirectory, schedule, Runtime.getRuntime()::halt);
    }

    static MvccFailurePointRegistry scheduled(
            Path databaseDirectory,
            Schedule schedule,
            ProcessTerminator processTerminator) {
        return new MvccFailurePointRegistry(databaseDirectory, schedule, processTerminator);
    }

    void hit(Point point, Context context) {
        Objects.requireNonNull(point, "point");
        Context requiredContext = Objects.requireNonNull(context, "context");
        if (schedule.steps().isEmpty()) {
            return;
        }

        Step matched;
        Hit hit;
        synchronized (this) {
            long occurrence = occurrences.merge(point, 1L, Long::sum);
            hit = new Hit(point, occurrence, requiredContext);
            hits.add(hit);
            matched = schedule.match(point, occurrence);
        }
        if (matched == null) {
            return;
        }

        matched.barrier().await(hit);
        switch (matched.action()) {
            case THROW -> throw new InjectedFailure(schedule.id(), hit);
            case HALT -> {
                processTerminator.halt(matched.haltStatus());
                throw new IllegalStateException(
                        "MVCC failure-point process terminator returned without halting");
            }
        }
    }

    synchronized List<Hit> hits() {
        return List.copyOf(hits);
    }

    Schedule schedule() {
        return schedule;
    }

    Path databaseDirectory() {
        return databaseDirectory;
    }

    MvccStorageFailureHook storageHook() {
        if (schedule.steps().isEmpty()) {
            return MvccStorageFailureHook.NOOP;
        }
        return (point, context) -> hit(
                Point.valueOf(point.name()),
                Context.transaction(
                        context.transactionId(),
                        context.commitSequence(),
                        context.participantIndex(),
                        context.participantCount()));
    }

    enum Point {
        AFTER_PARTICIPANT_PREPARE,
        BEFORE_TRANSACTION_DECISION_FORCE,
        AFTER_TRANSACTION_DECISION_FORCE,
        BEFORE_FIRST_PARTICIPANT_PUBLICATION,
        BETWEEN_PARTICIPANT_PUBLICATIONS,
        BEFORE_DERBY_RAW_STORE_COMMIT,
        AFTER_DERBY_RAW_STORE_COMMIT,
        DURING_CHECKPOINT,
        DURING_PAGE_ALLOCATION,
        DURING_INDEX_PUBLICATION,
        DURING_OVERFLOW_PUBLICATION,
        DURING_VACUUM_REUSE
    }

    enum Action {
        THROW,
        HALT
    }

    record Context(
            long transactionId,
            long commitSequence,
            int participantIndex,
            int participantCount) {
        static Context transaction(
                long transactionId,
                long commitSequence,
                int participantIndex,
                int participantCount) {
            return new Context(
                    transactionId, commitSequence, participantIndex, participantCount);
        }
    }

    record Hit(Point point, long occurrence, Context context) {
        Hit {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(context, "context");
            if (occurrence <= 0L) {
                throw new IllegalArgumentException("occurrence must be positive");
            }
        }
    }

    record Step(
            Point point,
            long occurrence,
            Action action,
            int haltStatus,
            Barrier barrier) {
        Step {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(action, "action");
            barrier = Objects.requireNonNullElse(barrier, Barrier.none());
            if (occurrence <= 0L) {
                throw new IllegalArgumentException("occurrence must be positive");
            }
            if (action == Action.HALT && haltStatus == 0) {
                throw new IllegalArgumentException("haltStatus must be non-zero for HALT");
            }
        }

        static Step fail(Point point) {
            return fail(point, 1L);
        }

        static Step fail(Point point, long occurrence) {
            return new Step(point, occurrence, Action.THROW, -1, Barrier.none());
        }

        static Step halt(Point point, long occurrence, int status) {
            return new Step(point, occurrence, Action.HALT, status, Barrier.none());
        }

        Step withBarrier(Barrier value) {
            return new Step(point, occurrence, action, haltStatus, value);
        }
    }

    record Schedule(int registryVersion, String id, long seed, List<Step> steps) {
        Schedule {
            if (registryVersion != REGISTRY_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported MVCC failure registry version " + registryVersion);
            }
            id = requireText(id, "id");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            for (int left = 0; left < steps.size(); left++) {
                Step step = steps.get(left);
                for (int right = left + 1; right < steps.size(); right++) {
                    Step other = steps.get(right);
                    if (step.point() == other.point()
                            && step.occurrence() == other.occurrence()) {
                        throw new IllegalArgumentException(
                                "duplicate MVCC failure step " + step.point()
                                        + " occurrence " + step.occurrence());
                    }
                }
            }
        }

        static Schedule disabled() {
            return new Schedule(REGISTRY_VERSION, "disabled", 0L, List.of());
        }

        static Schedule of(String id, Step... steps) {
            return new Schedule(REGISTRY_VERSION, id, 0L, List.of(steps));
        }

        static Schedule seeded(String id, long seed, List<Point> candidatePoints) {
            List<Point> candidates = List.copyOf(
                    Objects.requireNonNull(candidatePoints, "candidatePoints"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("candidatePoints must not be empty");
            }
            SplittableRandom random = new SplittableRandom(seed);
            Point point = candidates.get(random.nextInt(candidates.size()));
            long occurrence = random.nextLong(1L, 4L);
            return new Schedule(
                    REGISTRY_VERSION,
                    id,
                    seed,
                    List.of(Step.fail(point, occurrence)));
        }

        Step match(Point point, long occurrence) {
            for (Step step : steps) {
                if (step.point() == point && step.occurrence() == occurrence) {
                    return step;
                }
            }
            return null;
        }

        private static String requireText(String value, String label) {
            String normalized = Objects.requireNonNull(value, label).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return normalized;
        }
    }

    @FunctionalInterface
    interface Barrier {
        Barrier NONE = ignored -> { };

        void await(Hit hit);

        static Barrier none() {
            return NONE;
        }
    }

    @FunctionalInterface
    interface ProcessTerminator {
        void halt(int status);
    }

    static final class InjectedFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private final String scheduleId;
        private final Hit hit;

        InjectedFailure(String scheduleId, Hit hit) {
            super("injected MVCC failure schedule=" + scheduleId
                    + " point=" + hit.point()
                    + " occurrence=" + hit.occurrence()
                    + " transaction=" + hit.context().transactionId()
                    + " commitSequence=" + hit.context().commitSequence()
                    + " participant=" + hit.context().participantIndex()
                    + "/" + hit.context().participantCount());
            this.scheduleId = scheduleId;
            this.hit = hit;
        }

        String scheduleId() {
            return scheduleId;
        }

        Hit hit() {
            return hit;
        }
    }
}
