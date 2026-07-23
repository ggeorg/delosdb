/*

   Derby - Class org.apache.derby.impl.store.raw.data.RawStoreIoFaultInjectionTestSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.util.List;

/** Test-source-only bridge to the package-private RawStore I/O fault seam. */
public final class RawStoreIoFaultInjectionTestSupport {
    private RawStoreIoFaultInjectionTestSupport() {
    }

    public static void installThrow(
            String databaseIdentity,
            String scheduleId,
            String point,
            long occurrence) {
        DelosRawStoreIoFaultInjectionDirectory.installThrowForTesting(
                databaseIdentity, scheduleId, point, occurrence);
    }

    public static void installHalt(
            String databaseIdentity,
            String scheduleId,
            String point,
            long occurrence,
            int haltStatus) {
        DelosRawStoreIoFaultInjectionDirectory.installHaltForTesting(
                databaseIdentity,
                scheduleId,
                point,
                occurrence,
                haltStatus);
    }

    public static void clear(String databaseIdentity) {
        DelosRawStoreIoFaultInjectionDirectory.clearForTesting(databaseIdentity);
    }

    public static Evidence evidence(String databaseIdentity) {
        return toEvidence(
                DelosRawStoreIoFaultInjectionDirectory.snapshotForTesting(
                        databaseIdentity));
    }

    public static ControllerProof exerciseDeterministicController()
            throws IOException {
        DelosRawStoreIoFaultInjector exact =
                new DelosRawStoreIoFaultInjector(status -> {
                    throw new AssertionError(
                            "exact-occurrence proof must not halt: " + status);
                });
        exact.bind("file:/stage8.3-exact-controller");
        exact.installForTesting(
                DelosRawStoreIoFaultInjector.Schedule.of(
                        "exact-second-write",
                        DelosRawStoreIoFaultInjector.Step.fail(
                                DelosRawStoreIoFaultInjector.Point
                                        .BEFORE_PAGE_WRITE,
                                2L)));
        DelosRawStoreIoFaultInjector.Context page =
                DelosRawStoreIoFaultInjector.Context.page(
                        0L, 17L, 3L, 12288L, 4096);
        exact.hit(
                DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_WRITE,
                page);
        boolean exactFailure = false;
        try {
            exact.hit(
                    DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_WRITE,
                    page);
        } catch (DelosRawStoreIoFaultInjector.InjectedIOException expected) {
            exactFailure = expected.hit().occurrence() == 2L
                    && "exact-second-write".equals(expected.scheduleId());
        }
        exact.hit(
                DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_WRITE,
                page);
        DelosRawStoreIoFaultInjector.Snapshot exactSnapshot = exact.snapshot();

        List<DelosRawStoreIoFaultInjector.Point> candidates = List.of(
                DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_READ,
                DelosRawStoreIoFaultInjector.Point.AFTER_PAGE_WRITE,
                DelosRawStoreIoFaultInjector.Point.AFTER_FORCE_METADATA);
        DelosRawStoreIoFaultInjector.Schedule firstSeeded =
                DelosRawStoreIoFaultInjector.Schedule.seeded(
                        "seeded", 0x5eed83L, 4L, candidates);
        DelosRawStoreIoFaultInjector.Schedule secondSeeded =
                DelosRawStoreIoFaultInjector.Schedule.seeded(
                        "seeded", 0x5eed83L, 4L, candidates);
        boolean seededSelectionStable = firstSeeded.equals(secondSeeded);

        DelosRawStoreIoFaultInjector bounded =
                new DelosRawStoreIoFaultInjector(status -> {
                    throw new AssertionError(
                            "bounded-history proof must not halt: " + status);
                });
        bounded.bind("memory:stage8.3-bounded-controller");
        bounded.installForTesting(
                DelosRawStoreIoFaultInjector.Schedule.of(
                        "bounded-history",
                        DelosRawStoreIoFaultInjector.Step.fail(
                                DelosRawStoreIoFaultInjector.Point
                                        .BEFORE_PAGE_READ,
                                1000L)));
        for (int occurrence = 0; occurrence < 300; occurrence++) {
            bounded.hit(
                    DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_READ,
                    page);
        }
        DelosRawStoreIoFaultInjector.Snapshot boundedSnapshot =
                bounded.snapshot();

        DelosRawStoreIoFaultInjector disabled =
                new DelosRawStoreIoFaultInjector(status -> {
                    throw new AssertionError(
                            "disabled controller must not halt: " + status);
                });
        disabled.bind("file:/stage8.3-disabled-controller");
        disabled.hit(
                DelosRawStoreIoFaultInjector.Point.BEFORE_PAGE_WRITE,
                page);
        DelosRawStoreIoFaultInjector.Snapshot disabledSnapshot =
                disabled.snapshot();

        return new ControllerProof(
                exactFailure,
                exactSnapshot.totalHits(),
                exactSnapshot.injectedIoFailures(),
                seededSelectionStable,
                boundedSnapshot.hits().size(),
                boundedSnapshot.discardedHits(),
                boundedSnapshot.totalHits(),
                disabledSnapshot.totalHits(),
                disabledSnapshot.injectedIoFailures(),
                DelosRawStoreIoFaultInjector.REGISTRY_VERSION,
                DelosRawStoreIoFaultInjector.MAX_RECORDED_HITS);
    }

    private static Evidence toEvidence(
            DelosRawStoreIoFaultInjector.Snapshot snapshot) {
        List<HitEvidence> hits = snapshot.hits().stream()
                .map(hit -> new HitEvidence(
                        hit.point().name(),
                        hit.occurrence(),
                        hit.context().segmentId(),
                        hit.context().containerId(),
                        hit.context().pageNumber(),
                        hit.context().position(),
                        hit.context().length(),
                        hit.context().metadataForce()))
                .toList();
        return new Evidence(
                snapshot.registryVersion(),
                snapshot.databaseIdentity(),
                snapshot.runtimeActive(),
                snapshot.scheduleId(),
                snapshot.seed(),
                snapshot.scheduledSteps(),
                hits,
                snapshot.discardedHits(),
                snapshot.injectedIoFailures(),
                snapshot.injectedProcessHalts());
    }

    public record ControllerProof(
            boolean exactOccurrenceFailed,
            long exactHits,
            long exactInjectedIoFailures,
            boolean seededSelectionStable,
            int retainedBoundedHits,
            long discardedBoundedHits,
            long totalBoundedHits,
            long disabledHits,
            long disabledInjectedIoFailures,
            int registryVersion,
            int maximumRecordedHits) {
    }

    public record Evidence(
            int registryVersion,
            String databaseIdentity,
            boolean runtimeActive,
            String scheduleId,
            long seed,
            int scheduledSteps,
            List<HitEvidence> hits,
            long discardedHits,
            long injectedIoFailures,
            long injectedProcessHalts) {
        public Evidence {
            hits = List.copyOf(hits);
        }

        public long totalHits() {
            return discardedHits + hits.size();
        }
    }

    public record HitEvidence(
            String point,
            long occurrence,
            long segmentId,
            long containerId,
            long pageNumber,
            long position,
            int length,
            boolean metadataForce) {
    }
}
