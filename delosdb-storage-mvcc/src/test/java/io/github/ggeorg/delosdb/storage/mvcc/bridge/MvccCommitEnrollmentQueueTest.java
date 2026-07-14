/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;

import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves bounded FIFO enrollment before any cross-transaction force sharing. */
final class MvccCommitEnrollmentQueueTest {
    @TempDir
    Path databaseDirectory;

    @Test
    void queuedModeUsesBoundedFifoEnrollment() throws Exception {
        MvccCommitCoordinator coordinator = new MvccCommitCoordinator(
                MvccCommitCoordinator.Mode.QUEUED,
                2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        CountDownLatch releaseThird = new CountDownLatch(1);
        List<Integer> executionOrder = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Integer> first = executor.submit(() -> enterAndHold(
                    coordinator, 1, executionOrder, releaseFirst));
            awaitEnrollmentCount(coordinator, 1);

            Future<Integer> second = executor.submit(() -> enterAndHold(
                    coordinator, 2, executionOrder, releaseSecond));
            awaitEnrollmentCount(coordinator, 2);

            Future<Integer> third = executor.submit(() -> enterAndHold(
                    coordinator, 3, executionOrder, releaseThird));
            Thread.sleep(50L);
            assertEquals(2, coordinator.currentEnrollmentCountForTesting(),
                    "the bounded queue must not admit a third enrollment before capacity is released");
            assertFalse(third.isDone());

            releaseFirst.countDown();
            assertEquals(1, first.get(30L, TimeUnit.SECONDS).intValue());
            awaitExecutionOrder(executionOrder, 2);
            awaitEnrollmentCount(coordinator, 2);

            releaseSecond.countDown();
            assertEquals(2, second.get(30L, TimeUnit.SECONDS).intValue());
            awaitExecutionOrder(executionOrder, 3);

            releaseThird.countDown();
            assertEquals(2, third.get(30L, TimeUnit.SECONDS).intValue());
            assertEquals(List.of(1, 2, 3), executionOrder);
            assertEquals(0, coordinator.currentEnrollmentCountForTesting());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedPublicationReleasesTheNextEnrollment() throws Exception {
        MvccCommitCoordinator coordinator = new MvccCommitCoordinator(
                MvccCommitCoordinator.Mode.QUEUED,
                2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch failFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try (MvccCommitCoordinator.Permit permit = coordinator.enter(true)) {
                    if (permit.mode() != MvccCommitCoordinator.Mode.QUEUED) {
                        throw new IllegalStateException("expected queued durability mode");
                    }
                    firstEntered.countDown();
                    if (!failFirst.await(30L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out before injected publication failure");
                    }
                    throw new IllegalStateException("injected publication failure");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted before injected publication failure", e);
                }
            });
            assertTrue(firstEntered.await(30L, TimeUnit.SECONDS));
            Future<Integer> second = executor.submit(() -> {
                try (MvccCommitCoordinator.Permit permit = coordinator.enter(true)) {
                    return permit.enrollmentDepth();
                }
            });
            awaitEnrollmentCount(coordinator, 2);
            failFirst.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> first.get(30L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals(2, second.get(30L, TimeUnit.SECONDS).intValue());
            assertEquals(0, coordinator.currentEnrollmentCountForTesting());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void directAndQueuedModesHaveTheSameDurableAndFailureResults() throws Exception {
        DurableRun direct = runSuccessfulCommit(
                MvccCommitCoordinator.Mode.DIRECT,
                781L,
                databaseDirectory.resolve("direct-success"));
        DurableRun queued = runSuccessfulCommit(
                MvccCommitCoordinator.Mode.QUEUED,
                782L,
                databaseDirectory.resolve("queued-success"));

        assertEquals(direct.visibleRows(), queued.visibleRows());
        assertEquals(direct.logicalRows(), queued.logicalRows());
        assertEquals(direct.physicalVersions(), queued.physicalVersions());
        assertEquals(direct.statusForces(), queued.statusForces());
        assertEquals(direct.outcomeForces(), queued.outcomeForces());
        assertEquals(direct.walForces(), queued.walForces());
        assertEquals(direct.sidecarForces(), queued.sidecarForces());
        assertEquals(direct.pageForces(), queued.pageForces());
        assertEquals("direct", direct.coordinatorMode());
        assertEquals("queued", queued.coordinatorMode());
        assertEquals(1, direct.enrollmentDepth());
        assertEquals(1, queued.enrollmentDepth());

        List<String> directConflict = runPrePublicationConflict(
                MvccCommitCoordinator.Mode.DIRECT,
                783L,
                databaseDirectory.resolve("direct-conflict"));
        List<String> queuedConflict = runPrePublicationConflict(
                MvccCommitCoordinator.Mode.QUEUED,
                784L,
                databaseDirectory.resolve("queued-conflict"));
        assertEquals(directConflict, queuedConflict,
                "direct and queued modes must preserve the same pre-publication failure result");
    }

    private static int enterAndHold(
            MvccCommitCoordinator coordinator,
            int marker,
            List<Integer> executionOrder,
            CountDownLatch release) {
        try (MvccCommitCoordinator.Permit permit = coordinator.enter(true)) {
            synchronized (executionOrder) {
                executionOrder.add(marker);
            }
            assertTrue(release.await(30L, TimeUnit.SECONDS),
                    "timed out while holding durability enrollment " + marker);
            return permit.enrollmentDepth();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while proving commit enrollment", e);
        }
    }

    private static void awaitEnrollmentCount(
            MvccCommitCoordinator coordinator,
            int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while (System.nanoTime() < deadline) {
            if (coordinator.currentEnrollmentCountForTesting() == expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("expected enrollment count " + expected + " but saw "
                + coordinator.currentEnrollmentCountForTesting());
    }

    private static void awaitExecutionOrder(List<Integer> executionOrder, int marker)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while (System.nanoTime() < deadline) {
            synchronized (executionOrder) {
                if (executionOrder.contains(marker)) {
                    return;
                }
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("durability enrollment " + marker + " did not execute");
    }

    private static DurableRun runSuccessfulCommit(
            MvccCommitCoordinator.Mode mode,
            long containerId,
            Path directory) throws Exception {
        Files.createDirectories(directory);
        Path recordingFile = directory.resolve("commit.jfr");
        MvccInheritedTable table = new MvccInheritedTable(0L, containerId, directory, mode);
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();
            DelosStorageTransaction transaction = table.beginTransaction();
            for (long rowId = 1L; rowId <= 8L; rowId++) {
                table.insert(rowId, emptyRow(), transaction);
            }
            table.commit(transaction);
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }

        List<String> visibleRows;
        int logicalRows;
        int physicalVersions;
        MvccInheritedTable reopened = new MvccInheritedTable(0L, containerId, directory, mode);
        try {
            reopened.assertConsistentForTesting();
            visibleRows = reopened.pageBackedVisibleRowSummariesForTesting();
            logicalRows = reopened.logicalRowCountForTesting();
            physicalVersions = reopened.physicalVersionCountForTesting();
        } finally {
            reopened.close();
        }

        RecordedEvent event = commitEvents(recordingFile).getFirst();
        assertTrue(event.getBoolean("success"));
        return new DurableRun(
                visibleRows,
                logicalRows,
                physicalVersions,
                event.getLong("transactionStatusForceCount"),
                event.getLong("transactionOutcomeForceCount"),
                event.getLong("writeAheadLogForceCount"),
                event.getLong("otherSidecarForceCount"),
                event.getLong("pageVolumeForceCount"),
                event.getString("durabilityCoordinatorMode"),
                event.getInt("durabilityEnrollmentDepth"));
    }

    private static List<String> runPrePublicationConflict(
            MvccCommitCoordinator.Mode mode,
            long containerId,
            Path directory) throws Exception {
        Files.createDirectories(directory);
        MvccInheritedTable table = new MvccInheritedTable(0L, containerId, directory, mode);
        try {
            DelosStorageTransaction first = table.beginTransaction();
            DelosStorageTransaction second = table.beginTransaction();
            table.insert(1L, emptyRow(), first);
            table.insert(1L, emptyRow(), second);
            assertThrows(MvccWriteConflictException.class, () -> table.commit(first));
            table.commit(second);
            table.assertConsistentForTesting();
        } finally {
            table.close();
        }

        MvccInheritedTable reopened = new MvccInheritedTable(0L, containerId, directory, mode);
        try {
            reopened.assertConsistentForTesting();
            return reopened.pageBackedVisibleRowSummariesForTesting();
        } finally {
            reopened.close();
        }
    }

    private static List<RecordedEvent> commitEvents(Path recordingFile) throws Exception {
        return RecordingFile.readAllEvents(recordingFile).stream()
                .filter(candidate -> MvccCommitJfr.EVENT_NAME.equals(candidate.getEventType().getName()))
                .toList();
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }

    private record DurableRun(
            List<String> visibleRows,
            int logicalRows,
            int physicalVersions,
            long statusForces,
            long outcomeForces,
            long walForces,
            long sidecarForces,
            long pageForces,
            String coordinatorMode,
            int enrollmentDepth) {
        private DurableRun {
            visibleRows = List.copyOf(visibleRows);
        }
    }
}
