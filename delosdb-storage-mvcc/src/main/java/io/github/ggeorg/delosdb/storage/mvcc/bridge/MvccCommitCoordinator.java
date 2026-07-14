/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccCommitCoordinator

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Per-table boundary between prepared commits and ordered durable publication. */
final class MvccCommitCoordinator<T, R> {
    static final int DEFAULT_CAPACITY = 64;
    static final int DEFAULT_MAX_GROUP_SIZE = 16;
    static final long DEFAULT_MAX_GROUP_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);

    enum Mode {
        DIRECT("direct"),
        QUEUED("queued"),
        GROUP("group");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @FunctionalInterface
    interface GroupProcessor<T, R> {
        List<Outcome<R>> process(List<T> items);
    }

    record Outcome<R>(R value, Throwable failure) {
        static <R> Outcome<R> success(R value) {
            return new Outcome<>(value, null);
        }

        static <R> Outcome<R> failure(Throwable failure) {
            return new Outcome<>(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean succeeded() {
            return failure == null;
        }
    }

    record Submission<R>(
            R value,
            Throwable failure,
            Mode mode,
            int enrollmentDepth,
            long waitNanos,
            long groupId,
            int groupSize,
            boolean leader,
            long groupWaitNanos) {
        Submission {
            mode = Objects.requireNonNull(mode, "mode");
            if (enrollmentDepth <= 0) {
                throw new IllegalArgumentException("enrollmentDepth must be positive");
            }
            if (waitNanos < 0L || groupWaitNanos < 0L) {
                throw new IllegalArgumentException("wait times must not be negative");
            }
            if (groupId <= 0L || groupSize <= 0) {
                throw new IllegalArgumentException("group identity and size must be positive");
            }
        }

        boolean succeeded() {
            return failure == null;
        }
    }

    private final Mode mode;
    private final int maxGroupSize;
    private final long maxGroupDelayNanos;
    private final ReentrantLock directLock = new ReentrantLock(true);
    private final Semaphore capacity;
    private final ReentrantLock queueLock = new ReentrantLock(true);
    private final Condition queueChanged = queueLock.newCondition();
    private final ArrayDeque<Request<T, R>> queue = new ArrayDeque<>();
    private final AtomicLong nextGroupId = new AtomicLong(1L);
    private boolean groupLeaderActive;
    private int inFlightCount;

    MvccCommitCoordinator(Mode mode) {
        this(mode, DEFAULT_CAPACITY, DEFAULT_MAX_GROUP_SIZE, DEFAULT_MAX_GROUP_DELAY_NANOS);
    }

    MvccCommitCoordinator(Mode mode, int capacity) {
        this(mode, capacity, DEFAULT_MAX_GROUP_SIZE, DEFAULT_MAX_GROUP_DELAY_NANOS);
    }

    MvccCommitCoordinator(Mode mode, int capacity, int maxGroupSize, long maxGroupDelayNanos) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (maxGroupSize <= 0 || maxGroupSize > capacity) {
            throw new IllegalArgumentException("maxGroupSize must be in [1, capacity]");
        }
        if (maxGroupDelayNanos < 0L) {
            throw new IllegalArgumentException("maxGroupDelayNanos must not be negative");
        }
        this.maxGroupSize = mode == Mode.GROUP ? maxGroupSize : 1;
        this.maxGroupDelayNanos = mode == Mode.GROUP ? maxGroupDelayNanos : 0L;
        capacity = Math.max(capacity, this.maxGroupSize);
        this.capacity = new Semaphore(capacity, true);
    }

    Submission<R> submit(T item, boolean measureWait, GroupProcessor<T, R> processor) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(processor, "processor");
        if (mode == Mode.DIRECT) {
            return submitDirect(item, measureWait, processor);
        }

        long offeredAt = System.nanoTime();
        capacity.acquireUninterruptibly();
        Request<T, R> request = new Request<>(item, offeredAt);
        try {
            boolean leader = false;
            queueLock.lock();
            try {
                queue.addLast(request);
                request.enrollmentDepth = queue.size() + inFlightCount;
                queueChanged.signalAll();
                while (!request.completed) {
                    if (!groupLeaderActive && queue.peekFirst() == request) {
                        groupLeaderActive = true;
                        request.leader = true;
                        leader = true;
                        break;
                    }
                    queueChanged.awaitUninterruptibly();
                }
            } finally {
                queueLock.unlock();
            }

            if (leader) {
                executeGroup(processor);
            }

            queueLock.lock();
            try {
                while (!request.completed) {
                    queueChanged.awaitUninterruptibly();
                }
                return new Submission<>(
                        request.outcome.value(),
                        request.outcome.failure(),
                        mode,
                        request.enrollmentDepth,
                        measureWait ? request.executionStartedAt - offeredAt : 0L,
                        request.groupId,
                        request.groupSize,
                        request.leader,
                        request.groupWaitNanos);
            } finally {
                queueLock.unlock();
            }
        } finally {
            capacity.release();
        }
    }

    private Submission<R> submitDirect(T item, boolean measureWait, GroupProcessor<T, R> processor) {
        long started = measureWait ? System.nanoTime() : 0L;
        directLock.lock();
        try {
            long executionStarted = System.nanoTime();
            long groupId = nextGroupId.getAndIncrement();
            Outcome<R> outcome;
            try {
                List<Outcome<R>> outcomes = processor.process(List.of(item));
                if (outcomes.size() != 1) {
                    throw new IllegalStateException("direct commit processor returned " + outcomes.size()
                            + " outcomes for one request");
                }
                outcome = Objects.requireNonNull(outcomes.get(0), "processor outcome");
            } catch (Throwable failure) {
                outcome = Outcome.failure(failure);
            }
            return new Submission<>(
                    outcome.value(),
                    outcome.failure(),
                    mode,
                    1,
                    measureWait ? executionStarted - started : 0L,
                    groupId,
                    1,
                    true,
                    0L);
        } finally {
            directLock.unlock();
        }
    }

    private void executeGroup(GroupProcessor<T, R> processor) {
        List<Request<T, R>> group;
        long groupId;
        long executionStartedAt;
        long groupWaitNanos;
        queueLock.lock();
        try {
            Request<T, R> first = queue.peekFirst();
            if (first == null || !first.leader) {
                throw new IllegalStateException("MVCC group leader does not own the FIFO head");
            }
            long deadline = first.offeredAt + maxGroupDelayNanos;
            while (queue.size() < maxGroupSize) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    queueChanged.awaitNanos(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            int groupCount = Math.min(maxGroupSize, queue.size());
            group = new ArrayList<>(groupCount);
            for (int index = 0; index < groupCount; index++) {
                Request<T, R> request = queue.removeFirst();
                group.add(request);
            }
            inFlightCount += group.size();
            groupId = nextGroupId.getAndIncrement();
            executionStartedAt = System.nanoTime();
            groupWaitNanos = Math.max(0L, executionStartedAt - first.offeredAt);
            for (Request<T, R> request : group) {
                request.executionStartedAt = executionStartedAt;
                request.groupId = groupId;
                request.groupSize = group.size();
                request.groupWaitNanos = groupWaitNanos;
            }
        } finally {
            queueLock.unlock();
        }

        List<Outcome<R>> outcomes;
        try {
            outcomes = processor.process(group.stream().map(request -> request.item).toList());
            if (outcomes.size() != group.size()) {
                throw new IllegalStateException("group commit processor returned " + outcomes.size()
                        + " outcomes for " + group.size() + " requests");
            }
            outcomes = List.copyOf(outcomes);
        } catch (Throwable failure) {
            outcomes = new ArrayList<>(group.size());
            for (int index = 0; index < group.size(); index++) {
                outcomes.add(Outcome.failure(failure));
            }
        }

        queueLock.lock();
        try {
            for (int index = 0; index < group.size(); index++) {
                Request<T, R> request = group.get(index);
                request.outcome = Objects.requireNonNull(outcomes.get(index), "processor outcome");
                request.completed = true;
            }
            inFlightCount -= group.size();
            groupLeaderActive = false;
            queueChanged.signalAll();
        } finally {
            queueLock.unlock();
        }
    }

    Mode mode() {
        return mode;
    }

    int currentEnrollmentCountForTesting() {
        if (mode == Mode.DIRECT) {
            return directLock.isLocked() ? 1 : 0;
        }
        queueLock.lock();
        try {
            return queue.size() + inFlightCount;
        } finally {
            queueLock.unlock();
        }
    }

    private static final class Request<T, R> {
        private final T item;
        private final long offeredAt;
        private int enrollmentDepth;
        private long executionStartedAt;
        private long groupId;
        private int groupSize;
        private long groupWaitNanos;
        private boolean leader;
        private boolean completed;
        private Outcome<R> outcome;

        private Request(T item, long offeredAt) {
            this.item = item;
            this.offeredAt = offeredAt;
        }
    }
}
