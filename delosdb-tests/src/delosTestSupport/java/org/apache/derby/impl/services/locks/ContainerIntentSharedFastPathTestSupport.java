/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.locks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.CompatibilitySpace;
import org.apache.derby.iapi.services.locks.LockOwner;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.ContainerLock;

/** Package-private lock-manager proofs for the concurrent CIS fast path. */
public final class ContainerIntentSharedFastPathTestSupport {
    private ContainerIntentSharedFastPathTestSupport() {
    }

    public static void verifyMaterializationContract() throws Exception {
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        CompatibilitySpace readerA = new LockSpace(new TestLockOwner());
        CompatibilitySpace readerB = new LockSpace(new TestLockOwner());
        CompatibilitySpace writer = new LockSpace(new TestLockOwner());
        ContainerKey key = new ContainerKey(0, 42);

        Lock firstReader = requireLock(table.lockObject(
                readerA, key, ContainerLock.CIS, C_LockFactory.NO_WAIT),
                "first CIS reader was not granted");
        Lock secondReader = requireLock(table.lockObject(
                readerB, key, ContainerLock.CIS, C_LockFactory.NO_WAIT),
                "second compatible CIS reader was not granted");

        if (table.lockObject(writer, key, ContainerLock.CX,
                C_LockFactory.NO_WAIT) != null) {
            throw new AssertionError("CX was granted while CIS readers were active");
        }

        // The CX request above freezes/materializes the fast control. Locks
        // returned before that transition must still release correctly through
        // the ordinary LockControl.
        table.unlock(firstReader, 1);
        table.unlock(secondReader, 1);

        Lock exclusive = requireLock(table.lockObject(
                writer, key, ContainerLock.CX, C_LockFactory.NO_WAIT),
                "CX was not granted after both CIS readers released");
        table.unlock(exclusive, 1);

        firstReader = requireLock(table.lockObject(
                readerA, key, ContainerLock.CIS, C_LockFactory.NO_WAIT),
                "CIS was not granted after the normal control became empty");
        Lock intentExclusive = requireLock(table.lockObject(
                readerB, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "CIX was not granted alongside compatible CIS");
        table.unlock(firstReader, 1);
        table.unlock(intentExclusive, 1);
    }


    public static void verifyGroupReferenceCounting() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        CompatibilitySpace space = pool.createCompatibilitySpace(new TestLockOwner());
        Object group = new Object();
        ContainerKey key = new ContainerKey(0, 126);

        if (!pool.lockObject(space, group, key, ContainerLock.CIS, C_LockFactory.NO_WAIT)
                || !pool.lockObject(space, group, key, ContainerLock.CIS, C_LockFactory.NO_WAIT)) {
            throw new AssertionError("repeated CIS acquisition was not granted");
        }
        if (!pool.isLockHeld(space, group, key, ContainerLock.CIS)) {
            throw new AssertionError("CIS group lock was not recorded");
        }
        if (pool.unlock(space, group, key, ContainerLock.CIS) != 1
                || !pool.isLockHeld(space, group, key, ContainerLock.CIS)) {
            throw new AssertionError("first CIS release lost the repeated group hold");
        }
        if (pool.unlock(space, group, key, ContainerLock.CIS) != 1
                || pool.isLockHeld(space, group, key, ContainerLock.CIS)) {
            throw new AssertionError("second CIS release did not clear the group hold");
        }
    }

    public static void verifyConcurrentReaders() throws Exception {
        final int threads = 8;
        final int iterations = 10_000;
        ConcurrentLockSet table = new ConcurrentLockSet(null);
        ContainerKey key = new ContainerKey(0, 84);
        CompatibilitySpace[] spaces = new CompatibilitySpace[threads];
        for (int i = 0; i < threads; i++) {
            spaces[i] = new LockSpace(new TestLockOwner());
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int thread = 0; thread < threads; thread++) {
            final CompatibilitySpace space = spaces[thread];
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        Lock lock = table.lockObject(
                                space, key, ContainerLock.CIS, C_LockFactory.NO_WAIT);
                        if (lock == null) {
                            throw new AssertionError("compatible CIS request blocked");
                        }
                        table.unlock(lock, 1);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "fast-cis-proof-" + thread);
            worker.start();
        }

        start.countDown();
        done.await();
        if (failure.get() != null) {
            throw new AssertionError("concurrent CIS proof failed", failure.get());
        }

        Lock exclusive = requireLock(table.lockObject(
                new LockSpace(new TestLockOwner()), key,
                ContainerLock.CX, C_LockFactory.NO_WAIT),
                "CX was not granted after concurrent CIS readers completed");
        table.unlock(exclusive, 1);
    }

    private static Lock requireLock(Lock lock, String message) {
        if (lock == null) {
            throw new AssertionError(message);
        }
        return lock;
    }

    private static final class TestLockOwner implements LockOwner {
        public boolean noWait() {
            return false;
        }

        public boolean isNestedOwner() {
            return false;
        }

        public boolean nestsUnder(LockOwner other) {
            return false;
        }
    }
}
