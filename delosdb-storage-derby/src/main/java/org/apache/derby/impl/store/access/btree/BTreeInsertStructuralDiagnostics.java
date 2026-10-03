/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.access.btree;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Temporary counters for the F08 B-tree insert structural-work proof.
 *
 * <p>The counters are disabled unless
 * {@code delosdb.diagnostic.btreeInsertStructural} is true. Enabled threads
 * write only to thread-local arrays; reset advances a generation and snapshot
 * aggregates after the measured interval has stopped. This avoids introducing
 * another shared write authority into the path being measured.</p>
 */
final class BTreeInsertStructuralDiagnostics {
    static final int SPLIT_PASS_CALLS = 0;
    static final int ACTUAL_SPLIT_PASSES = 1;
    static final int RECLAIM_ONLY_PASSES = 2;
    static final int LEAF_ALREADY_HAS_SPACE = 3;
    static final int LEAF_PAGE_SPLITS = 4;
    static final int LEAF_ROOT_GROWS = 5;
    static final int BRANCH_PAGE_SPLITS = 6;
    static final int BRANCH_ROOT_GROWS = 7;
    static final int SPLIT_RESTARTS = 8;
    static final int SPLIT_PASS_NANOS = 9;
    static final int MAX_SPLIT_PASS_NANOS = 10;
    static final int INTERNAL_XACT_ACQUIRE_NANOS = 11;
    static final int SPLIT_OPEN_INIT_NANOS = 12;
    static final int ROOT_ACQUIRE_NANOS = 13;
    static final int ROOT_SPLIT_FOR_NANOS = 14;
    static final int FINAL_COMMIT_NANOS = 15;
    static final int FINAL_DESTROY_NANOS = 16;
    static final int WIDTH = 17;

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.btreeInsertStructural");
    private static final AtomicInteger GENERATION = new AtomicInteger(1);
    private static final ConcurrentLinkedQueue<State> STATES =
            new ConcurrentLinkedQueue<State>();
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(() -> {
        State state = new State();
        STATES.add(state);
        return state;
    });

    private BTreeInsertStructuralDiagnostics() {
    }

    static void increment(int counter) {
        if (!ENABLED) {
            return;
        }
        currentState().values[counter]++;
    }

    static long startTimer() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    static void addElapsed(int counter, long startedNanos) {
        if (!ENABLED || startedNanos == 0L) {
            return;
        }
        currentState().values[counter] += System.nanoTime() - startedNanos;
    }

    static void finishSplitPass(long startedNanos) {
        if (!ENABLED || startedNanos == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - startedNanos;
        State state = currentState();
        state.values[SPLIT_PASS_NANOS] += elapsed;
        if (elapsed > state.values[MAX_SPLIT_PASS_NANOS]) {
            state.values[MAX_SPLIT_PASS_NANOS] = elapsed;
        }
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
            for (int i = 0; i < WIDTH; i++) {
                if (i == MAX_SPLIT_PASS_NANOS) {
                    totals[i] = Math.max(totals[i], state.values[i]);
                } else {
                    totals[i] += state.values[i];
                }
            }
        }
        return totals;
    }

    private static State currentState() {
        State state = LOCAL.get();
        int generation = GENERATION.get();
        if (state.generation != generation) {
            Arrays.fill(state.values, 0L);
            state.generation = generation;
        }
        return state;
    }

    private static final class State {
        private int generation;
        private final long[] values = new long[WIDTH];
    }
}
