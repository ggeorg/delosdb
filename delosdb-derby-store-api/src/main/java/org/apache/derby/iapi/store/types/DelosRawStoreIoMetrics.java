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

    private final Object nativeMemoryMonitor = new Object();
    private boolean nativeMemoryEnabled;
    private long nativeMemoryLimitBytes;
    private long currentNativeMemoryBytes;
    private long peakNativeMemoryBytes;
    private long nativeBufferAllocations;
    private long nativeBufferReleases;
    private long nativeBufferFallbacks;
    private long nativeBufferReleaseFailures;
    private long nativePageReadOperations;
    private long nativePageReadBytes;
    private long nativePageWriteOperations;
    private long nativePageWriteBytes;
    private long currentNativeBuffers;
    private long peakNativeBuffers;
    private long unclosedNativeBuffersAtShutdown;
    private long unreleasedNativeMemoryBytesAtShutdown;

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

    public void nativeMemoryBound(long hardLimitBytes) {
        if (hardLimitBytes < 0L) {
            throw new IllegalArgumentException(
                    "native-memory hard limit must not be negative");
        }
        synchronized (nativeMemoryMonitor) {
            if (nativeMemoryLimitBytes != 0L || nativeMemoryEnabled
                    || nativeBufferAllocations != 0L) {
                throw new IllegalStateException(
                        "native-memory accounting is already bound");
            }
            nativeMemoryLimitBytes = hardLimitBytes;
            nativeMemoryEnabled = hardLimitBytes > 0L;
        }
    }

    public void nativeBufferAllocated(long bytes) {
        long allocatedBytes = requirePositive(bytes, "native buffer bytes");
        synchronized (nativeMemoryMonitor) {
            if (!nativeMemoryEnabled) {
                throw new IllegalStateException(
                        "native buffer allocated while native memory is disabled");
            }
            long updatedBytes = Math.addExact(
                    currentNativeMemoryBytes, allocatedBytes);
            if (updatedBytes > nativeMemoryLimitBytes) {
                throw new IllegalStateException(
                        "native buffer allocation exceeds the database hard limit");
            }
            currentNativeMemoryBytes = updatedBytes;
            peakNativeMemoryBytes = Math.max(
                    peakNativeMemoryBytes, currentNativeMemoryBytes);
            nativeBufferAllocations++;
            currentNativeBuffers++;
            peakNativeBuffers = Math.max(
                    peakNativeBuffers, currentNativeBuffers);
        }
    }

    public void nativeBufferReleased(long bytes) {
        long releasedBytes = requirePositive(bytes, "released native buffer bytes");
        synchronized (nativeMemoryMonitor) {
            if (releasedBytes > currentNativeMemoryBytes
                    || currentNativeBuffers == 0L) {
                throw new IllegalStateException(
                        "Unbalanced native page-buffer release accounting");
            }
            currentNativeMemoryBytes -= releasedBytes;
            currentNativeBuffers--;
            nativeBufferReleases++;
        }
    }

    public void nativeBufferFallback() {
        synchronized (nativeMemoryMonitor) {
            nativeBufferFallbacks++;
        }
    }

    public void nativeBufferReleaseFailed() {
        synchronized (nativeMemoryMonitor) {
            nativeBufferReleaseFailures++;
        }
    }

    public void nativePageReadSucceeded(long bytes) {
        long completedBytes = requirePositive(bytes, "native page read bytes");
        synchronized (nativeMemoryMonitor) {
            nativePageReadOperations++;
            nativePageReadBytes = Math.addExact(nativePageReadBytes, completedBytes);
        }
    }

    public void nativePageWriteSucceeded(long bytes) {
        long completedBytes = requirePositive(bytes, "native page write bytes");
        synchronized (nativeMemoryMonitor) {
            nativePageWriteOperations++;
            nativePageWriteBytes = Math.addExact(nativePageWriteBytes, completedBytes);
        }
    }

    public void nativeMemoryShutdown(
            long unclosedBuffers,
            long unreleasedBytes) {
        if (unclosedBuffers < 0L || unreleasedBytes < 0L) {
            throw new IllegalArgumentException(
                    "native shutdown leak values must not be negative");
        }
        synchronized (nativeMemoryMonitor) {
            unclosedNativeBuffersAtShutdown = unclosedBuffers;
            unreleasedNativeMemoryBytesAtShutdown = unreleasedBytes;
        }
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
        boolean observedNativeMemoryEnabled;
        long observedNativeMemoryLimitBytes;
        long observedCurrentNativeMemoryBytes;
        long observedPeakNativeMemoryBytes;
        long observedNativeBufferAllocations;
        long observedNativeBufferReleases;
        long observedNativeBufferFallbacks;
        long observedNativeBufferReleaseFailures;
        long observedNativePageReadOperations;
        long observedNativePageReadBytes;
        long observedNativePageWriteOperations;
        long observedNativePageWriteBytes;
        long observedCurrentNativeBuffers;
        long observedPeakNativeBuffers;
        long observedUnclosedNativeBuffersAtShutdown;
        long observedUnreleasedNativeMemoryBytesAtShutdown;
        synchronized (nativeMemoryMonitor) {
            observedNativeMemoryEnabled = nativeMemoryEnabled;
            observedNativeMemoryLimitBytes = nativeMemoryLimitBytes;
            observedCurrentNativeMemoryBytes = currentNativeMemoryBytes;
            observedPeakNativeMemoryBytes = Math.max(
                    peakNativeMemoryBytes, currentNativeMemoryBytes);
            observedNativeBufferAllocations = nativeBufferAllocations;
            observedNativeBufferReleases = nativeBufferReleases;
            observedNativeBufferFallbacks = nativeBufferFallbacks;
            observedNativeBufferReleaseFailures = nativeBufferReleaseFailures;
            observedNativePageReadOperations = nativePageReadOperations;
            observedNativePageReadBytes = nativePageReadBytes;
            observedNativePageWriteOperations = nativePageWriteOperations;
            observedNativePageWriteBytes = nativePageWriteBytes;
            observedCurrentNativeBuffers = currentNativeBuffers;
            observedPeakNativeBuffers = Math.max(
                    peakNativeBuffers, currentNativeBuffers);
            observedUnclosedNativeBuffersAtShutdown =
                    unclosedNativeBuffersAtShutdown;
            observedUnreleasedNativeMemoryBytesAtShutdown =
                    unreleasedNativeMemoryBytesAtShutdown;
        }
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
                unclosedContainerHandlesAtShutdown.get(),
                observedNativeMemoryEnabled,
                observedNativeMemoryLimitBytes,
                observedCurrentNativeMemoryBytes,
                observedPeakNativeMemoryBytes,
                observedNativeBufferAllocations,
                observedNativeBufferReleases,
                observedNativeBufferFallbacks,
                observedNativeBufferReleaseFailures,
                observedNativePageReadOperations,
                observedNativePageReadBytes,
                observedNativePageWriteOperations,
                observedNativePageWriteBytes,
                observedCurrentNativeBuffers,
                observedPeakNativeBuffers,
                observedUnclosedNativeBuffersAtShutdown,
                observedUnreleasedNativeMemoryBytesAtShutdown);
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
