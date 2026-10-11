/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.locks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.CompatibilitySpace;
import org.apache.derby.iapi.services.locks.LockOwner;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.ContainerLock;

/**
 * Package-private lock-manager proofs for granting an already held CIX
 * container lock from the transaction's own lock space.
 */
public final class HeldIntentRegrantTestSupport {
    private HeldIntentRegrantTestSupport() {
    }

    /** Held CIX locks in this JVM granted without the lock table. */
    public static long regrants() {
        return AbstractPool.heldIntentRegrantsForTesting();
    }

    /** Regrants count like lock-table grants: N requests need N unlocks. */
    public static void verifyReferenceCounting() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        CompatibilitySpace owner = pool.createCompatibilitySpace(new TestLockOwner());
        CompatibilitySpace other = pool.createCompatibilitySpace(new TestLockOwner());
        Object group = new Object();
        ContainerKey key = new ContainerKey(0, 301);

        long before = regrants();
        for (int i = 0; i < 3; i++) {
            require(pool.lockObject(owner, group, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                    "CIX request " + i + " was not granted");
        }
        check(regrants() - before == 2, "repeated CIX requests must be regranted locally");
        check(pool.isLockHeld(owner, group, key, ContainerLock.CIX), "CIX not recorded in group");

        for (int i = 0; i < 2; i++) {
            check(pool.unlock(owner, group, key, ContainerLock.CIX) == 1, "unlock " + i + " failed");
            check(!pool.lockObject(other, new Object(), key, ContainerLock.CX, C_LockFactory.NO_WAIT),
                    "CX granted after " + (i + 1) + " of 3 unlocks");
        }
        check(pool.unlock(owner, group, key, ContainerLock.CIX) == 1, "last unlock failed");
        check(!pool.isLockHeld(owner, group, key, ContainerLock.CIX), "CIX still recorded");
        Object otherGroup = new Object();
        require(pool.lockObject(other, otherGroup, key, ContainerLock.CX, C_LockFactory.NO_WAIT),
                "CX not granted after all CIX references were released");
        pool.unlockGroup(other, otherGroup);
    }

    /** A group unlock releases regranted references in one step. */
    public static void verifyGroupUnlockAndTransfer() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        CompatibilitySpace owner = pool.createCompatibilitySpace(new TestLockOwner());
        CompatibilitySpace other = pool.createCompatibilitySpace(new TestLockOwner());
        Object statement = new Object();
        Object transaction = new Object();
        ContainerKey key = new ContainerKey(0, 302);

        require(pool.lockObject(owner, statement, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "statement CIX not granted");
        // A different group does not hold the lock yet: it must not be regranted.
        long before = regrants();
        require(pool.lockObject(owner, transaction, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "transaction CIX not granted");
        check(regrants() == before, "a lock held in another group must not be regranted");
        require(pool.lockObject(owner, statement, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "statement CIX regrant failed");
        check(regrants() == before + 1, "held statement CIX was not regranted");

        pool.transfer(owner, statement, transaction);
        require(pool.lockObject(owner, transaction, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "transferred CIX regrant failed");
        check(regrants() == before + 2, "merged CIX was not regranted");

        pool.unlockGroup(owner, transaction);
        check(!pool.isLockHeld(owner, transaction, key, ContainerLock.CIX), "CIX still held");
        Object otherGroup = new Object();
        require(pool.lockObject(other, otherGroup, key, ContainerLock.CX, C_LockFactory.NO_WAIT),
                "CX not granted after the group unlock");
        pool.unlockGroup(other, otherGroup);
    }

    /**
     * With an incompatible request queued, a holder's repeated CIX request is
     * still granted (as by the lock table), and the waiter gets its lock as
     * soon as the holder's group is released.
     */
    public static void verifyRegrantWithQueuedWaiter() throws Exception {
        ConcurrentPool pool = new ConcurrentPool();
        CompatibilitySpace owner = pool.createCompatibilitySpace(new TestLockOwner());
        CompatibilitySpace waiter = pool.createCompatibilitySpace(new TestLockOwner());
        Object group = new Object();
        Object waiterGroup = new Object();
        ContainerKey key = new ContainerKey(0, 303);

        require(pool.lockObject(owner, group, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "first CIX not granted");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                started.countDown();
                if (!pool.lockObject(waiter, waiterGroup, key, ContainerLock.CX, 30_000)) {
                    throw new AssertionError("queued CX was not granted");
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "held-intent-regrant-waiter");
        thread.start();
        started.await();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!pool.anyoneBlocked()) {
            check(System.nanoTime() < deadline, "CX request never queued");
            Thread.sleep(1);
        }

        long before = regrants();
        require(pool.lockObject(owner, group, key, ContainerLock.CIX, C_LockFactory.NO_WAIT),
                "held CIX must be granted while a CX request waits");
        check(regrants() == before + 1, "held CIX was not regranted with a waiter queued");

        pool.unlockGroup(owner, group);
        thread.join(30_000);
        check(!thread.isAlive(), "CX waiter was not granted after the group unlock");
        if (failure.get() != null) {
            throw new AssertionError("CX waiter failed", failure.get());
        }
        check(pool.isLockHeld(waiter, waiterGroup, key, ContainerLock.CX), "CX not held by waiter");
        pool.unlockGroup(waiter, waiterGroup);
    }

    private static void require(boolean granted, String message) {
        if (!granted) {
            throw new AssertionError(message);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
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
