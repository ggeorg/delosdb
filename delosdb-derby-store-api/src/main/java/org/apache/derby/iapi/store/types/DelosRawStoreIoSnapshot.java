/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.util.Objects;

/** Immutable database-scoped observation of shared RawStore page I/O. */
public record DelosRawStoreIoSnapshot(
        int schemaVersion,
        String databaseIdentity,
        boolean runtimeActive,
        boolean memoryDatabase,
        long pageReadOperations,
        long pageReadBytes,
        long pageWriteOperations,
        long pageWriteBytes,
        long contentOnlyForceOperations,
        long metadataForceOperations,
        long pageReadFailures,
        long pageWriteFailures,
        long forceFailures,
        long closedChannelDetections,
        long channelRecoveryAttempts,
        long successfulChannelReopens,
        long failedChannelReopens,
        long currentInFlightPageIo,
        long peakInFlightPageIo,
        long currentOpenContainerHandles,
        long peakOpenContainerHandles,
        long unclosedContainerHandlesAtShutdown) {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    public DelosRawStoreIoSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        databaseIdentity = requireNonBlank(databaseIdentity, "databaseIdentity");
        long[] values = {
                pageReadOperations,
                pageReadBytes,
                pageWriteOperations,
                pageWriteBytes,
                contentOnlyForceOperations,
                metadataForceOperations,
                pageReadFailures,
                pageWriteFailures,
                forceFailures,
                closedChannelDetections,
                channelRecoveryAttempts,
                successfulChannelReopens,
                failedChannelReopens,
                currentInFlightPageIo,
                peakInFlightPageIo,
                currentOpenContainerHandles,
                peakOpenContainerHandles,
                unclosedContainerHandlesAtShutdown
        };
        for (long value : values) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        "RawStore I/O snapshot values must be non-negative");
            }
        }
        if (currentInFlightPageIo > peakInFlightPageIo) {
            throw new IllegalArgumentException(
                    "current in-flight page I/O exceeds its peak");
        }
        if (currentOpenContainerHandles > peakOpenContainerHandles) {
            throw new IllegalArgumentException(
                    "current open container handles exceed their peak");
        }
        if (successfulChannelReopens + failedChannelReopens
                > channelRecoveryAttempts) {
            throw new IllegalArgumentException(
                    "channel reopen outcomes exceed recovery attempts");
        }
        if (runtimeActive && unclosedContainerHandlesAtShutdown != 0L) {
            throw new IllegalArgumentException(
                    "an active runtime cannot report shutdown leaks");
        }
    }

    public static DelosRawStoreIoSnapshot unavailable() {
        return new DelosRawStoreIoSnapshot(
                CURRENT_SCHEMA_VERSION,
                "<unbound>",
                false,
                false,
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
                0L,
                0L,
                0L,
                0L,
                0L,
                0L);
    }

    public long totalForceOperations() {
        return contentOnlyForceOperations + metadataForceOperations;
    }

    public long totalPageOperations() {
        return pageReadOperations + pageWriteOperations;
    }

    public long totalPageBytes() {
        return pageReadBytes + pageWriteBytes;
    }

    private static String requireNonBlank(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
