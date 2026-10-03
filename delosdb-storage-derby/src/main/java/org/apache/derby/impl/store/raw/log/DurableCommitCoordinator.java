/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.derby.impl.store.raw.log;

import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.derby.iapi.util.InterruptStatus;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Groups transaction-end durability demands without owning WAL or I/O.
 *
 * One caller flushes a detached cohort while new demands accumulate. On
 * completion, leadership passes to a waiting caller; a caller never remains
 * responsible for draining unrelated work after its own demand is satisfied.
 * There is no worker thread, batching timer, or cached durability authority.
 * Only the exclusive frontier returned by RawStore can acknowledge a record.
 */
final class DurableCommitCoordinator {
    @FunctionalInterface
    interface Flusher {
        /** Flush through a record start; return the first unflushed byte. */
        long flushThrough(long recordInstant) throws StandardException;
    }

    record Snapshot(long requests, long cohorts, long acknowledgedRequests,
                    int maximumCohort, int pendingRequests, boolean failed) { }

    private static final class Request {
        final long instant;
        final Condition changed;
        boolean leader;
        boolean complete;
        StandardException failure;

        Request(long instant, Condition changed) {
            this.instant = instant;
            this.changed = changed;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Request> pending = new ArrayDeque<>();
    private final Flusher flusher;
    private boolean leaderActive;
    private volatile Thread flushingThread;
    private StandardException terminalFailure;
    private long requests;
    private long cohorts;
    private long acknowledgedRequests;
    private int maximumCohort;

    DurableCommitCoordinator(Flusher flusher) {
        this.flusher = java.util.Objects.requireNonNull(flusher, "flusher");
    }

    boolean isFlushingOnCurrentThread() {
        return flushingThread == Thread.currentThread();
    }

    void awaitDurable(long recordInstant) throws StandardException {
        if (isFlushingOnCurrentThread()) {
            throw new IllegalStateException("A flush leader cannot wait on its own cohort");
        }
        if (recordInstant <= LogCounter.INVALID_LOG_INSTANT) {
            throw new IllegalArgumentException("A durability demand requires a valid log instant");
        }
        boolean interrupted = false;
        Request request;
        lock.lock();
        try {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
            request = new Request(recordInstant, lock.newCondition());
            pending.addLast(request);
            requests++;
            if (!leaderActive) {
                leaderActive = true;
                request.leader = true;
            }
            while (!request.leader && !request.complete) {
                try {
                    request.changed.await();
                } catch (InterruptedException observed) {
                    // A recorded transaction decision cannot be acknowledged
                    // merely because its waiting thread was interrupted.
                    interrupted = true;
                }
            }
            if (request.failure != null) {
                throw request.failure;
            }
        } finally {
            lock.unlock();
            if (interrupted) {
                InterruptStatus.setInterrupted();
            }
        }
        if (request.leader) {
            flushCohort();
        }
    }

    private ArrayDeque<Request> takeCohort() {
        lock.lock();
        try {
            ArrayDeque<Request> cohort = new ArrayDeque<>(pending);
            pending.clear();
            cohorts++;
            return cohort;
        } finally {
            lock.unlock();
        }
    }

    private void flushCohort() throws StandardException {
        ArrayDeque<Request> cohort = null;
        long firstUnflushed = LogCounter.INVALID_LOG_INSTANT;
        StandardException failure = null;
        boolean succeeded = false;
        try {
            cohort = takeCohort();
            long target = LogCounter.INVALID_LOG_INSTANT;
            for (Request request : cohort) {
                target = Math.max(target, request.instant);
            }
            // Never call RawStore while holding the admission lock.
            flushingThread = Thread.currentThread();
            firstUnflushed = flusher.flushThrough(target);
            if (firstUnflushed <= target) {
                throw StandardException.newException(SQLState.LOG_STORE_CORRUPT);
            }
            succeeded = true;
        } catch (StandardException flushFailure) {
            failure = flushFailure;
            throw flushFailure;
        } finally {
            // Also unblock followers on an unchecked failure. The original
            // unchecked failure propagates unchanged to the leader; followers
            // receive a terminal failure, never a success or an automatic retry.
            flushingThread = null;
            finishCohort(cohort, firstUnflushed, succeeded, failure);
        }
    }

    private void finishCohort(ArrayDeque<Request> cohort, long firstUnflushed,
                              boolean succeeded, StandardException failure) {
        lock.lock();
        try {
            if (!succeeded) {
                terminalFailure = failure != null ? failure
                        : StandardException.newException(SQLState.LOG_STORE_CORRUPT);
                if (cohort != null) {
                    finishRequests(cohort, terminalFailure);
                }
                finishRequests(pending, terminalFailure);
                pending.clear();
                leaderActive = false;
                return;
            }
            int acknowledged = cohort.size();
            finishRequests(cohort, null);
            // Requests can arrive out of log order. A late request is covered
            // only when its record START is strictly before RawStore's frontier.
            var remaining = pending.iterator();
            while (remaining.hasNext()) {
                Request request = remaining.next();
                if (request.instant < firstUnflushed) {
                    finishRequest(request, null);
                    remaining.remove();
                    acknowledged++;
                }
            }
            acknowledgedRequests += acknowledged;
            maximumCohort = Math.max(maximumCohort, acknowledged);
            leaderActive = !pending.isEmpty();
            if (leaderActive) {
                Request next = pending.getFirst();
                next.leader = true;
                next.changed.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    private void finishRequests(Iterable<Request> requests, StandardException failure) {
        for (Request request : requests) {
            finishRequest(request, failure);
        }
    }

    private void finishRequest(Request request, StandardException failure) {
        request.failure = failure;
        request.complete = true;
        request.changed.signal();
    }

    Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(requests, cohorts, acknowledgedRequests,
                    maximumCohort, pending.size(), terminalFailure != null);
        } finally {
            lock.unlock();
        }
    }
}
