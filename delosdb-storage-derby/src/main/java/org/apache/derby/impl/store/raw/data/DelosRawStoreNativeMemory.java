/*

   Derby - Class org.apache.derby.impl.store.raw.data.DelosRawStoreNativeMemory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.derby.iapi.store.types.DelosRawStoreIoMetrics;

/**
 * Database-owned native page-I/O mirror allocator.
 *
 * <p>The inherited byte array remains the page-codec and cache authority. A native lease is an
 * optional physical-I/O mirror with a hard database-scoped byte limit. The production default is
 * disabled. Focused tests can arm one exact database identity through the package-private planning
 * directory; no SQL, connection attribute, system property, provider, or service surface exists.</p>
 */
final class DelosRawStoreNativeMemory {
    static final long DEFAULT_LIMIT_BYTES = 0L;

    private final DelosRawStoreIoMetrics metrics;
    private final Map<Lease, Boolean> activeLeases = new IdentityHashMap<>();

    private boolean bound;
    private boolean accepting;
    private long hardLimitBytes;
    private long reservedBytes;

    DelosRawStoreNativeMemory(DelosRawStoreIoMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    synchronized void bind(
            String databaseIdentity,
            boolean memoryDatabase,
            boolean nativeSegmentsSupported,
            long requestedHardLimitBytes) {
        Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        if (bound) {
            throw new IllegalStateException("Native page memory is already bound");
        }
        if (requestedHardLimitBytes < 0L) {
            throw new IllegalArgumentException(
                    "Native-memory hard limit must not be negative");
        }

        bound = true;
        hardLimitBytes = memoryDatabase || !nativeSegmentsSupported
                ? DEFAULT_LIMIT_BYTES
                : requestedHardLimitBytes;
        accepting = hardLimitBytes > 0L;
        metrics.nativeMemoryBound(hardLimitBytes);
    }

    synchronized Lease allocate(long byteSize) {
        if (byteSize <= 0L) {
            throw new IllegalArgumentException(
                    "Native page-buffer size must be positive");
        }
        if (!accepting) {
            return null;
        }
        if (byteSize > hardLimitBytes - reservedBytes) {
            metrics.nativeBufferFallback();
            return null;
        }

        Arena arena = null;
        try {
            arena = Arena.ofShared();
            MemorySegment segment = arena.allocate(byteSize, 8L);
            Lease lease = new Lease(this, arena, segment, byteSize);
            activeLeases.put(lease, Boolean.TRUE);
            reservedBytes += byteSize;
            metrics.nativeBufferAllocated(byteSize);
            return lease;
        } catch (OutOfMemoryError allocationFailure) {
            if (arena != null) {
                try {
                    arena.close();
                } catch (RuntimeException ignored) {
                    allocationFailure.addSuppressed(ignored);
                }
            }
            metrics.nativeBufferFallback();
            return null;
        }
    }

    synchronized boolean enabled() {
        return accepting;
    }

    void shutdown() {
        List<Lease> leaked;
        long leakedBytes;
        synchronized (this) {
            if (!bound) {
                return;
            }
            accepting = false;
            leaked = new ArrayList<>(activeLeases.keySet());
            leakedBytes = reservedBytes;
        }

        for (Lease lease : leaked) {
            try {
                lease.close();
            } catch (RuntimeException ignored) {
                // release() records the failure and leaves the lease visible.
            }
        }

        synchronized (this) {
            metrics.nativeMemoryShutdown(leaked.size(), leakedBytes);
        }
    }

    private void release(Lease lease) {
        long byteSize;
        synchronized (lease) {
            if (lease.closed) {
                return;
            }
            try {
                lease.arena.close();
            } catch (RuntimeException closeFailure) {
                metrics.nativeBufferReleaseFailed();
                throw closeFailure;
            }
            lease.closed = true;
            byteSize = lease.byteSize;
        }

        synchronized (this) {
            if (activeLeases.remove(lease) == null) {
                throw new IllegalStateException(
                        "Native page-buffer lease was not owned by this database");
            }
            reservedBytes = Math.subtractExact(reservedBytes, byteSize);
            metrics.nativeBufferReleased(byteSize);
        }
    }

    static final class Lease implements AutoCloseable {
        private final DelosRawStoreNativeMemory owner;
        private final Arena arena;
        private final MemorySegment segment;
        private final long byteSize;
        private boolean closed;

        private Lease(
                DelosRawStoreNativeMemory owner,
                Arena arena,
                MemorySegment segment,
                long byteSize) {
            this.owner = owner;
            this.arena = arena;
            this.segment = segment;
            this.byteSize = byteSize;
        }

        MemorySegment segment() {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException(
                            "Native page-buffer lease is closed");
                }
                return segment;
            }
        }

        long byteSize() {
            return byteSize;
        }

        @Override
        public void close() {
            owner.release(this);
        }
    }
}
