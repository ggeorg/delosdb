/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Temporary exact-path counters for transaction-status commit qualification.
 *
 * <p>The diagnostic begins only after the durable transaction-status row has
 * been staged successfully. Therefore {@code maxConcurrent} greater than one
 * proves that more than one status-backed transaction passed status staging
 * and remained in the RawStore commit pipeline concurrently. Counters are
 * disabled unless {@code delosdb.diagnostic.mvccTransactionStatusCommit} is
 * true.</p>
 */
final class MvccTransactionStatusCommitDiagnostics {
    static final int ENTERED = 0;
    static final int COMPLETED = 1;
    static final int MAX_CONCURRENT = 2;
    static final int ACTIVE = 3;
    static final int WIDTH = 4;

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.mvccTransactionStatusCommit");
    private static final AtomicLong ENTERED_COUNT = new AtomicLong();
    private static final AtomicLong COMPLETED_COUNT = new AtomicLong();
    private static final AtomicInteger ACTIVE_COUNT = new AtomicInteger();
    private static final AtomicInteger MAX_CONCURRENT_COUNT = new AtomicInteger();

    private MvccTransactionStatusCommitDiagnostics() {
    }

    static boolean enter() {
        if (!ENABLED) {
            return false;
        }
        ENTERED_COUNT.incrementAndGet();
        int active = ACTIVE_COUNT.incrementAndGet();
        MAX_CONCURRENT_COUNT.accumulateAndGet(active, Math::max);
        return true;
    }

    static void exit() {
        if (!ENABLED) {
            return;
        }
        COMPLETED_COUNT.incrementAndGet();
        int active = ACTIVE_COUNT.decrementAndGet();
        if (active < 0) {
            ACTIVE_COUNT.incrementAndGet();
            throw new IllegalStateException(
                    "RawStore MVCC transaction-status commit diagnostic underflow");
        }
    }

    static boolean enabledForTesting() {
        return ENABLED;
    }

    static void resetForTesting() {
        if (!ENABLED) {
            return;
        }
        int active = ACTIVE_COUNT.get();
        if (active != 0) {
            throw new IllegalStateException(
                    "Cannot reset transaction-status commit diagnostics with active commits: "
                            + active);
        }
        ENTERED_COUNT.set(0L);
        COMPLETED_COUNT.set(0L);
        MAX_CONCURRENT_COUNT.set(0);
    }

    static long[] snapshotForTesting() {
        return new long[] {
                ENTERED_COUNT.get(),
                COMPLETED_COUNT.get(),
                MAX_CONCURRENT_COUNT.get(),
                ACTIVE_COUNT.get()
        };
    }
}
