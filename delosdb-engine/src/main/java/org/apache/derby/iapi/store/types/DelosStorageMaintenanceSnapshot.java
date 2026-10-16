/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.util.List;
import java.util.Objects;

/** Versioned immutable database-scoped storage-maintenance observation. */
public record DelosStorageMaintenanceSnapshot(
        int schemaVersion,
        String providerId,
        String databaseIdentity,
        String storageMode,
        String collectionSemantics,
        long captureSequence,
        long capturedAtEpochMillis,
        boolean runtimeActive,
        boolean maintenanceEnabled,
        boolean readOnly,
        boolean accepting,
        int workerCount,
        int registeredTableCount,
        int queuedTableCount,
        long oldestQueuedAtEpochMillis,
        long oldestQueuedAgeMillis,
        int activeWorkerCount,
        int maximumActiveWorkerCount,
        long commitWakeupCount,
        long notificationFailureCount,
        long periodicScanCount,
        long scheduledRunCount,
        long completedRunCount,
        long skippedRunCount,
        long failedRunCount,
        long mutatedRunCount,
        long removedVersionCount,
        long removedLogicalRowCount,
        long publishedCommitHighWater,
        long vacuumHorizon,
        int retainedSnapshotCount,
        long oldestRetainedSnapshot,
        int activeWriterTransactionCount,
        int tableSnapshotCapacity,
        long tableSnapshotDroppedCount,
        List<DelosStorageMaintenanceTableSnapshot> tableSnapshots) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String RAWSTORE_MVCC_MODE = "RAWSTORE_MVCC";
    public static final String IMMUTABLE_COLLECTION =
            "atomic-runtime-horizon-with-bounded-maintenance-table-observations";

    public DelosStorageMaintenanceSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        providerId = DelosStorageProviderIds.normalize(providerId);
        databaseIdentity = DelosStorageText.requireNonBlank(
                databaseIdentity, "databaseIdentity");
        storageMode = DelosStorageText.requireNonBlank(storageMode, "storageMode");
        collectionSemantics = DelosStorageText.requireNonBlank(
                collectionSemantics, "collectionSemantics");
        validateNonNegative(
                captureSequence,
                capturedAtEpochMillis,
                workerCount,
                registeredTableCount,
                queuedTableCount,
                oldestQueuedAtEpochMillis,
                oldestQueuedAgeMillis,
                activeWorkerCount,
                maximumActiveWorkerCount,
                commitWakeupCount,
                notificationFailureCount,
                periodicScanCount,
                scheduledRunCount,
                completedRunCount,
                skippedRunCount,
                failedRunCount,
                mutatedRunCount,
                removedVersionCount,
                removedLogicalRowCount,
                publishedCommitHighWater,
                vacuumHorizon,
                retainedSnapshotCount,
                oldestRetainedSnapshot,
                activeWriterTransactionCount,
                tableSnapshotCapacity,
                tableSnapshotDroppedCount);
        if (queuedTableCount > registeredTableCount
                || activeWorkerCount > workerCount
                || maximumActiveWorkerCount > workerCount) {
            throw new IllegalArgumentException("maintenance worker/table counts are inconsistent");
        }
        if ((!maintenanceEnabled && (accepting || workerCount != 0))
                || (readOnly && maintenanceEnabled)) {
            throw new IllegalArgumentException("maintenance enable/read-only state is inconsistent");
        }
        if ((queuedTableCount == 0
                        && (oldestQueuedAtEpochMillis != 0L || oldestQueuedAgeMillis != 0L))
                || (queuedTableCount > 0 && oldestQueuedAtEpochMillis == 0L)) {
            throw new IllegalArgumentException("maintenance queue-age evidence is inconsistent");
        }
        if (vacuumHorizon > publishedCommitHighWater) {
            throw new IllegalArgumentException("vacuum horizon is ahead of published high-water");
        }
        if (oldestRetainedSnapshot != vacuumHorizon) {
            throw new IllegalArgumentException("oldest retained snapshot must equal vacuum horizon");
        }
        if (retainedSnapshotCount == 0 && oldestRetainedSnapshot != publishedCommitHighWater) {
            throw new IllegalArgumentException(
                    "without retained snapshots the oldest snapshot must equal published high-water");
        }
        tableSnapshots = List.copyOf(Objects.requireNonNull(tableSnapshots, "tableSnapshots"));
        if (tableSnapshots.size() > tableSnapshotCapacity
                || registeredTableCount != tableSnapshots.size() + tableSnapshotDroppedCount) {
            throw new IllegalArgumentException("maintenance table snapshot bounds are inconsistent");
        }
    }

    public static DelosStorageMaintenanceSnapshot unavailable(String providerId) {
        return new DelosStorageMaintenanceSnapshot(
                CURRENT_SCHEMA_VERSION,
                providerId,
                "<unbound>",
                RAWSTORE_MVCC_MODE,
                IMMUTABLE_COLLECTION,
                0L,
                System.currentTimeMillis(),
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                0L,
                0L,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0L,
                0,
                0,
                0L,
                List.of());
    }


    private static void validateNonNegative(long... values) {
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException("maintenance snapshot values must be non-negative");
            }
        }
    }
}
