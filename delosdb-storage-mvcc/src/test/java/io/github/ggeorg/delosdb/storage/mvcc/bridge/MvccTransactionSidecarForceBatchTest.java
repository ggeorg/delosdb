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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 7.7 proofs for transaction-level row-directory and free-space-map forcing. */
final class MvccTransactionSidecarForceBatchTest {
    @TempDir
    Path databaseDirectory;

    @Test
    void multiRowCommitDoesNotAddPerRowSidecarForces() throws Exception {
        RecordedEvent oneRow = commitWithRecording(751L, 1);
        RecordedEvent eightRows = commitWithRecording(752L, 8);

        assertEquals(1, oneRow.getInt("changedRows"));
        assertEquals(8, eightRows.getInt("changedRows"));
        assertEquals(oneRow.getLong("otherSidecarForceCount"),
                eightRows.getLong("otherSidecarForceCount"),
                "row-directory and free-space-map publication must not add one force per changed row");
        assertEquals(2L, eightRows.getLong("transactionStatusForceCount"));
        assertEquals(1L, eightRows.getLong("transactionOutcomeForceCount"));
        assertEquals(1L, eightRows.getLong("writeAheadLogForceCount"));
        assertEquals(2L, eightRows.getLong("pageVolumeForceCount"));
        assertTrue(eightRows.getLong("otherSidecarForceCount") > 0L);
    }

    @Test
    void tornRowDirectoryBatchTailIsDiscardedAndReconciledOnReopen() throws Exception {
        MvccInheritedTable table = new MvccInheritedTable(0L, 753L, databaseDirectory);
        Path rowDirectory = table.rowDirectoryStateFileForTesting();
        try {
            commitRows(table, 8);
        } finally {
            table.close();
        }

        Files.writeString(
                rowDirectory,
                "1\t999\tincomplete-row-directory-batch",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        MvccInheritedTable reopened = new MvccInheritedTable(0L, 753L, databaseDirectory);
        try {
            assertEquals(8, reopened.logicalRowCountForTesting());
            assertEquals(8, reopened.physicalVersionCountForTesting());
            assertEquals(0, reopened.consistencyErrorCountForTesting(),
                    reopened.consistencySummaryForTesting());
        } finally {
            reopened.close();
        }

        String repaired = Files.readString(rowDirectory, StandardCharsets.UTF_8);
        assertFalse(repaired.contains("incomplete-row-directory-batch"));
        assertTrue(repaired.endsWith(System.lineSeparator()));
        assertEquals(8L, repaired.lines().filter(line -> !line.isBlank()).count());
    }

    private RecordedEvent commitWithRecording(long containerId, int rowCount) throws Exception {
        MvccInheritedTable table = new MvccInheritedTable(0L, containerId, databaseDirectory);
        Path recordingFile = databaseDirectory.resolve("sidecar-batch-" + containerId + ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();
            commitRows(table, rowCount);
            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(event -> MvccCommitJfr.EVENT_NAME.equals(event.getEventType().getName()))
                .toList();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private static void commitRows(MvccInheritedTable table, int rowCount) {
        DelosStorageTransaction transaction = table.beginTransaction();
        for (long rowId = 1L; rowId <= rowCount; rowId++) {
            table.insert(rowId, new StoreDataValue[0], transaction);
        }
        table.commit(transaction);
    }
}
