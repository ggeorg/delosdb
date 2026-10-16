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
        databaseIdentity = DelosStorageText.requireNonBlank(
                databaseIdentity, "databaseIdentity");
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

    static DelosRawStoreIoSnapshot capture(
            String databaseIdentity,
            boolean runtimeActive,
            boolean memoryDatabase,
            PageIo pageIo,
            ForceIo forceIo,
            ChannelRecovery channelRecovery,
            RuntimeState runtimeState) {
        Objects.requireNonNull(pageIo, "pageIo");
        Objects.requireNonNull(forceIo, "forceIo");
        Objects.requireNonNull(channelRecovery, "channelRecovery");
        Objects.requireNonNull(runtimeState, "runtimeState");
        return new DelosRawStoreIoSnapshot(
                CURRENT_SCHEMA_VERSION,
                databaseIdentity,
                runtimeActive,
                memoryDatabase,
                pageIo.readOperations(),
                pageIo.readBytes(),
                pageIo.writeOperations(),
                pageIo.writeBytes(),
                forceIo.contentOnlyOperations(),
                forceIo.metadataOperations(),
                pageIo.readFailures(),
                pageIo.writeFailures(),
                forceIo.failures(),
                channelRecovery.closedChannelDetections(),
                channelRecovery.attempts(),
                channelRecovery.successes(),
                channelRecovery.failures(),
                runtimeState.currentInFlightPageIo(),
                runtimeState.peakInFlightPageIo(),
                runtimeState.currentOpenContainerHandles(),
                runtimeState.peakOpenContainerHandles(),
                runtimeState.unclosedContainerHandlesAtShutdown());
    }

    public static DelosRawStoreIoSnapshot unavailable() {
        return capture(
                "<unbound>",
                false,
                false,
                PageIo.EMPTY,
                ForceIo.EMPTY,
                ChannelRecovery.EMPTY,
                RuntimeState.EMPTY);
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

    record PageIo(
            long readOperations,
            long readBytes,
            long writeOperations,
            long writeBytes,
            long readFailures,
            long writeFailures) {
        private static final PageIo EMPTY = new PageIo(0L, 0L, 0L, 0L, 0L, 0L);
    }

    record ForceIo(
            long contentOnlyOperations,
            long metadataOperations,
            long failures) {
        private static final ForceIo EMPTY = new ForceIo(0L, 0L, 0L);
    }

    record ChannelRecovery(
            long closedChannelDetections,
            long attempts,
            long successes,
            long failures) {
        private static final ChannelRecovery EMPTY =
                new ChannelRecovery(0L, 0L, 0L, 0L);
    }

    record RuntimeState(
            long currentInFlightPageIo,
            long peakInFlightPageIo,
            long currentOpenContainerHandles,
            long peakOpenContainerHandles,
            long unclosedContainerHandlesAtShutdown) {
        private static final RuntimeState EMPTY =
                new RuntimeState(0L, 0L, 0L, 0L, 0L);
    }

}
