/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccSnapshotLease

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.concurrent.atomic.AtomicBoolean;

/** A snapshot sequence held open until it is closed; vacuum keeps what it needs. */
final class MvccSnapshotLease implements AutoCloseable {
    private final MvccRawStoreRuntime runtime;
    private final long leaseId;
    private final int slot;
    private final long sequence;
    private final AtomicBoolean closed = new AtomicBoolean();

    private MvccSnapshotLease(
            MvccRawStoreRuntime runtime,
            long leaseId,
            int slot,
            long sequence) {
        this.runtime = runtime;
        this.leaseId = leaseId;
        this.slot = slot;
        this.sequence = sequence;
    }

    static MvccSnapshotLease locked(
            MvccRawStoreRuntime runtime,
            long leaseId,
            long sequence) {
        return new MvccSnapshotLease(runtime, leaseId, -1, sequence);
    }

    static MvccSnapshotLease slotted(
            MvccRawStoreRuntime runtime,
            int slot,
            long sequence) {
        return new MvccSnapshotLease(runtime, 0L, slot, sequence);
    }

    long sequence() {
        return sequence;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            runtime.closeSnapshotLease(leaseId, slot, sequence);
        }
    }
}
