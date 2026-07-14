/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccPaths;

/** Pins the current Phase 7.2 transaction durability protocol before optimization. */
final class MvccTransactionDurabilityProtocolTest {
    @TempDir
    Path databaseDirectory;

    @Test
    void oneRowCommitUsesTheDocumentedAuthoritiesAndOrdering() throws Exception {
        MvccInheritedTable table = new MvccInheritedTable(0L, 721L, databaseDirectory);
        Path recordingFile = databaseDirectory.resolve("one-row-protocol.jfr");
        try {
            commitRowsWithRecording(table, recordingFile, 1);

            RecordedEvent event = onlyCommitEvent(recordingFile);
            assertEquals(1, event.getInt("changedRows"));
            assertEquals(2L, event.getLong("transactionStatusForceCount"));
            assertEquals(1L, event.getLong("transactionOutcomeForceCount"));
            assertEquals(1L, event.getLong("writeAheadLogForceCount"));
            assertEquals(2L, event.getLong("pageVolumeForceCount"));
            assertTrue(event.getBoolean("durabilityMeasurementComplete"));
            assertTrue(event.getBoolean("success"));

            List<String[]> status = fields(transactionStatusFile(721L));
            assertEquals(List.of("ACTIVE", "COMMITTED"), types(status));
            long commitSequence = Long.parseLong(status.get(1)[3]);

            List<String[]> wal = fields(table.writeAheadLogFileForTesting());
            assertEquals(List.of("BEGIN", "INSERT_VERSION", "COMMIT"), typesAt(wal, 2));
            assertEquals(commitSequence, Long.parseLong(wal.get(2)[4]));

            List<String[]> mutation = fields(table.pageMutationLogFileForTesting());
            assertEquals(List.of("BEGIN", "VERSION", "PREPARED"), types(mutation));
            assertEquals(commitSequence, Long.parseLong(mutation.getFirst()[3]));
            assertEquals(1, Integer.parseInt(mutation.getFirst()[4]));
            assertEquals(commitSequence, Long.parseLong(mutation.getLast()[3]));
            assertEquals(1, Integer.parseInt(mutation.getLast()[4]));

            Path outcomeFile = PageVolumeMvccPaths.transactionOutcomeLogFileFor(
                    table.pageVolumeStateFileForTesting());
            List<String[]> outcomes = fields(outcomeFile);
            assertEquals(List.of("COMMIT"), types(outcomes));
            assertEquals(commitSequence, Long.parseLong(outcomes.getFirst()[3]));

            List<String[]> recovery = fields(table.subsystemRecoveryRecordsFileForTesting());
            assertEquals(List.of(
                    "ROW_PAGE",
                    "INDEX_PAGE",
                    "OVERFLOW_PAGE",
                    "FREE_SPACE_MAP",
                    "TRANSACTION_OUTCOME",
                    "CHECKPOINT",
                    "INDEX_PAGE"), typesAt(recovery, 3),
                    "commit records the pre-checkpoint subsystem snapshot, then the post-checkpoint "
                            + "ordered-index rebuild state");
            assertEquals(commitSequence, Long.parseLong(recovery.getFirst()[6]));
            assertEquals(commitSequence, Long.parseLong(recovery.get(4)[6]));
            assertEquals(0L, Long.parseLong(recovery.get(1)[6]),
                    "the pre-rebuild index snapshot is lifecycle metadata, not transaction-correlated");
            assertEquals(0L, Long.parseLong(recovery.getLast()[6]),
                    "the post-rebuild index snapshot is lifecycle metadata, not transaction-correlated");
        } finally {
            table.close();
        }
    }

    @Test
    void multiRowCommitUsesOnePayloadBatchAndOneOutcomeFenceWhilePageForcesRemainPerRow() throws Exception {
        MvccInheritedTable table = new MvccInheritedTable(0L, 722L, databaseDirectory);
        Path recordingFile = databaseDirectory.resolve("eight-row-protocol.jfr");
        try {
            commitRowsWithRecording(table, recordingFile, 8);

            RecordedEvent event = onlyCommitEvent(recordingFile);
            assertEquals(8, event.getInt("changedRows"));
            assertEquals(2L, event.getLong("transactionStatusForceCount"));
            assertEquals(1L, event.getLong("transactionOutcomeForceCount"),
                    "one transaction outcome record is the transaction-complete fence");
            assertEquals(1L, event.getLong("writeAheadLogForceCount"),
                    "the page-volume WAL is already one transaction batch");
            assertEquals(9L, event.getLong("pageVolumeForceCount"),
                    "the current protocol forces once per row plus ordered-index materialization");

            List<String[]> wal = fields(table.writeAheadLogFileForTesting());
            assertEquals(10, wal.size(), "BEGIN + eight row operations + COMMIT");
            assertEquals("BEGIN", wal.getFirst()[2]);
            assertEquals("COMMIT", wal.getLast()[2]);

            List<String[]> mutation = fields(table.pageMutationLogFileForTesting());
            assertEquals(10, mutation.size(), "BEGIN + eight VERSION records + PREPARED");
            assertEquals("BEGIN", mutation.getFirst()[1]);
            assertEquals("PREPARED", mutation.getLast()[1]);
            assertEquals(8, Integer.parseInt(mutation.getFirst()[4]));
            assertEquals(8, Integer.parseInt(mutation.getLast()[4]));
            assertTrue(mutation.subList(1, mutation.size() - 1).stream()
                    .allMatch(record -> "VERSION".equals(record[1])));

            Path outcomeFile = PageVolumeMvccPaths.transactionOutcomeLogFileFor(
                    table.pageVolumeStateFileForTesting());
            List<String[]> outcomes = fields(outcomeFile);
            assertEquals(1, outcomes.size());
            assertEquals("COMMIT", outcomes.getFirst()[1]);
        } finally {
            table.close();
        }
    }

    private void commitRowsWithRecording(MvccInheritedTable table, Path recordingFile, int rowCount)
            throws Exception {
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();
            DelosStorageTransaction transaction = table.beginTransaction();
            for (long rowId = 1L; rowId <= rowCount; rowId++) {
                table.insert(rowId, new StoreDataValue[0], transaction);
            }
            table.commit(transaction);
            recording.stop();
            recording.dump(recordingFile);
        }
    }

    private RecordedEvent onlyCommitEvent(Path recordingFile) throws Exception {
        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(event -> MvccCommitJfr.EVENT_NAME.equals(event.getEventType().getName()))
                .toList();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private Path transactionStatusFile(long containerId) {
        return PageVolumeMvccPaths.inheritedStoreDirectory(databaseDirectory)
                .resolve("conglomerate-0-" + containerId + ".txstatus");
    }

    private static List<String[]> fields(Path path) throws Exception {
        List<String[]> records = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                records.add(line.split("\\t", -1));
            }
        }
        return List.copyOf(records);
    }

    private static List<String> types(List<String[]> records) {
        return typesAt(records, 1);
    }

    private static List<String> typesAt(List<String[]> records, int fieldIndex) {
        return records.stream().map(record -> record[fieldIndex]).toList();
    }
}
