/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageMaintenanceTableSnapshot

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

/** Immutable observation of one table registered with database-owned storage maintenance. */
public record DelosStorageMaintenanceTableSnapshot(
        int schemaVersion,
        long segmentId,
        long metadataContainerId,
        long versionContainerId,
        long orderedIndexContainerId,
        boolean active,
        boolean queued,
        boolean running,
        boolean retryRequired,
        long committedChangesSinceLastRun,
        long queuedAtEpochMillis,
        long scheduleCount,
        long runCount,
        long skipCount,
        long failureCount,
        String lastTrigger,
        String lastDecision,
        long lastVacuumHorizon,
        long lastStartedAtEpochMillis,
        long lastCompletedAtEpochMillis,
        int lastRemovedVersions,
        int lastRemovedLogicalRows,
        int remainingVersions,
        int remainingLogicalRows) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DelosStorageMaintenanceTableSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (segmentId < 0L || metadataContainerId < 0L || versionContainerId < 0L
                || orderedIndexContainerId < 0L) {
            throw new IllegalArgumentException("container identities must be non-negative");
        }
        validateNonNegative(
                committedChangesSinceLastRun,
                queuedAtEpochMillis,
                scheduleCount,
                runCount,
                skipCount,
                failureCount,
                lastVacuumHorizon,
                lastStartedAtEpochMillis,
                lastCompletedAtEpochMillis,
                lastRemovedVersions,
                lastRemovedLogicalRows,
                remainingVersions,
                remainingLogicalRows);
        lastTrigger = DelosStorageText.requireNonBlank(
                lastTrigger, "lastTrigger");
        lastDecision = DelosStorageText.requireNonBlank(
                lastDecision, "lastDecision");
        if (running && !active) {
            throw new IllegalArgumentException("an inactive maintenance target cannot be running");
        }
        if (lastCompletedAtEpochMillis > 0L
                && lastStartedAtEpochMillis > lastCompletedAtEpochMillis) {
            throw new IllegalArgumentException("maintenance completion precedes start");
        }
    }

    public String tableIdentity() {
        return "segment-" + segmentId + "/container-" + metadataContainerId;
    }


    private static void validateNonNegative(long... values) {
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException("maintenance counters must be non-negative");
            }
        }
    }
}
