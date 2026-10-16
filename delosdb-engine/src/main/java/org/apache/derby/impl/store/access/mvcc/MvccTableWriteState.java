/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccTableWriteState

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writer activity on one table. A snapshot read of a secondary index
 * checks each entry against the base row unless no transaction has
 * written the table since the snapshot: then an entry is visible exactly
 * when it is not marked deleted. Purging deleted index entries requires
 * that no other transaction writes the table. Transactions register
 * before they open the table or its indexes for update, and end after
 * their commit is published or their rollback is complete.
 */
final class MvccTableWriteState {
    /** Epoch value meaning the table changed after the snapshot. */
    static final long CHANGED = -1L;

    private final AtomicInteger activeWriters = new AtomicInteger();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong lastCommitSequence = new AtomicLong();

    MvccTableWriteState() {
    }

    void beginWrite() {
        activeWriters.incrementAndGet();
        epoch.incrementAndGet();
    }

    /** Records a writer's commit sequence before its commit is published. */
    void recordCommit(long commitSequence) {
        lastCommitSequence.accumulateAndGet(commitSequence, Math::max);
    }

    void endWrite() {
        epoch.incrementAndGet();
        activeWriters.decrementAndGet();
    }

    /**
     * The current epoch if no transaction is writing the table and every
     * committed write is visible at {@code snapshotSequence}, else
     * {@link #CHANGED}.
     */
    long unchangedEpoch(long snapshotSequence) {
        return quietEpoch(0, snapshotSequence);
    }

    /**
     * The current epoch if {@code ownWriters} transactions, the caller's
     * own, are all that write the table and no write committed after
     * {@code committedThrough}, else {@link #CHANGED}.
     */
    long quietEpoch(int ownWriters, long committedThrough) {
        long before = epoch.get();
        if (activeWriters.get() != ownWriters
                || lastCommitSequence.get() > committedThrough
                || epoch.get() != before) {
            return CHANGED;
        }
        return before;
    }

    /** Whether no writer has begun or ended since {@code unchangedEpoch}. */
    boolean unchangedSince(long unchangedEpoch) {
        return unchangedEpoch != CHANGED && epoch.get() == unchangedEpoch;
    }
}
