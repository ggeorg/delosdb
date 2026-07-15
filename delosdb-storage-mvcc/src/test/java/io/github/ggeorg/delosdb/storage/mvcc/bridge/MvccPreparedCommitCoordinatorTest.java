/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves concurrent immutable preparation with ordered same-table publication. */
final class MvccPreparedCommitCoordinatorTest {
    private static final int ROWS_PER_TRANSACTION = 512;

    @TempDir
    Path databaseDirectory;

    @Test
    void nonConflictingWritersPrepareConcurrentlyAndPublishSerially() throws Exception {
        String legacyMode = System.getProperty("delosdb.mvcc.commit.mode");
        System.setProperty("delosdb.mvcc.commit.mode", "direct");
        Path recordingFile = databaseDirectory.resolve("prepared-commit-coordinator.jfr");
        try {
            MvccInheritedTable table = new MvccInheritedTable(0L, 761L, databaseDirectory);
            try (Recording recording = new Recording()) {
                recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
                recording.start();

                DelosStorageTransaction first = table.beginTransaction();
                DelosStorageTransaction second = table.beginTransaction();
                for (int row = 1; row <= ROWS_PER_TRANSACTION; row++) {
                    table.insert(row, emptyRow(), first);
                    table.insert(ROWS_PER_TRANSACTION + row, emptyRow(), second);
                }
                commitTogether(table, first, second);
                table.assertConsistentForTesting();
                assertEquals(ROWS_PER_TRANSACTION * 2, table.logicalRowCountForTesting());

                recording.stop();
                recording.dump(recordingFile);
            } finally {
                table.close();
            }
        } finally {
            if (legacyMode == null) {
                System.clearProperty("delosdb.mvcc.commit.mode");
            } else {
                System.setProperty("delosdb.mvcc.commit.mode", legacyMode);
            }
        }

        List<RecordedEvent> events = commitEvents(recordingFile);
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> event.getBoolean("success")));
        assertEquals(2, events.stream()
                .mapToInt(event -> event.getInt("tablePreparationConcurrency"))
                .max()
                .orElseThrow(),
                "same-table immutable preparation must overlap");
        assertEquals(1, events.stream()
                .mapToInt(event -> event.getInt("tableDurabilityExecutionConcurrency"))
                .max()
                .orElseThrow(),
                "physical same-table publication remains serialized");
        assertTrue(events.stream().allMatch(event ->
                "group".equals(event.getString("durabilityCoordinatorMode"))),
                "normal table construction must use group mode and ignore the retired JVM property");
        assertEquals(2, events.stream()
                .mapToInt(event -> event.getInt("durabilityEnrollmentDepth"))
                .max()
                .orElseThrow(),
                "both prepared commits must be enrolled before physical publication completes");
        assertTrue(events.stream().allMatch(event -> event.getLong("preparationNanos") > 0L));
        assertTrue(events.stream().allMatch(event -> event.getLong("durabilityCoordinatorHoldNanos")
                >= event.getLong("tableLockHoldNanos")));
        assertTrue(events.stream().anyMatch(event -> event.getLong("durabilityCoordinatorWaitNanos") > 0L));
        assertTrue(events.stream().allMatch(event -> event.getInt("groupCommitSize") == 2));
        assertEquals(1L, events.stream().filter(event -> event.getBoolean("groupCommitLeader")).count());
        assertEquals(3L, events.stream().mapToLong(event ->
                event.getLong("transactionStatusForceCount")).sum(),
                "two ACTIVE forces plus one shared COMMITTED force are expected");
        assertTrue(events.stream().mapToLong(event ->
                event.getLong("groupCommitSharedForceCount")).sum() > 0L);
        for (RecordedEvent event : events) {
            assertEquals(ROWS_PER_TRANSACTION, event.getInt("changedRows"));
            assertEquals(1L, event.getLong("transactionOutcomeForceCount"));
            assertEquals(1L, event.getLong("writeAheadLogForceCount"));
        }
        assertEquals(3L, events.stream().mapToLong(event ->
                event.getLong("pageVolumeForceCount")).sum(),
                "two main-table page forces plus one shared ordered-index force are expected");
        assertEquals(1L, events.stream().filter(event ->
                event.getLong("pageVolumeForceCount") == 2L).count(),
                "exactly one group member must own the shared ordered-index force");
        assertEquals(1L, events.stream().filter(event ->
                event.getLong("pageVolumeForceCount") == 1L).count(),
                "the other group member must retain only its main-table page force");
    }

    @Test
    void sameRowPreparedCommitsStillHaveOneDeterministicWinner() {
        MvccInheritedTable table = new MvccInheritedTable(0L, 762L, databaseDirectory);
        try {
            DelosStorageTransaction first = table.beginTransaction();
            DelosStorageTransaction second = table.beginTransaction();
            table.insert(1L, emptyRow(), first);
            table.insert(1L, emptyRow(), second);

            assertThrows(MvccWriteConflictException.class, () -> table.commit(first));
            table.commit(second);

            assertEquals(1, table.logicalRowCountForTesting());
            table.assertConsistentForTesting();
        } finally {
            table.close();
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
            assertTrue(ready.await(30L, TimeUnit.SECONDS),
                    "prepared-commit workers did not reach the start barrier");
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
            start.await();
            table.commit(transaction);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while starting prepared commit", e);
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
}
