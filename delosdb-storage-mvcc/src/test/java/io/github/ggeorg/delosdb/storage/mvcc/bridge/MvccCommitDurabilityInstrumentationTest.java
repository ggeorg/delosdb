/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proofs Phase 7 commit instrumentation without changing the commit protocol. */
final class MvccCommitDurabilityInstrumentationTest {
    @TempDir
    Path databaseDirectory;

    @Test
    void committedTransactionReportsBeginCommitAndPageDurabilityWork() throws Exception {
        MvccInheritedTable table = table(701);
        Path recordingFile = databaseDirectory.resolve("commit-durability.jfr");
        try (Recording recording = recording()) {
            recording.start();

            DelosStorageTransaction transaction = table.beginTransaction();
            table.insert(1L, row(), transaction);
            table.insert(2L, row(), transaction);
            table.insert(3L, row(), transaction);
            table.commit(transaction);

            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }

        RecordedEvent event = commitEvents(recordingFile).getFirst();
        assertTrue(event.getBoolean("success"));
        assertTrue(event.getBoolean("durabilityMeasurementComplete"));
        assertEquals(3, event.getInt("changedRows"));
        assertEquals(2L, event.getLong("transactionStatusForceCount"),
                "ACTIVE and COMMITTED status records must both be attributed to the transaction");
        assertEquals(1L, event.getLong("writeAheadLogForceCount"),
                "one page-volume WAL transaction batch should be forced");
        assertEquals(1L, event.getLong("transactionOutcomeForceCount"),
                "one durable transaction outcome fences the complete payload batch");
        assertTrue(event.getLong("otherSidecarForceCount") > 0L);
        assertEquals(2L, event.getLong("pageVolumeForceCount"),
                "one main-table page batch plus ordered-index materialization are expected");
        assertTrue(event.getLong("pageVolumePagesCovered") > 0L);
        assertTrue(event.getLong("sidecarBytesCovered") + event.getLong("pageVolumeBytesCovered") > 0L);
        assertEquals(1, event.getInt("tableRequestConcurrency"));
        assertEquals(1, event.getInt("tableDurabilityExecutionConcurrency"));
        assertTrue(event.getLong("totalCommitNanos") > 0L);
    }

    @Test
    void overlappingCommitRequestsStillExecuteOneAtATimeInsideTableBoundary() throws Exception {
        MvccInheritedTable table = table(702);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Path recordingFile = databaseDirectory.resolve("commit-concurrency.jfr");
        try (Recording recording = recording()) {
            recording.start();

            DelosStorageTransaction first = table.beginTransaction();
            table.insert(1L, row(), first);
            DelosStorageTransaction second = table.beginTransaction();
            table.insert(2L, row(), second);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> firstCommit;
            Future<?> secondCommit;
            try (DelosStorageBackupCoordinator.Guard ignored =
                         DelosStorageBackupCoordinator.enterBackupSnapshot()) {
                firstCommit = executor.submit(() -> commitAfterStart(table, first, ready, start));
                secondCommit = executor.submit(() -> commitAfterStart(table, second, ready, start));
                assertTrue(ready.await(5L, TimeUnit.SECONDS));
                start.countDown();
                awaitRequestConcurrency(table, 2, Duration.ofSeconds(5));
            }

            firstCommit.get();
            secondCommit.get();
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            executor.shutdownNow();
            table.close();
        }

        List<RecordedEvent> events = commitEvents(recordingFile);
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> event.getBoolean("success")));
        assertTrue(events.stream().allMatch(event -> event.getBoolean("durabilityMeasurementComplete")));
        assertTrue(events.stream().mapToInt(event -> event.getInt("tableRequestConcurrency")).max().orElse(0) >= 2,
                "two caller threads must overlap before the backup/table boundaries");
        assertEquals(1, events.stream()
                        .mapToInt(event -> event.getInt("tableDurabilityExecutionConcurrency"))
                        .max()
                        .orElse(0),
                "the current table write lock permits only one durability execution at a time");
        assertTrue(events.stream().mapToLong(event -> event.getLong("backupWaitNanos")).sum() > 0L,
                "the held backup boundary must be visible in the measurements");
    }

    private MvccInheritedTable table(long containerId) {
        return new MvccInheritedTable(0L, containerId, databaseDirectory);
    }

    private static Recording recording() {
        Recording recording = new Recording();
        recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
        return recording;
    }

    private static List<RecordedEvent> commitEvents(Path recordingFile) throws Exception {
        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(candidate -> MvccCommitJfr.EVENT_NAME.equals(candidate.getEventType().getName()))
                .toList();
        if (events.isEmpty()) {
            throw new AssertionError("MVCC commit JFR event was not recorded");
        }
        return events;
    }

    private static StoreDataValue[] row() {
        return new StoreDataValue[0];
    }

    private static void commitAfterStart(
            MvccInheritedTable table,
            DelosStorageTransaction transaction,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            table.commit(transaction);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("commit worker interrupted", interrupted);
        }
    }

    private static void awaitRequestConcurrency(
            MvccInheritedTable table,
            int expected,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (table.activeCommitRequestsForTesting() >= expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("commit request concurrency did not reach " + expected);
    }
}
