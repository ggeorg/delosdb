/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temporary exact-path counters for the MVCC snapshot-lease removability proof.
 *
 * <p>The counters are disabled unless {@code delosdb.diagnostic.mvccSnapshotLease}
 * is true. Enabled threads update thread-local arrays only; reset advances a
 * generation and snapshot aggregates after the measured interval has stopped.
 * This avoids adding a shared atomic write to every snapshot open/close.</p>
 */
final class MvccSnapshotLeaseDiagnostics {
    static final int LOCKED_CURRENT_OPENS = 0;
    static final int LOCKED_RETAINED_OPENS = 1;
    static final int LOCKED_CLOSES = 2;
    static final int SLOTTED_CURRENT_OPENS = 3;
    static final int SLOTTED_RETAINED_OPENS = 4;
    static final int SLOTTED_CLOSES = 5;
    static final int CURRENT_SLOT_CLAIM_FAILURES = 6;
    static final int RETAINED_SLOT_CLAIM_FAILURES = 7;
    static final int WIDTH = 8;

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.mvccSnapshotLease");
    private static final AtomicInteger GENERATION = new AtomicInteger(1);
    private static final ConcurrentLinkedQueue<State> STATES =
            new ConcurrentLinkedQueue<State>();
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(() -> {
        State state = new State();
        STATES.add(state);
        return state;
    });

    private MvccSnapshotLeaseDiagnostics() {
    }

    static void lockedCurrentOpen() {
        increment(LOCKED_CURRENT_OPENS);
    }

    static void lockedRetainedOpen() {
        increment(LOCKED_RETAINED_OPENS);
    }

    static void lockedClose() {
        increment(LOCKED_CLOSES);
    }

    static void slottedCurrentOpen() {
        increment(SLOTTED_CURRENT_OPENS);
    }

    static void slottedRetainedOpen() {
        increment(SLOTTED_RETAINED_OPENS);
    }

    static void slottedClose() {
        increment(SLOTTED_CLOSES);
    }

    static void currentSlotClaimFailure() {
        increment(CURRENT_SLOT_CLAIM_FAILURES);
    }

    static void retainedSlotClaimFailure() {
        increment(RETAINED_SLOT_CLAIM_FAILURES);
    }

    static void resetForTesting() {
        if (ENABLED) {
            GENERATION.incrementAndGet();
        }
    }

    static long[] snapshotForTesting() {
        long[] totals = new long[WIDTH];
        if (!ENABLED) {
            return totals;
        }
        int generation = GENERATION.get();
        for (State state : STATES) {
            if (state.generation != generation) {
                continue;
            }
            for (int index = 0; index < WIDTH; index++) {
                totals[index] += state.values[index];
            }
        }
        return totals;
    }

    private static void increment(int counter) {
        if (!ENABLED) {
            return;
        }
        State state = LOCAL.get();
        int generation = GENERATION.get();
        if (state.generation != generation) {
            Arrays.fill(state.values, 0L);
            state.generation = generation;
        }
        state.values[counter]++;
    }

    private static final class State {
        private int generation;
        private final long[] values = new long[WIDTH];
    }
}
