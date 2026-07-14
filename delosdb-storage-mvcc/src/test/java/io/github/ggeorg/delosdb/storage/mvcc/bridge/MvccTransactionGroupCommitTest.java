/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
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

import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the first real cross-transaction MVCC group-commit boundary. */
final class MvccTransactionGroupCommitTest {
    private static final int ROWS_PER_TRANSACTION = 32;

    @TempDir
    Path databaseDirectory;

    @Test
    void groupedTransactionsShareStatusAndIndexDurability() throws Exception {
        CommitRun direct = runTwoTransactions(
                MvccCommitCoordinator.Mode.DIRECT,
                801L,
                databaseDirectory.resolve("direct"));
        CommitRun grouped = runTwoTransactions(
                MvccCommitCoordinator.Mode.GROUP,
                802L,
                databaseDirectory.resolve("group"));

        assertEquals(direct.visibleRows(), grouped.visibleRows());
        assertEquals(ROWS_PER_TRANSACTION * 2, grouped.logicalRows());
        assertEquals(2, grouped.events().size());
        assertTrue(grouped.events().stream().allMatch(event -> event.getBoolean("success")));
        assertTrue(grouped.events().stream().allMatch(event -> event.getInt("groupCommitSize") == 2));
        assertEquals(1L, grouped.events().stream()
                .filter(event -> event.getBoolean("groupCommitLeader"))
                .count());

        assertEquals(4L, forceSum(direct.events(), "transactionStatusForceCount"));
        assertEquals(3L, forceSum(grouped.events(), "transactionStatusForceCount"),
                "two ACTIVE records plus one shared COMMITTED append are expected");
        assertEquals(4L, forceSum(direct.events(), "pageVolumeForceCount"));
        assertEquals(3L, forceSum(grouped.events(), "pageVolumeForceCount"),
                "two main-table page forces plus one shared ordered-index force are expected");
        assertEquals(2L, forceSum(grouped.events(), "transactionOutcomeForceCount"));
        assertEquals(2L, forceSum(grouped.events(), "writeAheadLogForceCount"));
        assertTrue(forceSum(grouped.events(), "groupCommitSharedForceCount") > 0L);
        assertTrue(grouped.events().stream().allMatch(event ->
                !event.getBoolean("groupCommitLeaderFailure")
                        && !event.getBoolean("groupCommitFollowerFailure")));
    }

    @Test
    void oneTransactionKeepsTheEstablishedForceContract() throws Exception {
        Path directory = databaseDirectory.resolve("single");
        Files.createDirectories(directory);
        Path recordingFile = directory.resolve("commit.jfr");
        MvccInheritedTable table = new MvccInheritedTable(
                0L, 803L, directory, MvccCommitCoordinator.Mode.GROUP);
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();
            DelosStorageTransaction transaction = table.beginTransaction();
            table.insert(1L, emptyRow(), transaction);
            table.commit(transaction);
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }

        RecordedEvent event = commitEvents(recordingFile).getFirst();
        assertEquals(1, event.getInt("groupCommitSize"));
        assertTrue(event.getBoolean("groupCommitLeader"));
        assertEquals(2L, event.getLong("transactionStatusForceCount"));
        assertEquals(1L, event.getLong("transactionOutcomeForceCount"));
        assertEquals(1L, event.getLong("writeAheadLogForceCount"));
        assertEquals(2L, event.getLong("pageVolumeForceCount"));
    }

    @Test
    void groupProcessorFailureIsPropagatedToLeaderAndFollower() throws Exception {
        MvccCommitCoordinator<Integer, Integer> coordinator = new MvccCommitCoordinator<>(
                MvccCommitCoordinator.Mode.GROUP,
                4,
                4,
                TimeUnit.MILLISECONDS.toNanos(10L));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvccCommitCoordinator.Submission<Integer>> first = executor.submit(() ->
                    submitFailure(coordinator, 1, ready, start));
            Future<MvccCommitCoordinator.Submission<Integer>> second = executor.submit(() ->
                    submitFailure(coordinator, 2, ready, start));
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();

            List<MvccCommitCoordinator.Submission<Integer>> submissions = List.of(
                    first.get(30L, TimeUnit.SECONDS),
                    second.get(30L, TimeUnit.SECONDS));
            assertTrue(submissions.stream().allMatch(submission -> !submission.succeeded()));
            assertTrue(submissions.stream().allMatch(submission ->
                    submission.failure() instanceof IllegalStateException));
            assertTrue(submissions.stream().allMatch(submission -> submission.groupSize() == 2));
            assertEquals(1L, submissions.stream().filter(
                    MvccCommitCoordinator.Submission::leader).count());
            assertEquals(0, coordinator.currentEnrollmentCountForTesting());
        } finally {
            executor.shutdownNow();
        }
    }

    private static MvccCommitCoordinator.Submission<Integer> submitFailure(
            MvccCommitCoordinator<Integer, Integer> coordinator,
            int value,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(30L, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before group failure proof", e);
        }
        return coordinator.submit(value, true, items -> {
            assertEquals(2, items.size());
            throw new IllegalStateException("injected shared durability failure");
        });
    }

    private static CommitRun runTwoTransactions(
            MvccCommitCoordinator.Mode mode,
            long containerId,
            Path directory) throws Exception {
        Files.createDirectories(directory);
        Path recordingFile = directory.resolve("commit.jfr");
        MvccInheritedTable table = new MvccInheritedTable(0L, containerId, directory, mode);
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();
            DelosStorageTransaction first = table.beginTransaction();
            DelosStorageTransaction second = table.beginTransaction();
            for (long row = 1L; row <= ROWS_PER_TRANSACTION; row++) {
                table.insert(row, emptyRow(), first);
                table.insert(1_000L + row, emptyRow(), second);
            }
            commitTogether(table, first, second);
            table.assertConsistentForTesting();
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }

        MvccInheritedTable reopened = new MvccInheritedTable(0L, containerId, directory, mode);
        try {
            reopened.assertConsistentForTesting();
            return new CommitRun(
                    reopened.pageBackedVisibleRowSummariesForTesting(),
                    reopened.logicalRowCountForTesting(),
                    commitEvents(recordingFile));
        } finally {
            reopened.close();
        }
    }

    private static void commitTogether(
            MvccInheritedTable table,
            DelosStorageTransaction first,
            DelosStorageTransaction second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstCommit = executor.submit(() -> commitAfterSignal(table, first, ready, start));
            Future<?> secondCommit = executor.submit(() -> commitAfterSignal(table, second, ready, start));
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();
            firstCommit.get(60L, TimeUnit.SECONDS);
            secondCommit.get(60L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void commitAfterSignal(
            MvccInheritedTable table,
            DelosStorageTransaction transaction,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            assertTrue(start.await(30L, TimeUnit.SECONDS));
            table.commit(transaction);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before group commit", e);
        }
    }

    private static long forceSum(List<RecordedEvent> events, String field) {
        return events.stream().mapToLong(event -> event.getLong(field)).sum();
    }

    private static List<RecordedEvent> commitEvents(Path recordingFile) throws Exception {
        return RecordingFile.readAllEvents(recordingFile).stream()
                .filter(candidate -> MvccCommitJfr.EVENT_NAME.equals(candidate.getEventType().getName()))
                .toList();
    }

    private static StoreDataValue[] emptyRow() {
        return new StoreDataValue[0];
    }

    private record CommitRun(
            List<String> visibleRows,
            int logicalRows,
            List<RecordedEvent> events) {
        private CommitRun {
            visibleRows = List.copyOf(visibleRows);
            events = List.copyOf(events);
        }
    }
}
