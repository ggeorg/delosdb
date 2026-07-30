/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosRawStoreIoFaultInjector

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.derby.iapi.store.types.DelosStorageText;

/**
 * Database-scoped, disabled-by-default deterministic RawStore I/O fault seam.
 *
 * <p>The class is package-private and has no SQL, connection-attribute,
 * system-property, service-provider, or public application control surface.
 * Focused tests reach an active database through a same-package test bridge.
 * Production runtimes retain a disabled schedule.</p>
 */
final class DelosRawStoreIoFaultInjector {
    static final int REGISTRY_VERSION = 1;
    static final int MAX_RECORDED_HITS = 256;

    private final AtomicBoolean bound = new AtomicBoolean();
    private final Object monitor = new Object();
    private final EnumMap<Point, Long> occurrences = new EnumMap<>(Point.class);
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private final ProcessTerminator processTerminator;

    private volatile String databaseIdentity = "<unbound>";
    private volatile boolean runtimeActive;
    private volatile Schedule schedule = Schedule.disabled();
    private long discardedHits;
    private long injectedIoFailures;
    private long injectedProcessHalts;

    DelosRawStoreIoFaultInjector() {
        this(Runtime.getRuntime()::halt);
    }

    DelosRawStoreIoFaultInjector(ProcessTerminator processTerminator) {
        this.processTerminator = Objects.requireNonNull(
                processTerminator, "processTerminator");
    }

    void bind(String identity) {
        String normalized = DelosStorageText.requireNonBlank(identity, "identity");
        if (bound.compareAndSet(false, true)) {
            databaseIdentity = normalized;
            runtimeActive = true;
            return;
        }
        if (!databaseIdentity.equals(normalized)) {
            throw new IllegalStateException(
                    "RawStore I/O fault injector is already bound to "
                            + databaseIdentity);
        }
        if (!runtimeActive) {
            throw new IllegalStateException(
                    "RawStore I/O fault injector cannot be rebound after shutdown");
        }
    }

    void installForTesting(Schedule newSchedule) {
        Schedule required = Objects.requireNonNull(newSchedule, "newSchedule");
        if (required.steps().isEmpty()) {
            throw new IllegalArgumentException("test schedule must contain a step");
        }
        synchronized (monitor) {
            ensureActive();
            if (!schedule.steps().isEmpty()) {
                throw new IllegalStateException(
                        "RawStore I/O fault schedule is already installed for "
                                + databaseIdentity);
            }
            occurrences.clear();
            hits.clear();
            discardedHits = 0L;
            injectedIoFailures = 0L;
            injectedProcessHalts = 0L;
            schedule = required;
        }
    }

    void clearForTesting() {
        synchronized (monitor) {
            ensureActive();
            schedule = Schedule.disabled();
            occurrences.clear();
            hits.clear();
            discardedHits = 0L;
            injectedIoFailures = 0L;
            injectedProcessHalts = 0L;
        }
    }

    boolean enabled() {
        return runtimeActive && !schedule.steps().isEmpty();
    }

    void hit(Point point, Context context) throws IOException {
        Schedule observedSchedule = schedule;
        if (observedSchedule.steps().isEmpty()) {
            return;
        }

        Step matched;
        Hit hit;
        synchronized (monitor) {
            if (!runtimeActive) {
                return;
            }
            observedSchedule = schedule;
            if (observedSchedule.steps().isEmpty()) {
                return;
            }
            long occurrence = occurrences.merge(
                    Objects.requireNonNull(point, "point"), 1L, Long::sum);
            hit = new Hit(point, occurrence,
                    Objects.requireNonNull(context, "context"));
            if (hits.size() == MAX_RECORDED_HITS) {
                hits.removeFirst();
                discardedHits++;
            }
            hits.addLast(hit);
            matched = observedSchedule.match(point, occurrence);
            if (matched != null) {
                if (matched.action() == Action.THROW_IO) {
                    injectedIoFailures++;
                } else {
                    injectedProcessHalts++;
                }
            }
        }

        if (matched == null) {
            return;
        }

        switch (matched.action()) {
            case THROW_IO -> throw new InjectedIOException(
                    observedSchedule.id(), hit);
            case HALT -> {
                processTerminator.halt(matched.haltStatus());
                throw new IllegalStateException(
                        "RawStore I/O fault process terminator returned without halting");
            }
        }
    }

    Snapshot snapshot() {
        synchronized (monitor) {
            Schedule currentSchedule = schedule;
            return new Snapshot(
                    REGISTRY_VERSION,
                    databaseIdentity,
                    runtimeActive,
                    currentSchedule.id(),
                    currentSchedule.seed(),
                    currentSchedule.steps().size(),
                    List.copyOf(hits),
                    discardedHits,
                    injectedIoFailures,
                    injectedProcessHalts);
        }
    }

    void shutdown() {
        synchronized (monitor) {
            runtimeActive = false;
        }
    }

