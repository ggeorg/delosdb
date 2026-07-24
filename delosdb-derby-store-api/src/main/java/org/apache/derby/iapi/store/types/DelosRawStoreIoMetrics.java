/*

   Derby - Class org.apache.derby.iapi.store.types.DelosRawStoreIoMetrics

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.types;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Database-owned, bounded counters for the shared RawStore page-I/O path. */
public final class DelosRawStoreIoMetrics {
    private final AtomicBoolean bound = new AtomicBoolean();
    private final AtomicBoolean runtimeActive = new AtomicBoolean();
    private final AtomicLong pageReadOperations = new AtomicLong();
    private final AtomicLong pageReadBytes = new AtomicLong();
    private final AtomicLong pageWriteOperations = new AtomicLong();
    private final AtomicLong pageWriteBytes = new AtomicLong();
    private final AtomicLong contentOnlyForceOperations = new AtomicLong();
    private final AtomicLong metadataForceOperations = new AtomicLong();
    private final AtomicLong pageReadFailures = new AtomicLong();
    private final AtomicLong pageWriteFailures = new AtomicLong();
    private final AtomicLong forceFailures = new AtomicLong();
    private final AtomicLong closedChannelDetections = new AtomicLong();
    private final AtomicLong channelRecoveryAttempts = new AtomicLong();
    private final AtomicLong successfulChannelReopens = new AtomicLong();
    private final AtomicLong failedChannelReopens = new AtomicLong();
    private final AtomicLong currentInFlightPageIo = new AtomicLong();
    private final AtomicLong peakInFlightPageIo = new AtomicLong();
    private final AtomicLong currentOpenContainerHandles = new AtomicLong();
    private final AtomicLong peakOpenContainerHandles = new AtomicLong();
    private final AtomicLong unclosedContainerHandlesAtShutdown = new AtomicLong();

    private volatile String databaseIdentity = "<unbound>";
    private volatile boolean memoryDatabase;

    public void bind(String identity, boolean memory) {
        String normalized = requireIdentity(identity);
        if (bound.compareAndSet(false, true)) {
            databaseIdentity = normalized;
            memoryDatabase = memory;
            runtimeActive.set(true);
            return;
        }
        if (!databaseIdentity.equals(normalized) || memoryDatabase != memory) {
            throw new IllegalStateException(
                    "RawStore I/O metrics are already bound to " + databaseIdentity);
        }
        if (!runtimeActive.get()) {
            throw new IllegalStateException(
                    "RawStore I/O metrics cannot be rebound after shutdown");
        }
    }

    public void pageIoStarted() {
        long current = currentInFlightPageIo.incrementAndGet();
        updatePeak(peakInFlightPageIo, current);
    }

    public void pageIoFinished() {
        decrementNonNegative(currentInFlightPageIo, "in-flight page I/O");
    }

    public void pageReadSucceeded(long bytes) {
        long completedBytes = requirePositive(bytes, "page read bytes");
        pageReadOperations.incrementAndGet();
        pageReadBytes.addAndGet(completedBytes);
    }

    public void pageReadFailed() {
        pageReadFailures.incrementAndGet();
    }

    public void pageWriteSucceeded(long bytes) {
        long completedBytes = requirePositive(bytes, "page write bytes");
        pageWriteOperations.incrementAndGet();
        pageWriteBytes.addAndGet(completedBytes);
    }

    public void pageWriteFailed() {
        pageWriteFailures.incrementAndGet();
    }

    public void forceSucceeded(boolean metadata) {
        (metadata ? metadataForceOperations : contentOnlyForceOperations)
                .incrementAndGet();
    }

    public void forceFailed() {
        forceFailures.incrementAndGet();
    }

    public void closedChannelDetected() {
        closedChannelDetections.incrementAndGet();
    }

    public void channelRecoveryAttempted() {
        channelRecoveryAttempts.incrementAndGet();
    }

    public void channelReopenSucceeded() {
        successfulChannelReopens.incrementAndGet();
    }

    public void channelReopenFailed() {
        failedChannelReopens.incrementAndGet();
    }

    public void containerHandleOpened() {
        long current = currentOpenContainerHandles.incrementAndGet();
        updatePeak(peakOpenContainerHandles, current);
    }

    public void containerHandleClosed() {
        decrementNonNegative(currentOpenContainerHandles, "open container handles");
    }

    public void shutdown() {
        if (!runtimeActive.compareAndSet(true, false)) {
            return;
        }
        unclosedContainerHandlesAtShutdown.set(currentOpenContainerHandles.get());
    }

    public DelosRawStoreIoSnapshot snapshot() {
        long inFlightPageIo = currentInFlightPageIo.get();
        long observedPeakInFlightPageIo = Math.max(
                peakInFlightPageIo.get(), inFlightPageIo);
        long openContainerHandles = currentOpenContainerHandles.get();
        long observedPeakOpenContainerHandles = Math.max(
                peakOpenContainerHandles.get(), openContainerHandles);
        return new DelosRawStoreIoSnapshot(
                DelosRawStoreIoSnapshot.CURRENT_SCHEMA_VERSION,
                databaseIdentity,
                runtimeActive.get(),
                memoryDatabase,
                pageReadOperations.get(),
                pageReadBytes.get(),
                pageWriteOperations.get(),
                pageWriteBytes.get(),
                contentOnlyForceOperations.get(),
                metadataForceOperations.get(),
                pageReadFailures.get(),
                pageWriteFailures.get(),
                forceFailures.get(),
                closedChannelDetections.get(),
                channelRecoveryAttempts.get(),
                successfulChannelReopens.get(),
                failedChannelReopens.get(),
                inFlightPageIo,
                observedPeakInFlightPageIo,
                openContainerHandles,
                observedPeakOpenContainerHandles,
                unclosedContainerHandlesAtShutdown.get());
    }

    public String databaseIdentity() {
        return databaseIdentity;
    }

    private static void updatePeak(AtomicLong peak, long candidate) {
        long observed;
        do {
            observed = peak.get();
            if (candidate <= observed) {
                return;
            }
        } while (!peak.compareAndSet(observed, candidate));
    }

    private static void decrementNonNegative(AtomicLong counter, String label) {
        long updated = counter.decrementAndGet();
        if (updated < 0L) {
            counter.incrementAndGet();
            throw new IllegalStateException("Unbalanced " + label + " accounting");
        }
    }

    private static long requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static String requireIdentity(String value) {
        String normalized = Objects.requireNonNull(value, "identity").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
        return normalized;
    }
}
