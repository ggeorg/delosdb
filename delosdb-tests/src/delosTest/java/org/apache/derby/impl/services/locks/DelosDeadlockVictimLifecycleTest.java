/*

   Derby - Class org.apache.derby.impl.services.locks.DelosDeadlockVictimLifecycleTest

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
package org.apache.derby.impl.services.locks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import junit.framework.TestCase;
import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.CompatibilitySpace;
import org.apache.derby.iapi.services.locks.Latch;
import org.apache.derby.iapi.services.locks.Lockable;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Deadlock-victim wake-up correctness while the wait graph is changing. */
public final class DelosDeadlockVictimLifecycleTest extends TestCase {
    private static final Object EXCLUSIVE = new Object();

    public void testVictimWakeupSurvivesLaterGrantSignal() throws Exception {
        assertVictimWinsWakeupRace(false);
    }

    public void testVictimWakeupOverridesQueuedGrantSignal() throws Exception {
        assertVictimWinsWakeupRace(true);
    }

    private void assertVictimWinsWakeupRace(boolean grantFirst) throws Exception {
        TestPool pool = new TestPool();
        LockTable table = pool.lockTable;
        CompatibilitySpace survivor = pool.createCompatibilitySpace(null);
        CompatibilitySpace victim = pool.createCompatibilitySpace(null);
        Lockable first = new ExclusiveLockable();
        Lockable second = new ExclusiveLockable();

        Lock survivorFirst = table.lockObject(
                survivor, first, EXCLUSIVE, C_LockFactory.WAIT_FOREVER);
        Lock victimFirst = table.lockObject(
                victim, second, EXCLUSIVE, C_LockFactory.WAIT_FOREVER);

        AtomicReference<Lock> survivorSecond = new AtomicReference<>();
        AtomicReference<Throwable> survivorFailure = new AtomicReference<>();
        Thread survivorThread = lockAsync(
                table, survivor, second, survivorSecond, survivorFailure, "delos-deadlock-survivor");
        ActiveLock survivorWait = awaitWaiter(table, second, survivor);

        AtomicReference<Lock> victimSecond = new AtomicReference<>();
        AtomicReference<Throwable> victimFailure = new AtomicReference<>();
        Thread victimThread = lockAsync(
                table, victim, first, victimSecond, victimFailure, "delos-deadlock-victim");
        ActiveLock victimWait = awaitWaiter(table, first, victim);

        // Force the portable form of the MariaDB race. The victim already owns
        // one lock, is waiting for the next lock in the cycle, and receives
        // both a grant-style wake-up and a deadlock-victim wake-up before it
        // can resume. Deadlock selection must dominate either signal order.
        synchronized (victimWait) {
            if (grantFirst) {
                victimWait.wakeUp(Constants.WAITING_LOCK_GRANT);
                victimWait.wakeUp(Constants.WAITING_LOCK_DEADLOCK);
            } else {
                victimWait.wakeUp(Constants.WAITING_LOCK_DEADLOCK);
                victimWait.wakeUp(Constants.WAITING_LOCK_GRANT);
            }
        }

        victimThread.join(5000L);
        assertFalse("deadlock victim remained blocked", victimThread.isAlive());
        assertNull("deadlock victim must not acquire the cancelled later lock", victimSecond.get());
        assertEquals(0, victimWait.getCount());
        assertDeadlock(victimFailure.get());

        // A deadlock victim at the lock-manager level still owns its earlier
        // grant until transaction rollback releases it. Releasing that grant
        // must let the surviving waiter acquire a real lock, not a phantom one.
        table.unlock(victimFirst, 1);
        survivorThread.join(5000L);
        assertFalse("surviving waiter did not resume", survivorThread.isAlive());
        assertNull(survivorFailure.get());
        assertNotNull(survivorSecond.get());
        assertEquals(1, survivorSecond.get().getCount());
        assertSame(survivorWait, survivorSecond.get());

        table.unlock(survivorSecond.get(), 1);
        table.unlock(survivorFirst, 1);
        assertFalse("lock table retained a waiter after deadlock cleanup", table.anyoneBlocked());
    }

    private static Thread lockAsync(
            LockTable table,
            CompatibilitySpace space,
            Lockable lockable,
            AtomicReference<Lock> result,
            AtomicReference<Throwable> failure,
            String name) {
        Thread thread = new Thread(() -> {
            try {
                result.set(table.lockObject(space, lockable, EXCLUSIVE, C_LockFactory.WAIT_FOREVER));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static ActiveLock awaitWaiter(
            LockTable table, Lockable lockable, CompatibilitySpace space) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            Control control = (Control) table.shallowClone().get(lockable);
            List<?> waiting = control == null ? null : control.getWaiting();
            if (waiting != null) {
                for (Object candidate : waiting) {
                    ActiveLock lock = (ActiveLock) candidate;
                    if (lock.getCompatabilitySpace() == space) {
                        return lock;
                    }
                }
            }
            Thread.sleep(5L);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for lock request");
        return null;
    }

    private static void assertDeadlock(Throwable failure) {
        assertTrue("expected StandardException, got " + failure,
                failure instanceof StandardException);
        assertEquals(SQLState.DEADLOCK, ((StandardException) failure).getSQLState());
    }

    private static final class TestPool extends AbstractPool {
        @Override
        protected LockTable createLockTable() {
            return new ConcurrentLockSet(this);
        }
    }

    private static final class ExclusiveLockable implements Lockable {
        @Override
        public void lockEvent(Latch lockInfo) {
        }

        @Override
        public boolean requestCompatible(Object requestedQualifier, Object grantedQualifier) {
            return false;
        }

        @Override
        public boolean lockerAlwaysCompatible() {
            return false;
        }

        @Override
        public void unlockEvent(Latch lockInfo) {
        }

        @Override
        public boolean lockAttributes(int flag, Map<String, Object> attributes) {
            return false;
        }
    }
}
