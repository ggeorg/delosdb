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
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the current same-table commit critical section before its lock is split. */
final class MvccSameTableCommitBoundaryAuditTest {
    @TempDir
    Path databaseDirectory;

    @Test
    void currentTableWriteLockContainsEveryMeasuredCommitPhase() throws Exception {
        MvccInheritedTable table = new MvccInheritedTable(0L, 751L, databaseDirectory);
        Path recordingFile = databaseDirectory.resolve("same-table-boundary-audit.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(MvccCommitJfr.EVENT_NAME).withThreshold(Duration.ZERO);
            recording.start();

            DelosStorageTransaction transaction = table.beginTransaction();
            table.insert(1L, row(), transaction);
            table.insert(2L, row(), transaction);
            table.commit(transaction);

            recording.stop();
            recording.dump(recordingFile);
        } finally {
            table.close();
        }

        RecordedEvent event = commitEvent(recordingFile);
        assertTrue(event.getBoolean("success"));
        assertEquals(1, event.getInt("tableDurabilityExecutionConcurrency"));

        long validationNanos = positive(event, "validationNanos");
        long transactionStatusCommitNanos = positive(event, "transactionStatusCommitNanos");
        long pageStatePersistenceNanos = positive(event, "pageStatePersistenceNanos");
        long orderedIndexRebuildNanos = positive(event, "orderedIndexRebuildNanos");
        long transactionStatePublicationNanos = positive(event, "transactionStatePublicationNanos");
        long maintenanceNanos = positive(event, "maintenanceNanos");
        long measuredInsideLock = validationNanos
                + transactionStatusCommitNanos
                + pageStatePersistenceNanos
                + orderedIndexRebuildNanos
                + transactionStatePublicationNanos
                + maintenanceNanos;

        assertTrue(event.getLong("tableLockHoldNanos") >= measuredInsideLock,
                "the current table write lock must enclose every audited commit phase");
        assertEquals(2L, event.getLong("transactionStatusForceCount"));
        assertEquals(1L, event.getLong("transactionOutcomeForceCount"));
        assertEquals(1L, event.getLong("writeAheadLogForceCount"));
        assertEquals(2L, event.getLong("pageVolumeForceCount"));
    }

    private static long positive(RecordedEvent event, String field) {
        long value = event.getLong(field);
        assertTrue(value > 0L, field + " must be measured");
        return value;
    }

    private static RecordedEvent commitEvent(Path recordingFile) throws Exception {
        List<RecordedEvent> events = RecordingFile.readAllEvents(recordingFile).stream()
                .filter(candidate -> MvccCommitJfr.EVENT_NAME.equals(candidate.getEventType().getName()))
                .toList();
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private static StoreDataValue[] row() {
        return new StoreDataValue[0];
    }
}
