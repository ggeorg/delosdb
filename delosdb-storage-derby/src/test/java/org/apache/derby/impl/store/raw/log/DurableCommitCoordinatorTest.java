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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.apache.derby.iapi.util.InterruptStatus;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Deterministic barriers test real caller blocking, coverage, and handoff. */
public final class DurableCommitCoordinatorTest {
    private DurableCommitCoordinatorTest() { }

    public static void main(String[] args) throws Exception {
        singleCallerFlushesWithoutWaitingForPeers();
        pendingCallersShareTheNextFlush();
        lateCoveredRequestNeedsNoAdditionalFlush();
        exclusiveFrontierDoesNotAcknowledgeTheNextRecord();
        fileRolloverOrdersRequestsByLogInstant();
        checkedFailureReleasesEveryCallerAndPoisonsAdmission();
        uncheckedFailureDoesNotStrandFollowers();
        shortFlushCannotAcknowledgeACommit();
        interruptionCannotAcknowledgeAnUnflushedRecord();
        recursiveAdmissionFailsRatherThanDeadlocking();
        concurrentStressPreservesCoverage();
        System.out.println("DURABLE_COMMIT_COORDINATOR_OK tests=11");
    }

    private static void singleCallerFlushesWithoutWaitingForPeers() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            check(target == 20, "single caller target");
            calls.incrementAndGet();
            return 21;
        });
        coordinator.awaitDurable(20);
        check(calls.get() == 1, "single caller must perform exactly one flush");
        check(coordinator.snapshot().acknowledgedRequests() == 1, "single acknowledgement");
    }

    private static void pendingCallersShareTheNextFlush() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> firstLeader = new AtomicReference<>();
        AtomicReference<Thread> nextLeader = new AtomicReference<>();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstLeader.set(Thread.currentThread());
                firstEntered.countDown();
                await(releaseFirst);
            } else {
                check(call == 2 && target == 90, "cohort must select its maximum target");
                nextLeader.set(Thread.currentThread());
                secondEntered.countDown();
                await(releaseSecond);
            }
            return target + 1;
        });
        FutureTask<Void> first = submit(coordinator, 10);
        List<FutureTask<Void>> followers = new ArrayList<>();
        try {
            await(firstEntered);
            for (long target : new long[] {90, 20, 90, 40, 30, 60}) {
                followers.add(submit(coordinator, target));
            }
            pending(coordinator, 6);
            check(!first.isDone(), "leader cannot return before its flush completes");
            releaseFirst.countDown();
            await(secondEntered);
            get(first);
            check(followers.stream().noneMatch(FutureTask::isDone), "followers acknowledged too early");
            check(firstLeader.get() != nextLeader.get(), "leadership must pass to a waiting caller");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
        }
        for (FutureTask<Void> task : followers) {
            get(task);
        }
        check(calls.get() == 2, "six pending callers must share one flush");
        check(coordinator.snapshot().maximumCohort() == 6, "cohort size");
        check(coordinator.snapshot().acknowledgedRequests() == 7, "all requests acknowledged once");
    }

    private static void lateCoveredRequestNeedsNoAdditionalFlush() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            return 101;
        });
        FutureTask<Void> first = submit(coordinator, 10);
        FutureTask<Void> late;
        try {
            await(entered);
            late = submit(coordinator, 100);
            pending(coordinator, 1);
            check(!late.isDone(), "covered request cannot return before the I/O succeeds");
        } finally {
            release.countDown();
        }
        get(first);
        get(late);
        check(calls.get() == 1, "RawStore's returned frontier should cover the late request");
        check(coordinator.snapshot().maximumCohort() == 2, "late request counted in its durable cohort");
    }

    private static void exclusiveFrontierDoesNotAcknowledgeTheNextRecord() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                return 100;
            }
            check(target == 100, "record at the exclusive frontier needs its own flush");
            return 101;
        });
        FutureTask<Void> first = submit(coordinator, 10);
        FutureTask<Void> boundary;
        try {
            await(entered);
            boundary = submit(coordinator, 100);
            pending(coordinator, 1);
        } finally {
            release.countDown();
        }
        get(first);
        get(boundary);
        check(calls.get() == 2, "exclusive frontier is not a durable record start");
    }

    private static void fileRolloverOrdersRequestsByLogInstant() throws Exception {
        long earlier = LogCounter.makeLogInstantAsLong(1, 4000);
        long later = LogCounter.makeLogInstantAsLong(2, 64);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong requested = new AtomicLong();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            requested.set(target);
            entered.countDown();
            await(release);
            return target + 1;
        });
        FutureTask<Void> first = submit(coordinator, earlier);
        FutureTask<Void> next;
        try {
            await(entered);
            next = submit(coordinator, later);
            pending(coordinator, 1);
        } finally {
            release.countDown();
        }
        get(first);
        get(next);
        check(requested.get() == later, "file number must participate in durability ordering");
    }

    private static void checkedFailureReleasesEveryCallerAndPoisonsAdmission() throws Exception {
        StandardException original = StandardException.newException(SQLState.LOG_STORE_CORRUPT);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            calls.incrementAndGet();
            entered.countDown();
            await(release);
            throw original;
        });
        FutureTask<Void> leader = submit(coordinator, 10);
        FutureTask<Void> follower;
        try {
            await(entered);
            follower = submit(coordinator, 20);
            pending(coordinator, 1);
        } finally {
            release.countDown();
        }
        check(failure(leader) == original, "leader retains the original flush failure");
        check(failure(follower) == original, "follower retains the original flush failure");
        check(failure(submit(coordinator, 30)) == original, "failed coordinator cannot retry automatically");
        check(calls.get() == 1 && coordinator.snapshot().acknowledgedRequests() == 0,
                "failed sync must acknowledge nothing");
    }

    private static void uncheckedFailureDoesNotStrandFollowers() throws Exception {
        AssertionError original = new AssertionError("injected flush failure");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            entered.countDown();
            await(release);
            throw original;
        });
        FutureTask<Void> leader = submit(coordinator, 10);
        FutureTask<Void> follower;
        try {
            await(entered);
            follower = submit(coordinator, 20);
            pending(coordinator, 1);
        } finally {
            release.countDown();
        }
        check(failure(leader) == original, "unchecked failure must escape unchanged");
        check(failure(follower) instanceof StandardException, "follower must fail, not hang");
        check(coordinator.snapshot().failed() && coordinator.snapshot().pendingRequests() == 0,
                "abnormal completion poisons admission and empties pending requests");
    }

    private static void shortFlushCannotAcknowledgeACommit() throws Exception {
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> target);
        check(failure(submit(coordinator, 40)) instanceof StandardException, "short frontier rejected");
        check(coordinator.snapshot().acknowledgedRequests() == 0, "no success after a short frontier");
    }

    private static void interruptionCannotAcknowledgeAnUnflushedRecord() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            entered.countDown();
            await(release);
            return 100;
        });
        FutureTask<Void> leader = submit(coordinator, 10);
        FutureTask<Boolean> follower = new FutureTask<>(() -> {
            followerThread.set(Thread.currentThread());
            coordinator.awaitDurable(20);
            InterruptStatus.restoreIntrFlagIfSeen();
            return Thread.interrupted();
        });
        try {
            await(entered);
            start(follower);
            pending(coordinator, 1);
            followerThread.get().interrupt();
            until(() -> !followerThread.get().isInterrupted(), "interrupt not consumed by waiting caller");
            check(!follower.isDone(), "interrupt must not release a non-durable request");
        } finally {
            release.countDown();
        }
        get(leader);
        check(get(follower), "Derby interrupt status must survive the durability wait");
    }

    private static void recursiveAdmissionFailsRatherThanDeadlocking() throws Exception {
        AtomicReference<DurableCommitCoordinator> ref = new AtomicReference<>();
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            check(ref.get().isFlushingOnCurrentThread(), "leader must be recognizable for RawStore reentry");
            ref.get().awaitDurable(target);
            return target + 1;
        });
        ref.set(coordinator);
        check(failure(submit(coordinator, 10)) instanceof IllegalStateException, "recursive wait rejected");
        check(!coordinator.isFlushingOnCurrentThread(), "leader ownership must clear on failure");
    }

    private static void concurrentStressPreservesCoverage() throws Exception {
        AtomicLong allocated = new AtomicLong();
        AtomicLong durable = new AtomicLong();
        AtomicInteger active = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        DurableCommitCoordinator coordinator = new DurableCommitCoordinator(target -> {
            check(active.incrementAndGet() == 1, "two concurrent cohort flushers");
            try {
                Thread.yield();
                return durable.accumulateAndGet(target + 1, Math::max);
            } finally {
                active.decrementAndGet();
            }
        });
        List<FutureTask<Void>> tasks = new ArrayList<>();
        for (int worker = 0; worker < 8; worker++) {
            FutureTask<Void> task = new FutureTask<>(() -> {
                await(start);
                for (int i = 0; i < 1000; i++) {
                    long target = allocated.incrementAndGet();
                    coordinator.awaitDurable(target);
                    check(durable.get() > target, "acknowledged record lies beyond durable frontier");
                }
                return null;
            });
            tasks.add(task);
            start(task);
        }
        start.countDown();
        for (FutureTask<Void> task : tasks) {
            get(task);
        }
        check(coordinator.snapshot().acknowledgedRequests() == 8000, "stress acknowledgement count");
        check(coordinator.snapshot().pendingRequests() == 0, "stress left requests behind");
    }

    private static FutureTask<Void> submit(DurableCommitCoordinator coordinator, long target) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            coordinator.awaitDurable(target);
            return null;
        });
        start(task);
        return task;
    }

    private static void start(FutureTask<?> task) {
        Thread thread = new Thread(task, "durable-commit-contract");
        thread.setDaemon(true);
        thread.start();
    }

    private static <T> T get(FutureTask<T> task) throws Exception {
        return task.get(15, TimeUnit.SECONDS);
    }

    private static Throwable failure(FutureTask<?> task) throws Exception {
        try {
            get(task);
            throw new AssertionError("Expected failure");
        } catch (java.util.concurrent.ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static void pending(DurableCommitCoordinator coordinator, int count) {
        until(() -> coordinator.snapshot().pendingRequests() == count, "pending request count " + count);
    }

    private static void until(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            check(System.nanoTime() < deadline, message);
            Thread.yield();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            check(latch.await(10, TimeUnit.SECONDS), "test barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test leader unexpectedly interrupted", failure);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
