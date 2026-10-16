/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Default-off, instance-attributed ownership diagnostics; never a store authority. */
public final class RawStoreOccupancy {
    public static final boolean ENABLED = Boolean.getBoolean(
            "delosdb.diagnostic.rawStoreOccupancy.enabled");
    public static final int SAMPLE_EVERY = ENABLED
            ? Integer.getInteger("delosdb.diagnostic.rawStoreOccupancy.sampleEvery", 32) : 1;
    public static final int OTHER = 0, HEAP = 1, ROOT = 2, BRANCH = 3, LEAF = 4;
    public static final int SELF = 0, CHILD_WAIT = 1, ALLOC_WAIT = 2,
            ALLOC_HOLD = 3, LOG_WAIT = 4, LOG_HOLD = 5,
            SWITCH_BUFFER = 6, DIRTY_FLUSH = 7, SWITCH_FILE = 8;
    private static final long ORIGIN = ENABLED ? System.nanoTime() : 0L;
    private static final String[] ROLES = {"OTHER", "HEAP_INSERT_PAGE",
            "BTREE_ROOT", "BTREE_BRANCH", "BTREE_LEAF"};
    private static final AtomicReferenceArray<Resource> RESOURCES = new AtomicReferenceArray<>(4096);
    private static final LongAdder DROPPED_OWNERSHIPS = new LongAdder();
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    static {
        if (SAMPLE_EVERY < 1 || SAMPLE_EVERY > 1024 || (SAMPLE_EVERY & (SAMPLE_EVERY - 1)) != 0) {
            throw new IllegalArgumentException("RawStore occupancy sampleEvery must be a power of two in 1..1024");
        }
        if (ENABLED && System.getProperty("delosdb.diagnostic.rawStoreOccupancy.report") != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(RawStoreOccupancy::writeReport,
                    "rawstore-occupancy-report"));
        }
    }

    private RawStoreOccupancy() {}

    /** Allocate once per physical resource, only when enabled. */
    public static Resource resource(String identity) {
        identity = identity.replace(',', '_').replace('\n', '_');
        int bucket = identity.hashCode() & (RESOURCES.length() - 1);
        Resource candidate = null;
        while (true) {
            Resource previous = RESOURCES.get(bucket);
            for (Resource current = previous; current != null; current = current.next) {
                if (current.identity.equals(identity)) return current;
            }
            if (candidate == null) candidate = new Resource(identity);
            candidate.next = previous;
            if (RESOURCES.compareAndSet(bucket, previous, candidate)) return candidate;
        }
    }

    public static int requestRole(int role) {
        State state = STATE.get();
        int previous = state.role;
        state.role = role;
        return previous;
    }

    public static boolean requestTarget(boolean target) {
        State state = STATE.get();
        boolean previous = state.target;
        state.target = target;
        return previous;
    }

    public static void classify(Object page, int role) {
        State state = STATE.get();
        for (int i = 0; i < state.pages; i++) {
            if (state.page[i] == page) state.pageRole[i] = role;
        }
    }

    public static void treeHeight(Object page, long height) {
        State state = STATE.get();
        if (!state.sampled) return;
        for (int i = 0; i < state.pages; i++) {
            if (state.page[i] == page) {
                state.resource[i].row(state.pageRole[i]).height.accumulateAndGet(height, Math::max);
                return;
            }
        }
    }

    /** Begin actual latch attempt, before entering the page monitor. */
    public static long pageWait() {
        State state = STATE.get();
        state.beginEpisode();
        state.tick();
        state.childWait++;
        return state.now;
    }

    /** PRELATCH owns the resource, including the cleaner wait. */
    public static void pageAcquired(Object page, Resource resource, long started) {
        State state = STATE.get();
        state.tick();
        state.childWait--;
        state.ensurePages();
        int slot = state.pages++;
        state.page[slot] = page;
        state.resource[slot] = resource;
        state.pageRole[slot] = page instanceof AllocPage ? OTHER : state.role;
        state.pageTarget[slot] = state.target && !(page instanceof AllocPage);
        state.wait[slot] = state.now - started;
        state.pageStarted[slot] = state.now;
        if (state.sampled) java.util.Arrays.fill(state.time[slot], 0L);
    }

    public static void pageCancelled() {
        State state = STATE.get();
        state.tick();
        state.childWait--;
    }

    public static void pageFailed(Resource resource, long started) {
        State state = STATE.get();
        state.tick();
        state.childWait--;
        if (state.sampled) {
            resource.row(state.role).failures.increment();
            resource.row(state.role).failedWait.add(state.now - started);
        }
    }

    public static void pageReleased(Object page) {
        State state = STATE.get();
        state.tick();
        for (int i = 0; i < state.pages; i++) {
            if (state.page[i] == page) {
                if (state.sampled) {
                    Row row = state.resource[i].row(state.pageRole[i]);
                    row.acquires.increment();
                    row.hold.add(state.now - state.pageStarted[i]);
                    row.observe(state.pageStarted[i], state.now);
                    if (state.pageTarget[i]) row.targets.increment();
                    row.wait.add(state.wait[i]);
                    for (int j = 0; j < 9; j++) {
                        if (state.time[i][j] != 0L) row.time[j].add(state.time[i][j]);
                    }
                }
                int last = --state.pages;
                state.page[i] = state.page[last];
                state.resource[i] = state.resource[last];
                state.pageRole[i] = state.pageRole[last];
                state.pageTarget[i] = state.pageTarget[last];
                state.wait[i] = state.wait[last];
                state.pageStarted[i] = state.pageStarted[last];
                long[] spare = state.time[i];
                state.time[i] = state.time[last];
                state.time[last] = spare;
                state.page[last] = null;
                state.resource[last] = null;
                return;
            }
        }
        DROPPED_OWNERSHIPS.increment();
    }

    /** Monitor wait/hold is attributed to the explicit request or held page role. */
    public static long allocWait() {
        State state = STATE.get();
        state.beginEpisode();
        state.tick();
        state.allocWait++;
        return state.now;
    }

    public static long allocAcquired(Resource resource, long started) {
        State state = STATE.get();
        state.tick();
        state.allocWait--;
        state.allocHold++;
        int role = state.context();
        boolean reentrant = false;
        for (int i = 0; i < state.allocDepth; i++) {
            if (state.allocResource[i] == resource) reentrant = true;
        }
        state.ensureAlloc();
        int slot = state.allocDepth++;
        state.allocResource[slot] = resource;
        state.allocRole[slot] = reentrant ? -1 : role;
        if (!reentrant && state.sampled) {
            resource.row(role).acquires.increment();
            resource.row(role).wait.add(state.now - started);
        }
        return state.now;
    }

    public static void allocReleased(Resource resource, long started) {
        State state = STATE.get();
        state.tick();
        int slot = --state.allocDepth;
        int role = state.allocRole[slot];
        if (role >= 0 && state.sampled) {
            resource.row(role).time[SELF].add(state.now - started);
            resource.row(role).hold.add(state.now - started);
            resource.row(role).observe(started, state.now);
        }
        state.allocResource[slot] = null;
        state.allocHold--;
    }

    /** Reentrant FIRST/LAST calls count the outer acquisition exactly once. */
    public static void logWait(Resource resource) {
        State state = STATE.get();
        state.beginEpisode();
        state.tick();
        if (state.logDepth++ == 0) {
            state.log = resource;
            if (state.sampled) java.util.Arrays.fill(state.logTime, 0L);
            state.logHold = 0L;
            state.logRole = state.context();
            state.logStart = state.now;
            state.logWaiting = true;
        }
    }

    public static void logAcquired() {
        State state = STATE.get();
        state.tick();
        if (state.logDepth == 1) {
            if (state.sampled) {
                Row row = state.log.row(state.logRole);
                row.acquires.increment();
                int mask = 0;
                for (int i = 0; i < state.pages; i++) mask |= 1 << state.pageRole[i];
                for (int role = 0; role < ROLES.length; role++) {
                    if ((mask & (1 << role)) != 0) row.calledWith[role].increment();
                }
                row.wait.add(state.now - state.logStart);
                row.observe(state.now, state.now);
            }
            state.logWaiting = false;
            state.logOwned = true;
        }
    }

    public static void logReleased() {
        State state = STATE.get();
        state.tick();
        if (--state.logDepth == 0) {
            if (state.sampled) state.publishLog();
            state.logOwned = false;
            state.logWaiting = false;
            state.log = null;
        }
    }

    /** Object.wait releases all reentrant levels of the log monitor. */
    public static void logSuspend() {
        State state = STATE.get();
        state.tick();
        if (state.logDepth != 0) {
            state.logOwned = false;
            state.logWaiting = true;
            state.logStart = state.now;
        }
    }

    public static void logResume() {
        State state = STATE.get();
        state.tick();
        if (state.logDepth != 0) {
            if (state.sampled) state.log.row(state.logRole).wait.add(state.now - state.logStart);
            state.logOwned = true;
            state.logWaiting = false;
        }
    }

    public static int phase(int phase) {
        State state = STATE.get();
        state.tick();
        int previous = state.phase;
        state.phase = phase;
        return previous;
    }

    /** Quiescent snapshot: completed page holds only; no hot-path text output. */
    public static String[] snapshot() {
        if (!ENABLED) return new String[0];
        List<String> lines = new ArrayList<>();
        lines.add("resource,context,acquires,last_insert_candidate_acquires,failures,wait_ns,failed_wait_ns,hold_ns,self_ns,"
                + "child_wait_ns,alloc_wait_ns,alloc_hold_ns,log_wait_ns,log_hold_ns,"
                + "switch_buffer_ns,dirty_flush_ns,switch_file_ns,called_with_other_page,"
                + "called_with_heap_page,called_with_root,called_with_branch,called_with_leaf,"
                + "first_acquire_ns,last_release_ns,tree_height_max,sample_every");
        for (Resource resource : resources()) {
            for (int role = 0; role < ROLES.length; role++) {
                Row row = resource.rows.get(role);
                if (row != null && row.acquires.sum() + row.failures.sum() != 0) {
                    lines.add(resource.identity + "," + ROLES[role] + "," + row.csv());
                }
            }
        }
        if (DROPPED_OWNERSHIPS.sum() != 0) {
            lines.add("INVALID:dropped_ownerships=" + DROPPED_OWNERSHIPS.sum());
        }
        return lines.toArray(String[]::new);
    }

    /** Caller must quiesce all participating writers before reset. */
    public static void reset() {
        if (!ENABLED) return;
        for (Resource resource : resources()) {
            for (int role = 0; role < 5; role++) {
                Row row = resource.rows.get(role);
                if (row != null) row.reset();
            }
        }
    }

    private static List<Resource> resources() {
        List<Resource> result = new ArrayList<>();
        for (int bucket = 0; bucket < RESOURCES.length(); bucket++) {
            for (Resource resource = RESOURCES.get(bucket); resource != null; resource = resource.next) {
                result.add(resource);
            }
        }
        return result;
    }

    private static void writeReport() {
        Path path = Path.of(System.getProperty("delosdb.diagnostic.rawStoreOccupancy.report"));
        try {
            Files.write(path, List.of(snapshot()), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            System.err.println("Cannot write RawStore occupancy report: " + failure.getMessage());
        }
    }

    public static final class Resource {
        private final String identity;
        private final AtomicReferenceArray<Row> rows = new AtomicReferenceArray<>(5);
        private Resource next;

        private Resource(String identity) {
            this.identity = identity.replace(',', '_').replace('\n', '_');

        }
        private Row row(int role) {
            Row value = rows.get(role);
            if (value == null) {
                Row candidate = new Row();
                if (rows.compareAndSet(role, null, candidate)) return candidate;
                value = rows.get(role);
            }
            return value;
        }

    }

    private static final class Row {
        final AtomicLong first = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong last = new AtomicLong();
        final AtomicLong height = new AtomicLong();
        final LongAdder acquires = new LongAdder();
        final LongAdder targets = new LongAdder();
        final LongAdder failures = new LongAdder();
        final LongAdder wait = new LongAdder();
        final LongAdder hold = new LongAdder();
        final LongAdder failedWait = new LongAdder();
        final LongAdder[] time = new LongAdder[9];
        final LongAdder[] calledWith = new LongAdder[5];

        Row() {
            for (int i = 0; i < time.length; i++) time[i] = new LongAdder();
            for (int i = 0; i < calledWith.length; i++) calledWith[i] = new LongAdder();
        }

        void observe(long begin, long end) {
            if (begin < first.get()) first.accumulateAndGet(begin, Math::min);
            last.accumulateAndGet(end, Math::max);
        }

        String csv() {
            StringBuilder values = new StringBuilder();
            for (LongAdder value : time) {
                long nanos = value.sum();
                values.append(',').append(nanos);
            }
            for (LongAdder value : calledWith) values.append(',').append(value.sum());
            return acquires.sum() + "," + targets.sum() + "," + failures.sum() + "," + wait.sum() + ","
                    + failedWait.sum() + "," + hold.sum() + values + ","
                    + (first.get() == Long.MAX_VALUE ? 0 : first.get()) + "," + last.get() + "," + height.get() + "," + SAMPLE_EVERY;
        }

        void reset() {
            first.set(Long.MAX_VALUE);
            last.set(0L);
            height.set(0L);
            acquires.reset();
            targets.reset();
            failures.reset();
            wait.reset();
            hold.reset();
            failedWait.reset();
            for (LongAdder value : time) value.reset();
            for (LongAdder value : calledWith) value.reset();
        }
    }

    private static final class State {
        int role, pages, childWait, allocWait, allocHold, logDepth, logRole, phase;
        long now, logStart, logHold;
        int random = System.identityHashCode(this) | 1;
        boolean sampled;
        final long[] logTime = new long[9];
        boolean logOwned, logWaiting, target;
        boolean[] pageTarget = new boolean[256];
        Resource log;
        int allocDepth;
        Resource[] allocResource = new Resource[256];
        int[] allocRole = new int[256];
        Object[] page = new Object[256];
        Resource[] resource = new Resource[256];
        int[] pageRole = new int[256];
        long[] wait = new long[256];
        long[] pageStarted = new long[256];
        long[][] time = new long[256][9];

        void ensureAlloc() {
            if (allocDepth < allocResource.length) return;
            allocResource = java.util.Arrays.copyOf(allocResource, allocDepth * 2);
            allocRole = java.util.Arrays.copyOf(allocRole, allocDepth * 2);
        }

        void ensurePages() {
            if (pages < page.length) return;
            int length = page.length * 2;
            page = java.util.Arrays.copyOf(page, length);
            resource = java.util.Arrays.copyOf(resource, length);
            pageRole = java.util.Arrays.copyOf(pageRole, length);
            pageTarget = java.util.Arrays.copyOf(pageTarget, length);
            wait = java.util.Arrays.copyOf(wait, length);
            pageStarted = java.util.Arrays.copyOf(pageStarted, length);
            time = java.util.Arrays.copyOf(time, length);
            for (int i = pages; i < length; i++) time[i] = new long[9];
        }

        int context() {
            return role != OTHER ? role : pages == 0 ? OTHER : pageRole[pages - 1];
        }

        int category() {
            if (logWaiting) return LOG_WAIT;
            if (logOwned) return LOG_HOLD;
            if (allocWait != 0) return ALLOC_WAIT;
            if (allocHold != 0) return ALLOC_HOLD;
            return childWait != 0 ? CHILD_WAIT : SELF;
        }

        boolean idle() {
            return pages == 0 && childWait == 0 && allocWait == 0 && allocDepth == 0 && logDepth == 0;
        }

        void beginEpisode() {
            if (!idle()) return;
            random ^= random << 13;
            random ^= random >>> 17;
            random ^= random << 5;
            sampled = (random & (SAMPLE_EVERY - 1)) == 0;
            if (sampled) now = System.nanoTime() - ORIGIN;
        }

        void publishLog() {
            Row row = log.row(logRole);
            row.hold.add(logHold);
            for (int i = 0; i < logTime.length; i++) {
                if (logTime[i] != 0L) row.time[i].add(logTime[i]);
            }
            row.observe(now, now);
        }

        void tick() {
            if (!sampled || idle()) return;
            long next = System.nanoTime() - ORIGIN;
            long elapsed = next - now;
            int category = category();
            for (int i = 0; i < pages; i++) time[i][category] += elapsed;
            if (logOwned) {
                logTime[phase] += elapsed;
                logHold += elapsed;
            }
            now = next;
        }

    }
}
