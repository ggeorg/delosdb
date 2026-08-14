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
 * Temporary exact-path counters for the HOT primary-key read proof.
 *
 * <p>The counters are disabled unless {@code delosdb.diagnostic.btreePointReadPath}
 * is true. Enabled threads write only to thread-local arrays; reset advances a
 * generation and snapshot aggregates after the measured interval has stopped.
 * This keeps the proof from adding a shared atomic write to every read.</p>
 */
final class BTreePointReadDiagnostics {
    static final int FETCH_ROWS_CALLS = 0;
    static final int SCAN_INIT_SINGLE_ROW_CALLS = 1;
    static final int REJECT_FOR_UPDATE = 2;
    static final int REJECT_HELD = 3;
    static final int REJECT_QUALIFIER = 4;
    static final int REJECT_MISSING_START = 5;
    static final int REJECT_MISSING_STOP = 6;
    static final int REJECT_START_OPERATOR = 7;
    static final int REJECT_STOP_OPERATOR = 8;
    static final int REJECT_NON_UNIQUE = 9;
    static final int REJECT_START_KEY_LENGTH = 10;
    static final int REJECT_STOP_KEY_LENGTH = 11;
    static final int REJECT_UNEQUAL_BOUNDS = 12;
    static final int ELIGIBLE_EXACT_POINT_SHAPES = 13;
    static final int ROOT_SNAPSHOT_ATTEMPTS = 14;
    static final int ROOT_SNAPSHOT_HITS = 15;
    static final int ROOT_SNAPSHOT_FALLBACKS = 16;
    static final int ROOT_SNAPSHOT_HEIGHT_TWO_HITS = 17;
    static final int ROOT_SNAPSHOT_OTHER_HEIGHT_HITS = 18;
    static final int EXACT_START_MATCHES = 19;
    static final int PREVIOUS_KEY_LOCK_SKIPPED = 20;
    static final int PREVIOUS_KEY_LOCK_REQUESTED = 21;
    static final int INDEX_LEAF_ROW_FETCHES = 22;
    static final int SNAPSHOT_POINT_ATTEMPTS = 23;
    static final int SNAPSHOT_POINT_HITS = 24;
    static final int SNAPSHOT_POINT_SNAPSHOT_MISSES = 25;
    static final int SNAPSHOT_POINT_LOCK_FALLBACKS = 26;
    static final int SNAPSHOT_POINT_REVALIDATION_FALLBACKS = 27;
    static final int SNAPSHOT_POINT_HELD_EXHAUSTIONS = 28;
    static final int LEAF_SNAPSHOT_OBSERVATIONS = 29;
    static final int LEAF_SNAPSHOT_INVALIDATIONS = 30;
    static final int WIDTH = 31;

    private static final boolean ENABLED =
            Boolean.getBoolean("delosdb.diagnostic.btreePointReadPath");
    private static final AtomicInteger GENERATION = new AtomicInteger(1);
    private static final ConcurrentLinkedQueue<State> STATES =
            new ConcurrentLinkedQueue<State>();
    private static final ThreadLocal<State> LOCAL = ThreadLocal.withInitial(() -> {
        State state = new State();
        STATES.add(state);
        return state;
    });

    private BTreePointReadDiagnostics() {
    }

    static boolean enabled() {
        return ENABLED;
    }

    static void increment(int counter) {
        if (!ENABLED) {
            return;
        }
        State state = currentState();
        state.values[counter]++;
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
                totals[i] += state.values[i];
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