    private void ensureActive() {
        if (!runtimeActive) {
            throw new IllegalStateException(
                    "RawStore I/O fault injector is not active for "
                            + databaseIdentity);
        }
    }

    enum Point {
        BEFORE_PAGE_READ,
        AFTER_PAGE_READ,
        BEFORE_PAGE_WRITE,
        AFTER_PAGE_WRITE,
        BEFORE_FORCE_CONTENT,
        AFTER_FORCE_CONTENT,
        BEFORE_FORCE_METADATA,
        AFTER_FORCE_METADATA,
        BEFORE_CHANNEL_REOPEN,
        AFTER_CHANNEL_REOPEN
    }

    enum Action {
        THROW_IO,
        HALT
    }

    record Context(
            long segmentId,
            long containerId,
            long pageNumber,
            long position,
            int length,
            boolean metadataForce) {
        Context {
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative");
            }
        }

        static Context page(
                long segmentId,
                long containerId,
                long pageNumber,
                long position,
                int length) {
            return new Context(
                    segmentId, containerId, pageNumber, position, length, false);
        }

        static Context force(
                long segmentId,
                long containerId,
                boolean metadata) {
            return new Context(
                    segmentId, containerId, -1L, -1L, 0, metadata);
        }

        static Context reopen(long segmentId, long containerId) {
            return new Context(
                    segmentId, containerId, -1L, -1L, 0, false);
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

    record Step(Point point, long occurrence, Action action, int haltStatus) {
        Step {
            Objects.requireNonNull(point, "point");
            Objects.requireNonNull(action, "action");
            if (occurrence <= 0L) {
                throw new IllegalArgumentException("occurrence must be positive");
            }
            if (action == Action.HALT && haltStatus == 0) {
                throw new IllegalArgumentException(
                        "haltStatus must be non-zero for HALT");
            }
        }

        static Step fail(Point point, long occurrence) {
            return new Step(point, occurrence, Action.THROW_IO, -1);
        }

        static Step halt(Point point, long occurrence, int status) {
            return new Step(point, occurrence, Action.HALT, status);
        }
    }

    record Schedule(int registryVersion, String id, long seed, List<Step> steps) {
        Schedule {
            if (registryVersion != REGISTRY_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported RawStore I/O fault registry version "
                                + registryVersion);
            }
            id = DelosStorageText.requireNonBlank(id, "id");
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            for (int left = 0; left < steps.size(); left++) {
                Step step = steps.get(left);
                for (int right = left + 1; right < steps.size(); right++) {
                    Step other = steps.get(right);
                    if (step.point() == other.point()
                            && step.occurrence() == other.occurrence()) {
                        throw new IllegalArgumentException(
                                "duplicate RawStore I/O fault step "
                                        + step.point() + " occurrence "
                                        + step.occurrence());
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

        static Schedule seeded(
                String id,
                long seed,
                long occurrence,
                List<Point> candidatePoints) {
            List<Point> candidates = List.copyOf(
                    Objects.requireNonNull(candidatePoints, "candidatePoints"));
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(
                        "candidatePoints must not be empty");
            }
            Point selected = candidates.get(
                    new SplittableRandom(seed).nextInt(candidates.size()));
            return new Schedule(
                    REGISTRY_VERSION,
                    id,
                    seed,
                    List.of(Step.fail(selected, occurrence)));
        }

        Step match(Point point, long occurrence) {
            for (Step step : steps) {
                if (step.point() == point && step.occurrence() == occurrence) {
                    return step;
                }
            }
            return null;
        }
    }

    record Snapshot(
            int registryVersion,
            String databaseIdentity,
            boolean runtimeActive,
            String scheduleId,
            long seed,
            int scheduledSteps,
            List<Hit> hits,
            long discardedHits,
            long injectedIoFailures,
            long injectedProcessHalts) {
        Snapshot {
            if (registryVersion != REGISTRY_VERSION) {
                throw new IllegalArgumentException("invalid registryVersion");
            }
            databaseIdentity = DelosStorageText.requireNonBlank(
                    databaseIdentity, "databaseIdentity");
            scheduleId = DelosStorageText.requireNonBlank(
                    scheduleId, "scheduleId");
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            if (scheduledSteps < 0
                    || discardedHits < 0L
                    || injectedIoFailures < 0L
                    || injectedProcessHalts < 0L) {
                throw new IllegalArgumentException(
                        "RawStore I/O fault snapshot values must be non-negative");
            }
            if (hits.size() > MAX_RECORDED_HITS) {
                throw new IllegalArgumentException(
                        "RawStore I/O fault history exceeds its bound");
            }
        }

        long totalHits() {
            return discardedHits + hits.size();
        }
    }

    static final class InjectedIOException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String scheduleId;
        private final Hit hit;

        InjectedIOException(String scheduleId, Hit hit) {
            super("Injected RawStore I/O failure: schedule=" + scheduleId
                    + ", point=" + hit.point()
                    + ", occurrence=" + hit.occurrence());
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

    @FunctionalInterface
    interface ProcessTerminator {
        void halt(int status);
    }

}
