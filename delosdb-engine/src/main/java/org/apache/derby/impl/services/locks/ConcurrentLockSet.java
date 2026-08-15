/*

   Derby - Class org.apache.derby.impl.services.locks.ConcurrentLockSet

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

import org.apache.derby.iapi.services.locks.CompatibilitySpace;
import org.apache.derby.iapi.services.locks.Latch;
import org.apache.derby.iapi.services.locks.Lockable;
import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.ContainerLock;
import org.apache.derby.iapi.store.raw.RowLock;

import org.apache.derby.shared.common.error.StandardException;

import org.apache.derby.shared.common.sanity.SanityManager;
import org.apache.derby.iapi.services.diag.DiagnosticUtil;

import org.apache.derby.shared.common.reference.Property;
import org.apache.derby.shared.common.reference.SQLState;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.Map;


/**
    A ConcurrentLockSet is a complete lock table which maps
    <code>Lockable</code>s to <code>LockControl</code> objects.

	<P>
	A LockControl contains information about the locks held on a Lockable.

	<BR>
    MT - Mutable : All public methods of this class, except addWaiters, are
    thread safe. addWaiters can only be called from the thread which performs
    deadlock detection. Only one thread can perform deadlock detection at a
    time.

	<BR>
	The class creates ActiveLock and LockControl objects.
	
	Ordinary LockControl objects are never passed out of this class and are
	accessed while holding the per-Lockable ReentrantLock. A ContainerKey that
	is held only with intent-shared (CIS) locks may instead use a concurrent
	CIS-only control. The first request for any other container lock mode freezes
	that control and materializes all holders into an ordinary LockControl before
	compatibility, waiter ordering, timeout, or deadlock logic runs.
	@see LockControl
*/

final class ConcurrentLockSet implements LockTable {
	/*
	** Fields
	*/
	private final AbstractPool factory;

    /** Hash table which maps <code>Lockable</code> objects to
     * <code>Lock</code>s. */
    private final ConcurrentHashMap<Lockable, Entry> locks;

    /**
     * List containing all entries seen by the last call to
     * <code>addWaiters()</code>. Makes it possible for the deadlock detection
     * thread to lock all the entries it has visited until it has
     * finished. This prevents false deadlocks from being reported (because all
     * observed waiters must still be waiting when the deadlock detection has
     * completed).
     */
    private ArrayList<Entry> seenByDeadlockDetection;

	/**
		Timeout for deadlocks, in ms.
		<BR>
		MT - immutable
	*/
	private int deadlockTimeout = Property.DEADLOCK_TIMEOUT_DEFAULT * 1000;
	private int waitTimeout = Property.WAIT_TIMEOUT_DEFAULT * 1000;

//EXCLUDE-START-lockdiag- 

	// this varible is set and get without synchronization.  
	// Only one thread should be setting it at one time.
	private boolean deadlockTrace;

//EXCLUDE-END-lockdiag- 

	// The number of waiters for locks
	private final AtomicInteger blockCount;

    private static final boolean LOCK_ENTRY_DIAGNOSTICS =
            Boolean.getBoolean("delosdb.diagnostic.lockEntry");
    private static final boolean LOCK_WAIT_DIAGNOSTICS =
            Boolean.getBoolean("delosdb.diagnostic.lockWait");
    private static final boolean HOT_STATE_DIAGNOSTICS =
            Boolean.getBoolean("delosdb.diagnostic.hotState");
    private static final boolean FAST_RECORD_READ_ENABLED =
            Boolean.getBoolean("delosdb.experimental.fastRecordReadLock");
    private static final LongAdder FAST_CIS_ACQUIRE_ATTEMPTS = new LongAdder();
    private static final LongAdder FAST_CIS_RELEASE_ATTEMPTS = new LongAdder();
    private static final LongAdder FAST_CIS_HOLDER_CREATES = new LongAdder();
    private static final LongAdder FAST_CIS_HOLDER_REMOVES = new LongAdder();
    private static final LongAdder FAST_CIS_STRIPE_OVERLAP = new LongAdder();
    private static final LongAdder FAST_RECORD_READ_PROMOTIONS = new LongAdder();
    private static final LongAdder FAST_RECORD_READ_ACQUIRE_HITS = new LongAdder();
    private static final LongAdder FAST_RECORD_READ_RELEASE_HITS = new LongAdder();
    private static final LongAdder FAST_RECORD_READ_FREEZES = new LongAdder();
    private static final LongAdder FAST_RECORD_READ_RETIREMENTS = new LongAdder();
    private static final LockEntryStats CONTAINER_KEY_ENTRY_STATS = new LockEntryStats();
    private static final LockEntryStats RECORD_ID_ENTRY_STATS = new LockEntryStats();
    private static final LockEntryStats OTHER_ENTRY_STATS = new LockEntryStats();
    private static final LogicalWaitStats CONTAINER_KEY_WAIT_STATS = new LogicalWaitStats();
    private static final LogicalWaitStats RECORD_ID_WAIT_STATS = new LogicalWaitStats();
    private static final LogicalWaitStats OTHER_WAIT_STATS = new LogicalWaitStats();

    private static final class LockEntryStats {
        private final LongAdder acquisitions = new LongAdder();
        private final LongAdder contendedAcquisitions = new LongAdder();
        private final LongAdder waitNanos = new LongAdder();

        private void reset() {
            acquisitions.reset();
            contendedAcquisitions.reset();
            waitNanos.reset();
        }
    }

    private static final class LogicalWaitStats {
        private final LongAdder waits = new LongAdder();
        private final LongAdder waitNanos = new LongAdder();
        private final LongAccumulator maxWaitNanos = new LongAccumulator(Long::max, 0L);

        private void reset() {
            waits.reset();
            waitNanos.reset();
            maxWaitNanos.reset();
        }
    }

	/*
	** Constructor
	*/

	ConcurrentLockSet(AbstractPool factory) {
		this.factory = factory;
        blockCount = new AtomicInteger();
		locks = new ConcurrentHashMap<Lockable, Entry>();
	}

    /**
     * Class representing an entry in the lock table.
     */
    private static final class Entry {
        private final Lockable ref;

        Entry(Lockable ref) {
            this.ref = ref;
        }

        /** The lock control. Volatile for lock-free compatible-reader fast paths. */
        volatile Control control;
        /**
         * Mutex used to ensure single-threaded access to the LockControls. To
         * avoid Java deadlocks, no thread should ever hold the mutex of more
         * than one entry. Excepted from this requirement is a thread which
         * performs deadlock detection. During deadlock detection, a thread
         * might hold several mutexes, but it is not allowed to hold any mutex
         * when entering the deadlock detection. Only one thread is allowed to
         * perform deadlock detection at a time.
         */
        private final ReentrantLock mutex = new ReentrantLock();
        /**
         * Condition variable which prevents calls to <code>lock()</code> from
         * locking the entry. If it is not <code>null</code>, only the thread
         * performing deadlock detection may lock the entry (by calling
         * <code>lockForDeadlockDetection()</code>).
         */
        private Condition deadlockDetection;

        /**
         * Lock the entry, ensuring exclusive access to the contained
         * <code>Control</code> object. The call will block until the entry can
         * be locked. If the entry is unlocked and
         * <code>deadlockDetection</code> is not <code>null</code>, the entry
         * belongs to a thread which waits for deadlock detection to be
         * initiated, and the call will block until that thread has finished
         * its deadlock detection.
         */
        void lock() {
            if (SanityManager.DEBUG) {
                SanityManager.ASSERT(!mutex.isHeldByCurrentThread());
            }
            if (!LOCK_ENTRY_DIAGNOSTICS) {
                mutex.lock();
            } else {
                LockEntryStats stats = lockEntryStats(ref);
                stats.acquisitions.increment();
                if (!mutex.tryLock()) {
                    stats.contendedAcquisitions.increment();
                    long start = System.nanoTime();
                    mutex.lock();
                    stats.waitNanos.add(System.nanoTime() - start);
                }
            }
            while (deadlockDetection != null) {
                deadlockDetection.awaitUninterruptibly();
            }
        }

        /**
         * Unlock the entry, allowing other threads to lock and access the
         * contained <code>Control</code> object.
         */
        void unlock() {
            mutex.unlock();
        }

        /**
         * Lock the entry while performing deadlock detection. This method will
         * lock the entry even when <code>deadlockDetection</code> is not
         * <code>null</code>. If <code>deadlockDetection</code> is not
         * <code>null</code>, we know the entry and its <code>Control</code>
         * will not be accessed by others until we have finished the deadlock
         * detection, so it's OK for us to access it.
         *
         */
        void lockForDeadlockDetection() {
            if (SanityManager.DEBUG) {
                SanityManager.ASSERT(!mutex.isHeldByCurrentThread());
            }
            mutex.lock();
        }

        /**
         * Notify that the lock request that is currently accessing the entry
         * will be entering deadlock detection. Unlock the entry to allow the
         * current thread or other threads to lock the entry for deadlock
         * detection, but set the condition variable to prevent regular locking
         * of the entry.
         */
        void enterDeadlockDetection() {
            deadlockDetection = mutex.newCondition();
            mutex.unlock();
        }

        /**
         * Notify that the deadlock detection triggered by the current thread
         * has finished. Re-lock the entry and notify any waiters that the
         * deadlock detection has completed.
         */
        void exitDeadlockDetection() {
            if (SanityManager.DEBUG) {
                SanityManager.ASSERT(!mutex.isHeldByCurrentThread());
            }
            mutex.lock();
            deadlockDetection.signalAll();
            deadlockDetection = null;
        }
    }

    /**
     * Concurrent control used only while a ContainerKey is held exclusively
     * with intent-shared (CIS) locks. CIS holders are mutually compatible, so
     * acquisition and release update per-compatibility-space counters without
     * serializing on {@link Entry#mutex} or a shared reader gate.
     *
     * <p>The first request for any other qualifier clears {@link #fast}, waits
     * only for already-entered fast operations to leave, then materializes all
     * holders into the ordinary {@link LockControl}. From that point on,
     * Derby's existing compatibility, waiter ordering, timeout and deadlock
     * machinery is authoritative.</p>
     */
    private final class FastCisControl implements Control {
        private static final int FAST_OPERATION_STRIPES = 16;

        private final Lockable ref;
        private final ConcurrentHashMap<CompatibilitySpace, AtomicInteger> holders =
                new ConcurrentHashMap<CompatibilitySpace, AtomicInteger>();
        private final AtomicIntegerArray inFlight =
                new AtomicIntegerArray(FAST_OPERATION_STRIPES);
        private volatile boolean fast = true;

        FastCisControl(Lockable ref) {
            this.ref = ref;
        }

        private FastCisControl(Lockable ref, boolean fast) {
            this.ref = ref;
            this.fast = fast;
        }

        Lock tryAcquire(CompatibilitySpace space) {
            if (HOT_STATE_DIAGNOSTICS) {
                FAST_CIS_ACQUIRE_ATTEMPTS.increment();
            }
            int stripe = enterFast(space);
            if (stripe < 0) {
                return null;
            }
            try {
                int count = holders.computeIfAbsent(space, ignored -> {
                    if (HOT_STATE_DIAGNOSTICS) {
                        FAST_CIS_HOLDER_CREATES.increment();
                    }
                    return new AtomicInteger();
                }).incrementAndGet();
                Lock lock = new Lock(space, ref, ContainerLock.CIS);
                lock.count = count;
                return lock;
            } finally {
                leaveFast(stripe);
            }
        }

        boolean tryRelease(Latch item, int unlockCount) {
            if (HOT_STATE_DIAGNOSTICS) {
                FAST_CIS_RELEASE_ATTEMPTS.increment();
            }
            CompatibilitySpace space = item.getCompatabilitySpace();
            int stripe = enterFast(space);
            if (stripe < 0) {
                return false;
            }
            try {
                int count = unlockCount == 0 ? item.getCount() : unlockCount;
                AtomicInteger held = holders.get(space);
                if (held == null) {
                    return false;
                }
                int remaining = held.addAndGet(-count);
                if (SanityManager.DEBUG) {
                    SanityManager.ASSERT(remaining >= 0,
                            "negative fast CIS hold count: " + remaining);
                }
                if (remaining == 0 && holders.remove(space, held)
                        && HOT_STATE_DIAGNOSTICS) {
                    FAST_CIS_HOLDER_REMOVES.increment();
                }
                return true;
            } finally {
                leaveFast(stripe);
            }
        }

        LockControl freeze() {
            fast = false;
            awaitFastOperations(inFlight);
            return materializeCisHolders(ref, holders);
        }

        private int enterFast(CompatibilitySpace space) {
            if (!fast) {
                return -1;
            }
            int stripe = System.identityHashCode(space) & (FAST_OPERATION_STRIPES - 1);
            int active = inFlight.incrementAndGet(stripe);
            if (HOT_STATE_DIAGNOSTICS && active > 1) {
                FAST_CIS_STRIPE_OVERLAP.increment();
            }
            if (fast) {
                return stripe;
            }
            inFlight.decrementAndGet(stripe);
            return -1;
        }

        private void leaveFast(int stripe) {
            inFlight.decrementAndGet(stripe);
        }


        private LockControl snapshot() {
            return materializeCisHolders(ref, holders);
        }

        public Lockable getLockable() {
            return ref;
        }

        public LockControl getLockControl() {
            return snapshot();
        }

        public Lock getLock(CompatibilitySpace compatibilitySpace,
                            Object qualifier) {
            if (qualifier != ContainerLock.CIS) {
                return null;
            }
            AtomicInteger held = holders.get(compatibilitySpace);
            int count = held == null ? 0 : held.get();
            if (count == 0) {
                return null;
            }
            Lock lock = new Lock(compatibilitySpace, ref, qualifier);
            lock.count = count;
            return lock;
        }

        public Control shallowClone() {
            LockControl snapshot = snapshot();
            return snapshot == null ? new FastCisControl(ref, false) : snapshot;
        }

        public ActiveLock firstWaiter() {
            return null;
        }

        public boolean isEmpty() {
            return holders.isEmpty();
        }

        public boolean unlock(Latch lockInGroup, int unlockCount) {
            tryRelease(lockInGroup, unlockCount);
            return false;
        }

        public void addWaiters(Map<Object,Object> waiters) {
        }

        public Lock getFirstGrant() {
            java.util.List<Lock> grants = getGranted();
            return grants == null || grants.isEmpty() ? null : grants.get(0);
        }

        public java.util.List<Lock> getGranted() {
            java.util.ArrayList<Lock> grants = new java.util.ArrayList<Lock>();
            for (Map.Entry<CompatibilitySpace, AtomicInteger> holder : holders.entrySet()) {
                int count = holder.getValue().get();
                if (count > 0) {
                    Lock lock = new Lock(holder.getKey(), ref, ContainerLock.CIS);
                    lock.count = count;
                    grants.add(lock);
                }
            }
            return grants.isEmpty() ? null : grants;
        }

        public java.util.List<Lock> getWaiting() {
            return null;
        }

        public boolean isGrantable(boolean noWaitersBeforeMe,
                                   CompatibilitySpace compatibilitySpace,
                                   Object qualifier) {
            for (Map.Entry<CompatibilitySpace, AtomicInteger> holder : holders.entrySet()) {
                if (holder.getValue().get() == 0 || holder.getKey() == compatibilitySpace) {
                    continue;
                }
                if (!ref.requestCompatible(qualifier, ContainerLock.CIS)) {
                    return false;
                }
            }
            return true;
        }
    }


    /**
     * Concurrent control for a RecordId held only with compatible RS2 locks.
     * It is installed adaptively only when a second compatibility space
     * overlaps the first reader. Once promoted, the empty control remains
     * dormant and reusable across short idle gaps so a genuinely hot record
     * does not repeatedly fall back through Entry.mutex promotion.
     *
     * <p>The first request for any other qualifier freezes this control, waits
     * for already-entered fast operations to leave, and materializes all RS2
     * holders into the ordinary LockControl. From that point onward Derby's
     * waiter ordering, timeout, conversion, and deadlock machinery is
     * authoritative.</p>
     */
    private final class FastRecordReadControl implements Control {
        private static final int FAST_OPERATION_STRIPES = 16;

        private final Lockable ref;
        private final ConcurrentHashMap<CompatibilitySpace, AtomicInteger> holders =
                new ConcurrentHashMap<CompatibilitySpace, AtomicInteger>();
        private final AtomicIntegerArray inFlight =
                new AtomicIntegerArray(FAST_OPERATION_STRIPES);
        private final AtomicInteger totalHolds = new AtomicInteger();
        private volatile boolean fast = true;

        FastRecordReadControl(Lock firstGrant) {
            ref = firstGrant.getLockable();
            holders.put(firstGrant.getCompatabilitySpace(), new AtomicInteger(1));
            totalHolds.set(1);
        }

        private FastRecordReadControl(Lockable ref, boolean fast) {
            this.ref = ref;
            this.fast = fast;
        }

        Lock tryAcquire(CompatibilitySpace space) {
            int stripe = enterFast(space);
            if (stripe < 0) {
                return null;
            }
            try {
                AtomicInteger held;
                for (;;) {
                    held = holders.get(space);
                    if (held == null) {
                        AtomicInteger candidate = new AtomicInteger(1);
                        AtomicInteger existing = holders.putIfAbsent(space, candidate);
                        if (existing == null) {
                            break;
                        }
                        held = existing;
                    }
                    int state = held.get();
                    if (state == 0) {
                        if (held.compareAndSet(0, 1)) {
                            break;
                        }
                        continue;
                    }
                    if (state < 0) {
                        holders.remove(space, held);
                        continue;
                    }
                    return null;
                }
                totalHolds.incrementAndGet();
                if (HOT_STATE_DIAGNOSTICS) {
                    FAST_RECORD_READ_ACQUIRE_HITS.increment();
                }
                Lock lock = new Lock(space, ref, RowLock.RS2);
                lock.count = 1;
                return lock;
            } finally {
                leaveFast(stripe);
            }
        }

        int tryRelease(Latch item, int unlockCount) {
            CompatibilitySpace space = item.getCompatabilitySpace();
            int stripe = enterFast(space);
            if (stripe < 0) {
                return -1;
            }
            try {
                int count = unlockCount == 0 ? item.getCount() : unlockCount;
                AtomicInteger held = holders.get(space);
                if (count != 1 || held == null || !held.compareAndSet(1, 0)) {
                    return -1;
                }
                int remainingTotal = totalHolds.decrementAndGet();
                if (SanityManager.DEBUG) {
                    SanityManager.ASSERT(remainingTotal >= 0,
                            "negative fast RecordId RS2 total: " + remainingTotal);
                }
                if (held.compareAndSet(0, -1)) {
                    holders.remove(space, held);
                }
                if (HOT_STATE_DIAGNOSTICS) {
                    FAST_RECORD_READ_RELEASE_HITS.increment();
                }
                return remainingTotal;
            } finally {
                leaveFast(stripe);
            }
        }

        LockControl freeze() {
            fast = false;
            awaitFastOperations(inFlight);
            if (HOT_STATE_DIAGNOSTICS) {
                FAST_RECORD_READ_FREEZES.increment();
            }
            return materializeRecordReadHolders(ref, holders);
        }

        private int enterFast(CompatibilitySpace space) {
            if (!fast) {
                return -1;
            }
            int stripe = System.identityHashCode(space) & (FAST_OPERATION_STRIPES - 1);
            inFlight.incrementAndGet(stripe);
            if (fast) {
                return stripe;
            }
            inFlight.decrementAndGet(stripe);
            return -1;
        }

        private void leaveFast(int stripe) {
            inFlight.decrementAndGet(stripe);
        }


        private LockControl snapshot() {
            return materializeRecordReadHolders(ref, holders);
        }

        public Lockable getLockable() {
            return ref;
        }

        public LockControl getLockControl() {
            return snapshot();
        }

        public Lock getLock(CompatibilitySpace compatibilitySpace,
                            Object qualifier) {
            if (qualifier != RowLock.RS2) {
                return null;
            }
            AtomicInteger held = holders.get(compatibilitySpace);
            int count = held == null ? 0 : held.get();
            if (count == 0) {
                return null;
            }
            Lock lock = new Lock(compatibilitySpace, ref, qualifier);
            lock.count = count;
            return lock;
        }

        public Control shallowClone() {
            LockControl snapshot = snapshot();
            return snapshot == null ? new FastRecordReadControl(ref, false) : snapshot;
        }

        public ActiveLock firstWaiter() {
            return null;
        }

        public boolean isEmpty() {
            return totalHolds.get() == 0;
        }

        public boolean unlock(Latch lockInGroup, int unlockCount) {
            return false;
        }

        public void addWaiters(Map<Object,Object> waiters) {
        }

        public Lock getFirstGrant() {
            java.util.List<Lock> grants = getGranted();
            return grants == null || grants.isEmpty() ? null : grants.get(0);
        }

        public java.util.List<Lock> getGranted() {
            java.util.ArrayList<Lock> grants = new java.util.ArrayList<Lock>();
            for (Map.Entry<CompatibilitySpace, AtomicInteger> holder : holders.entrySet()) {
                int count = holder.getValue().get();
                if (count > 0) {
                    Lock lock = new Lock(holder.getKey(), ref, RowLock.RS2);
                    lock.count = count;
                    grants.add(lock);
                }
            }
            return grants.isEmpty() ? null : grants;
        }

        public java.util.List<Lock> getWaiting() {
            return null;
        }

        public boolean isGrantable(boolean noWaitersBeforeMe,
                                   CompatibilitySpace compatibilitySpace,
                                   Object qualifier) {
            AtomicInteger own = holders.get(compatibilitySpace);
            int ownCount = own == null ? 0 : own.get();
            if (totalHolds.get() <= ownCount) {
                return true;
            }
            return ref.requestCompatible(qualifier, RowLock.RS2);
        }
    }

    private static void awaitFastOperations(AtomicIntegerArray inFlight) {
        for (;;) {
            boolean active = false;
            for (int stripe = 0; stripe < inFlight.length(); stripe++) {
                if (inFlight.get(stripe) != 0) {
                    active = true;
                    break;
                }
            }
            if (!active) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    private LockControl materializeCisHolders(
            Lockable ref,
            ConcurrentHashMap<CompatibilitySpace, AtomicInteger> holders) {
        LockControl control = null;
        for (Map.Entry<CompatibilitySpace, AtomicInteger> holder : holders.entrySet()) {
            int count = holder.getValue().get();
            if (count <= 0) {
                continue;
            }
            if (control == null) {
                Lock first = new Lock(holder.getKey(), ref, ContainerLock.CIS);
                first.count = count;
                control = new LockControl(first, ref);
                continue;
            }
            for (int i = 0; i < count; i++) {
                Lock granted = control.addLock(this, holder.getKey(), ContainerLock.CIS);
                if (SanityManager.DEBUG) {
                    SanityManager.ASSERT(granted.getCount() != 0,
                            "materialized CIS lock was not granted");
                }
            }
        }
        return control;
    }


    private LockControl materializeRecordReadHolders(
            Lockable ref,
            ConcurrentHashMap<CompatibilitySpace, AtomicInteger> holders) {
        LockControl control = null;
        for (Map.Entry<CompatibilitySpace, AtomicInteger> holder : holders.entrySet()) {
            int count = holder.getValue().get();
            if (count <= 0) {
                continue;
            }
            if (control == null) {
                Lock first = new Lock(holder.getKey(), ref, RowLock.RS2);
                first.count = count;
                control = new LockControl(first, ref);
                continue;
            }
            for (int i = 0; i < count; i++) {
                Lock granted = control.addLock(this, holder.getKey(), RowLock.RS2);
                if (SanityManager.DEBUG) {
                    SanityManager.ASSERT(granted.getCount() != 0,
                            "materialized RecordId RS2 lock was not granted");
                }
            }
        }
        return control;
    }

    private static boolean isFastRecordReadRequest(Lockable ref, Object qualifier) {
        return FAST_RECORD_READ_ENABLED
                && qualifier == RowLock.RS2
                && "org.apache.derby.impl.store.raw.data.RecordId".equals(
                        ref.getClass().getName());
    }

    private Lock tryFastRecordReadLock(CompatibilitySpace compatibilitySpace,
                                       Lockable ref,
                                       Object qualifier) {
        if (!isFastRecordReadRequest(ref, qualifier)) {
            return null;
        }
        Entry entry = locks.get(ref);
        if (entry == null) {
            return null;
        }
        Control control = entry.control;
        return control instanceof FastRecordReadControl
                ? ((FastRecordReadControl) control).tryAcquire(compatibilitySpace)
                : null;
    }

    private Lock promoteFastRecordRead(Entry entry,
                                       Control control,
                                       CompatibilitySpace compatibilitySpace,
                                       Lockable ref,
                                       Object qualifier) {
        if (!isFastRecordReadRequest(ref, qualifier) || !(control instanceof Lock)) {
            return null;
        }
        Lock first = (Lock) control;
        if (first.getQualifier() != RowLock.RS2 || first.getCount() != 1
                || first.getCompatabilitySpace() == compatibilitySpace) {
            return null;
        }
        FastRecordReadControl fastControl = new FastRecordReadControl(first);
        Lock granted = fastControl.tryAcquire(compatibilitySpace);
        if (granted == null) {
            return null;
        }
        entry.control = fastControl;
        if (HOT_STATE_DIAGNOSTICS) {
            FAST_RECORD_READ_PROMOTIONS.increment();
        }
        return granted;
    }

    private boolean tryFastRecordReadUnlock(Entry entry, Latch item, int unlockCount) {
        if (entry == null
                || !isFastRecordReadRequest(item.getLockable(), item.getQualifier())) {
            return false;
        }
        Control control = entry.control;
        if (!(control instanceof FastRecordReadControl)) {
            return false;
        }
        FastRecordReadControl fastControl = (FastRecordReadControl) control;
        int remaining = fastControl.tryRelease(item, unlockCount);
        if (remaining < 0) {
            return false;
        }
        return true;
    }

    private static boolean isFastCisRequest(Lockable ref, Object qualifier) {
        return ref instanceof ContainerKey && qualifier == ContainerLock.CIS;
    }

    private Lock tryFastCisLock(CompatibilitySpace compatibilitySpace,
                                Lockable ref,
                                Object qualifier) {
        if (!isFastCisRequest(ref, qualifier)) {
            return null;
        }
        Entry entry = locks.get(ref);
        if (entry == null) {
            return null;
        }
        Control control = entry.control;
        return control instanceof FastCisControl
                ? ((FastCisControl) control).tryAcquire(compatibilitySpace)
                : null;
    }

    private boolean tryFastCisUnlock(Entry entry, Latch item, int unlockCount) {
        if (entry == null || !isFastCisRequest(item.getLockable(), item.getQualifier())) {
            return false;
        }
        Control control = entry.control;
        return control instanceof FastCisControl
                && ((FastCisControl) control).tryRelease(item, unlockCount);
    }

    /**
     * Get an entry from the lock table. If no entry exists for the
     * <code>Lockable</code>, insert an entry. The returned entry will be
     * locked and is guaranteed to still be present in the table.
     *
     * @param ref the <code>Lockable</code> whose entry to return
     * @return the entry for the <code>Lockable</code>, locked for exclusive
     * access
     */
    private Entry getEntry(Lockable ref) {
        Entry e = locks.get(ref);
        while (true) {
            if (e != null) {
                e.lock();
                if (e.control != null) {
                    // entry is found and in use, return it
                    return e;
                }
                // entry is empty, hence it was removed from the table after we
                // retrieved it. Try to reuse it later.
            } else {
                // no entry found, create a new one
                e = new Entry(ref);
                e.lock();
            }
            // reinsert empty entry, or insert the new entry
            Entry current = locks.putIfAbsent(ref, e);
            if (current == null) {
                // successfully (re-)inserted entry, return it
                return e;
            }
            // someone beat us, unlock the old entry and retry with the entry
            // they inserted
            e.unlock();
            e = current;
        }
    }

    /**
     * Check whether there is a deadlock. Make sure that only one thread enters
     * deadlock detection at a time.
     *
     * @param entry the entry in the lock table for the lock request that
     * triggered deadlock detection
     * @param waitingLock the waiting lock
     * @param wakeupReason the reason for waking up the waiter
     * @return an object describing the deadlock
     */
    private Object[] checkDeadlock(Entry entry, ActiveLock waitingLock,
                                   byte wakeupReason) {
        LockControl control = (LockControl) entry.control;
        // make sure that the entry is not blocking other threads performing
        // deadlock detection since we have to wait for them to finish
        entry.enterDeadlockDetection();
        synchronized (Deadlock.class) {
            try {
                return Deadlock.look(factory, this, control, waitingLock,
                                     wakeupReason);
            } finally {
                // unlock all entries we visited
                for (Entry e : seenByDeadlockDetection) {
                    e.unlock();
                }
                seenByDeadlockDetection = null;
                // re-lock the entry
                entry.exitDeadlockDetection();
            }
        }
    }

    static void resetLockEntryDiagnosticsForTesting() {
        CONTAINER_KEY_ENTRY_STATS.reset();
        RECORD_ID_ENTRY_STATS.reset();
        OTHER_ENTRY_STATS.reset();
    }

    static String[] snapshotLockEntryDiagnosticsForTesting() {
        return new String[] {
            diagnosticRow("ContainerKey", CONTAINER_KEY_ENTRY_STATS),
            diagnosticRow("RecordId", RECORD_ID_ENTRY_STATS),
            diagnosticRow("Other", OTHER_ENTRY_STATS)
        };
    }

    static void resetHotStateDiagnosticsForTesting() {
        FAST_CIS_ACQUIRE_ATTEMPTS.reset();
        FAST_CIS_RELEASE_ATTEMPTS.reset();
        FAST_CIS_HOLDER_CREATES.reset();
        FAST_CIS_HOLDER_REMOVES.reset();
        FAST_CIS_STRIPE_OVERLAP.reset();
        FAST_RECORD_READ_PROMOTIONS.reset();
        FAST_RECORD_READ_ACQUIRE_HITS.reset();
        FAST_RECORD_READ_RELEASE_HITS.reset();
        FAST_RECORD_READ_FREEZES.reset();
        FAST_RECORD_READ_RETIREMENTS.reset();
    }

    static String[] snapshotHotStateDiagnosticsForTesting() {
        return new String[] {
            hotStateRow("FastCisControl", "fastCisAcquireAttempts", FAST_CIS_ACQUIRE_ATTEMPTS),
            hotStateRow("FastCisControl", "fastCisReleaseAttempts", FAST_CIS_RELEASE_ATTEMPTS),
            hotStateRow("FastCisControl", "fastCisHolderCreates", FAST_CIS_HOLDER_CREATES),
            hotStateRow("FastCisControl", "fastCisHolderRemoves", FAST_CIS_HOLDER_REMOVES),
            hotStateRow("FastCisControl", "fastCisStripeOverlap", FAST_CIS_STRIPE_OVERLAP),
            hotStateRow("FastRecordReadControl", "promotions", FAST_RECORD_READ_PROMOTIONS),
            hotStateRow("FastRecordReadControl", "acquireHits", FAST_RECORD_READ_ACQUIRE_HITS),
            hotStateRow("FastRecordReadControl", "releaseHits", FAST_RECORD_READ_RELEASE_HITS),
            hotStateRow("FastRecordReadControl", "freezes", FAST_RECORD_READ_FREEZES),
            hotStateRow("FastRecordReadControl", "retirements", FAST_RECORD_READ_RETIREMENTS)
        };
    }

    private static String hotStateRow(String component, String metric, LongAdder value) {
        return component + "," + metric + "," + value.sum();
    }

    private static LockEntryStats lockEntryStats(Lockable ref) {
        String name = ref.getClass().getName();
        if ("org.apache.derby.iapi.store.raw.ContainerKey".equals(name)) {
            return CONTAINER_KEY_ENTRY_STATS;
        }
        if ("org.apache.derby.impl.store.raw.data.RecordId".equals(name)) {
            return RECORD_ID_ENTRY_STATS;
        }
        return OTHER_ENTRY_STATS;
    }

    private static String diagnosticRow(String name, LockEntryStats stats) {
        return name + "," + stats.acquisitions.sum() + ","
                + stats.contendedAcquisitions.sum() + "," + stats.waitNanos.sum();
    }

    static void resetLogicalWaitDiagnosticsForTesting() {
        CONTAINER_KEY_WAIT_STATS.reset();
        RECORD_ID_WAIT_STATS.reset();
        OTHER_WAIT_STATS.reset();
    }

    static String[] snapshotLogicalWaitDiagnosticsForTesting() {
        return new String[] {
            logicalWaitRow("ContainerKey", CONTAINER_KEY_WAIT_STATS),
            logicalWaitRow("RecordId", RECORD_ID_WAIT_STATS),
            logicalWaitRow("Other", OTHER_WAIT_STATS)
        };
    }

    private static void recordLogicalWait(Lockable ref, long waitNanos) {
        if (!LOCK_WAIT_DIAGNOSTICS) {
            return;
        }
        LogicalWaitStats stats = logicalWaitStats(ref);
        stats.waits.increment();
        stats.waitNanos.add(waitNanos);
        stats.maxWaitNanos.accumulate(waitNanos);
    }

    private static LogicalWaitStats logicalWaitStats(Lockable ref) {
        String name = ref.getClass().getName();
        if ("org.apache.derby.iapi.store.raw.ContainerKey".equals(name)) {
            return CONTAINER_KEY_WAIT_STATS;
        }
        if ("org.apache.derby.impl.store.raw.data.RecordId".equals(name)) {
            return RECORD_ID_WAIT_STATS;
        }
        return OTHER_WAIT_STATS;
    }

    private static String logicalWaitRow(String name, LogicalWaitStats stats) {
        return name + "," + stats.waits.sum() + "," + stats.waitNanos.sum()
                + "," + stats.maxWaitNanos.get();
    }

	/*
	** Public Methods
	*/

	/**
	 *	Lock an object within a specific compatibility space.
	 *
	 *	@param	compatibilitySpace Compatibility space.
	 *	@param	ref Lockable reference.
	 *	@param	qualifier Qualifier.
	 *	@param	timeout Timeout in milli-seconds
	 *
	 *	@return	Object that represents the lock.
	 *
	 *	@exception	StandardException Standard Derby policy.

	*/
	public Lock lockObject(CompatibilitySpace compatibilitySpace, Lockable ref,
						   Object qualifier, int timeout)
		throws StandardException
	{		
		if (SanityManager.DEBUG) {

			if (SanityManager.DEBUG_ON("memoryLeakTrace")) {

				if (locks.size() > 1000)
					System.out.println("memoryLeakTrace:LockSet: " +
                                           locks.size());
			}
		}

		LockControl control;
		Lock lockItem;
        String  lockDebug = null;
        boolean blockedByParent = false;

        Lock fastCis = tryFastCisLock(compatibilitySpace, ref, qualifier);
        if (fastCis != null) {
            return fastCis;
        }
        Lock fastRecordRead = tryFastRecordReadLock(
                compatibilitySpace, ref, qualifier);
        if (fastRecordRead != null) {
            return fastRecordRead;
        }

        Entry entry = getEntry(ref);
        try {

            Control gc = entry.control;
            if (gc instanceof FastCisControl) {
                FastCisControl fastControl = (FastCisControl) gc;
                if (isFastCisRequest(ref, qualifier)) {
                    fastCis = fastControl.tryAcquire(compatibilitySpace);
                    if (fastCis != null) {
                        return fastCis;
                    }
                }
                gc = fastControl.freeze();
                entry.control = gc;
            }
            if (gc instanceof FastRecordReadControl) {
                FastRecordReadControl fastControl = (FastRecordReadControl) gc;
                if (isFastRecordReadRequest(ref, qualifier)) {
                    fastRecordRead = fastControl.tryAcquire(compatibilitySpace);
                    if (fastRecordRead != null) {
                        return fastRecordRead;
                    }
                }
                gc = fastControl.freeze();
                entry.control = gc;
            }

			if (gc == null) {
                if (isFastCisRequest(ref, qualifier)) {
                    FastCisControl fastControl = new FastCisControl(ref);
                    Lock granted = fastControl.tryAcquire(compatibilitySpace);
                    entry.control = fastControl;
                    return granted;
                }

				// object is not locked, can be granted
				Lock gl = new Lock(compatibilitySpace, ref, qualifier);

				gl.grant();

				entry.control = gl;

				return gl;
			}

            fastRecordRead = promoteFastRecordRead(
                    entry, gc, compatibilitySpace, ref, qualifier);
            if (fastRecordRead != null) {
                return fastRecordRead;
            }

			control = gc.getLockControl();
			if (control != gc) {
				entry.control = control;
			}

			if (SanityManager.DEBUG) {
				SanityManager.ASSERT(ref.equals(control.getLockable()));
				// ASSERT item is in the list
                SanityManager.ASSERT(
                    locks.get(control.getLockable()).control == control);
			}

			lockItem = control.addLock(this, compatibilitySpace, qualifier);

			if (lockItem.getCount() != 0) {
				return lockItem;
			}

            //
            // This logic supports the use-case of DERBY-6554.
            //
            blockedByParent =
                (timeout == 0) &&
                compatibilitySpace.getOwner().isNestedOwner() &&
                control.blockedByParent( lockItem );

			if (
                AbstractPool.noLockWait(timeout, compatibilitySpace) ||
                blockedByParent
                )
            {
    			// remove all trace of lock
    			control.giveUpWait(lockItem, this);

               if (SanityManager.DEBUG) 
                {
                    if (SanityManager.DEBUG_ON("DeadlockTrace"))
                    {

                        SanityManager.showTrace(new Throwable());

                        // The following dumps the lock table as it 
                        // exists at the time a timeout is about to 
                        // cause a deadlock exception to be thrown.

                        lockDebug = 
                            DiagnosticUtil.toDiagString(lockItem)   +
                            "\nCould not grant lock with zero timeout, " +
                            "here's the table";

                        // We cannot hold a lock on an entry while calling
                        // toDebugString() since it will lock other entries in
                        // the lock table. Holding the lock could cause a
                        // deadlock.
                        entry.unlock();
                        try {
                            lockDebug += toDebugString();
                        } finally {
                            // Re-lock the entry so that the outer finally
                            // clause doesn't fail.
                            entry.lock();
                        }
                    }
                }

               return null;
			}

        } finally {
            entry.unlock();
            
            if ( blockedByParent )
            {
                throw StandardException.newException
                    ( SQLState.SELF_DEADLOCK );
            }
        }

		boolean deadlockWait = false;
		int actualTimeout;

		if (timeout == C_LockFactory.WAIT_FOREVER)
		{
			// always check for deadlocks as there should not be any
			deadlockWait = true;
			if ((actualTimeout = deadlockTimeout) == C_LockFactory.WAIT_FOREVER)
				actualTimeout = Property.DEADLOCK_TIMEOUT_DEFAULT * 1000;
		}
		else
		{

			if (timeout == C_LockFactory.TIMED_WAIT)
				timeout = actualTimeout = waitTimeout;
			else
				actualTimeout = timeout;


			// five posible cases
			// i)   timeout -1, deadlock -1         -> 
            //          just wait forever, no deadlock check
			// ii)  timeout >= 0, deadlock -1       -> 
            //          just wait for timeout, no deadlock check
			// iii) timeout -1, deadlock >= 0       -> 
            //          wait for deadlock, then deadlock check, 
            //          then infinite timeout
			// iv)  timeout >=0, deadlock < timeout -> 
            //          wait for deadlock, then deadlock check, 
            //          then wait for (timeout - deadlock)
			// v)   timeout >=0, deadlock >= timeout -> 
            //          just wait for timeout, no deadlock check


			if (deadlockTimeout >= 0) {

				if (actualTimeout < 0) {
					// infinite wait but perform a deadlock check first
					deadlockWait = true;
					actualTimeout = deadlockTimeout;
				} else if (deadlockTimeout < actualTimeout) {

					// deadlock wait followed by a timeout wait

					deadlockWait = true;
					actualTimeout = deadlockTimeout;

					// leave timeout as the remaining time
					timeout -= deadlockTimeout;
				}
			}
		}


        ActiveLock waitingLock = (ActiveLock) lockItem;
        lockItem = null;
        long logicalWaitStarted = LOCK_WAIT_DIAGNOSTICS ? System.nanoTime() : 0L;

        int earlyWakeupCount = 0;
        long startWaitTime = 0;

        try {
forever:	for (;;) {

                byte wakeupReason = 0;
                ActiveLock nextWaitingLock = null;
                Object[] deadlockData = null;

                try {
                    try {
                        wakeupReason = waitingLock.waitForGrant(actualTimeout);
                    } catch(StandardException e) {
                        // DERBY-4711: If waitForGrant() fails, we need to
                        // remove ourselves from the queue so that those
                        // behind us in the queue don't get stuck waiting for
                        // us.
                        nextWaitingLock = control.getNextWaiter(waitingLock, true, this);
                        throw e;
                    }

                    boolean willQuitWait;
                    Enumeration timeoutLockTable = null;
                    long currentTime = 0;
        
                    entry.lock();
                    try {

                        if (control.isGrantable(
                                control.firstWaiter() == waitingLock,
                                compatibilitySpace,
                                qualifier)) {

                            // Yes, we are granted, put us on the granted queue.
                            control.grant(waitingLock);

                            // Remove from the waiting queue & get next waiter
                            nextWaitingLock = 
                                control.getNextWaiter(waitingLock, true, this);

                            return waitingLock;
                        }

                        // try again later
                        waitingLock.clearPotentiallyGranted(); 

                        willQuitWait = 
                            (wakeupReason != Constants.WAITING_LOCK_GRANT);

                        if (((wakeupReason == Constants.WAITING_LOCK_IN_WAIT) &&
                                    deadlockWait) ||
                            (wakeupReason == Constants.WAITING_LOCK_DEADLOCK))
                        {

                            // check for a deadlock, even if we were woken up 
                            // because we were selected as a victim we still 
                            // check because the situation may have changed.
                            deadlockData = 
                                checkDeadlock(entry, waitingLock, wakeupReason);

                            if (deadlockData == null) {
                                // we don't have a deadlock
                                deadlockWait = false;

                                actualTimeout = timeout;
                                startWaitTime = 0;
                                willQuitWait = false;
                            } else {
                                willQuitWait = true;
                            }
                        }

                        nextWaitingLock = 
                            control.getNextWaiter(
                                waitingLock, willQuitWait, this);


                        // If we were not woken by another then we have
                        // timed out. Either deadlock out or timeout
                        if (SanityManager.DEBUG &&
                                SanityManager.DEBUG_ON("DeadlockTrace") &&
                                willQuitWait) {
                            // Generate the first part of the debug message
                            // while holding the lock on entry, so that we have
                            // exclusive access to waitingLock. Wait until the
                            // entry has been unlocked before appending the
                            // contents of the lock table (to avoid deadlocks).
                            lockDebug =
                                DiagnosticUtil.toDiagString(waitingLock) +
                                "\nGot deadlock/timeout, here's the table";
                        }

                    } finally {
                        entry.unlock();
                    }

                    // need to do this outside of the synchronized block as the
                    // message text building (timeouts and deadlocks) may 
                    // involve getting locks to look up table names from 
                    // identifiers.

                    if (willQuitWait)
                    {
                        if (deadlockTrace && (deadlockData == null)) {
                            // if ending lock request due to lock timeout
                            // want a copy of the LockTable and the time,
                            // in case of deadlock deadlockData has the
                            // info we need.
                            currentTime = System.currentTimeMillis();
                            timeoutLockTable =
                                factory.makeVirtualLockTable();
                        }

                        if (SanityManager.DEBUG)
                        {
                            if (SanityManager.DEBUG_ON("DeadlockTrace")) {
                                SanityManager.showTrace(new Throwable());

                                // The following dumps the lock table as it
                                // exists at the time a timeout is about to
                                // cause a deadlock exception to be thrown.

                                lockDebug += toDebugString();
                            }

                            if (lockDebug != null)
                            {
                                String type = 
                                    ((deadlockData != null) ? 
                                         "deadlock:" : "timeout:"); 

                                SanityManager.DEBUG_PRINT(
                                    type,
                                    "wait on lockitem caused " + type + 
                                    lockDebug);
                            }

                        }

                        if (deadlockData == null)
                        {
                            // ending wait because of lock timeout or interrupt

                            if (wakeupReason ==
                                    Constants.WAITING_LOCK_INTERRUPTED) {

                                throw StandardException.
                                    newException(SQLState.CONN_INTERRUPT);

                            } else if (deadlockTrace)
                            {   
                                // Turn ON derby.locks.deadlockTrace to build 
                                // the lockTable.
                                    
                                
                                throw Timeout.buildException(
                                    waitingLock, timeoutLockTable, currentTime);
                            }
                            else
                            {
                                StandardException se = 
                                    StandardException.newException(
                                        SQLState.LOCK_TIMEOUT);

                                throw se;
                            }
                        }
                        else 
                        {
                            // ending wait because of lock deadlock.

                            throw Deadlock.buildException(
                                    factory, deadlockData);
                        }
                    }
                } finally {
                    if (nextWaitingLock != null) {
                        nextWaitingLock.wakeUp(Constants.WAITING_LOCK_GRANT);
                        nextWaitingLock = null;
                    }
                }

                if (actualTimeout != C_LockFactory.WAIT_FOREVER) {

                    if (wakeupReason != Constants.WAITING_LOCK_IN_WAIT)
                        earlyWakeupCount++;

                    if (earlyWakeupCount > 5) {

                        long now = System.currentTimeMillis();

                        if (startWaitTime != 0) {

                            long sleepTime = now - startWaitTime;

                            actualTimeout -= sleepTime;
                        }

                        startWaitTime = now;
                    }
                }


            } // for(;;)
        } finally {
            if (logicalWaitStarted != 0L) {
                recordLogicalWait(ref, System.nanoTime() - logicalWaitStarted);
            }
        }
	}

	/**
		Unlock an object, previously locked by lockObject(). 

		If unlockCOunt is not zero then the lock will be unlocked
		that many times, otherwise the unlock count is taken from
		item.

	*/
	public void unlock(Latch item, int unlockCount) {
        // assume LockEntry is there
        Entry entry = locks.get(item.getLockable());
        if (tryFastCisUnlock(entry, item, unlockCount)
                || tryFastRecordReadUnlock(entry, item, unlockCount)) {
            return;
        }
        entry.lock();
        try {
            if (entry.control instanceof FastRecordReadControl) {
                entry.control = ((FastRecordReadControl) entry.control).freeze();
            }
            unlock(entry, item, unlockCount);
        } finally {
            entry.unlock();
        }
    }

    /**
     * Unlock an object, previously locked by lockObject().
     *
     * @param entry the entry in which the lock is contained (the current
     * thread must have locked the entry)
     * @param item the item to unlock
     * @param unlockCount the number of times to unlock the item (if zero, take
     * the unlock count from item)
     */
    private void unlock(Entry entry, Latch item, int unlockCount) {
		if (SanityManager.DEBUG) {
            SanityManager.ASSERT(entry.mutex.isHeldByCurrentThread());
			if (SanityManager.DEBUG_ON(Constants.LOCK_TRACE)) {
				/*
				** I don't like checking the trace flag twice, but SanityManager
				** doesn't provide a way to get to the debug trace stream
				** directly.
				*/
				SanityManager.DEBUG(
                    Constants.LOCK_TRACE, 
                    "Release lock: " + DiagnosticUtil.toDiagString(item));
			}
		}

		boolean tryGrant = false;
		ActiveLock nextGrant = null;

        Control control = entry.control;
			
			if (SanityManager.DEBUG) {

                // only valid Lock's expected
                if (item.getLockable() == null)
                {
                    SanityManager.THROWASSERT(
                        "item.getLockable() = null." +
                        "unlockCount " + unlockCount + 
                        "item = " + DiagnosticUtil.toDiagString(item));
                }

                // only valid Lock's expected
                if (control == null)
                {
                    SanityManager.THROWASSERT(
                        "control = null." +
                        "unlockCount " + unlockCount + 
                        "item = " + DiagnosticUtil.toDiagString(item));
                }

                SanityManager.ASSERT(
                    locks.get(control.getLockable()).control == control);

				if ((unlockCount != 0) && (unlockCount > item.getCount()))
					SanityManager.THROWASSERT("unlockCount " + unlockCount +
						" larger than actual lock count " + item.getCount() + " item " + item);
			}

			tryGrant = control.unlock(item, unlockCount);
			item = null;

			boolean mayBeEmpty = true;
			if (tryGrant) {
				nextGrant = control.firstWaiter();
				if (nextGrant != null) {
					mayBeEmpty = false;
					if (!nextGrant.setPotentiallyGranted())
						nextGrant = null;
				}
			}

			if (mayBeEmpty) {
				if (control.isEmpty()) {
					// no-one granted, no-one waiting, remove lock control
					locks.remove(control.getLockable());
                    entry.control = null;
				}
				return;
			}

		if (tryGrant && (nextGrant != null)) {
			nextGrant.wakeUp(Constants.WAITING_LOCK_GRANT);
		}
	}

    /**
     * Unlock an object once if it is present in the specified group. Also
     * remove the object from the group.
     *
     * @param space the compatibility space
     * @param ref a reference to the locked object
     * @param qualifier qualifier of the lock
     * @param group a map representing the locks in a group
     * @return the corresponding lock in the group map, or <code>null</code> if
     * the object was not unlocked
     */
    public Lock unlockReference(CompatibilitySpace space, Lockable ref,
                                Object qualifier, Map group) {

        Entry entry = locks.get(ref);
        if (entry == null) {
            return null;
        }

        if (isFastCisRequest(ref, qualifier)) {
            Control fast = entry.control;
            if (fast instanceof FastCisControl) {
                Lock key = new Lock(space, ref, qualifier);
                Lock lockInGroup = (Lock) group.get(key);
                if (lockInGroup != null
                        && ((FastCisControl) fast).tryRelease(lockInGroup, 1)) {
                    group.remove(key);
                    return lockInGroup;
                }
            }
        }
        if (isFastRecordReadRequest(ref, qualifier)) {
            Lock key = new Lock(space, ref, qualifier);
            Lock lockInGroup = (Lock) group.get(key);
            if (lockInGroup != null && tryFastRecordReadUnlock(entry, lockInGroup, 1)) {
                group.remove(key);
                return lockInGroup;
            }
        }

        entry.lock();
        try {
            Control control = entry.control;
            if (control instanceof FastRecordReadControl) {
                control = ((FastRecordReadControl) control).freeze();
                entry.control = control;
            }
            if (control == null) {
                return null;
            }

            Lock setLock = control.getLock(space, qualifier);
            if (setLock == null) {
                return null;
            }

            Lock lockInGroup = (Lock) group.remove(setLock);
            if (lockInGroup != null) {
                unlock(entry, lockInGroup, 1);
            }

            return lockInGroup;

        } finally {
            entry.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean zeroDurationLockObject(
        CompatibilitySpace space, Lockable ref, Object qualifier, int timeout)
            throws StandardException {

        if (SanityManager.DEBUG) {
            if (SanityManager.DEBUG_ON(Constants.LOCK_TRACE)) {
                D_LockControl.debugLock(
                    "Zero Duration Lock Request before Grant: ",
                    space, null, ref, qualifier, timeout);
                if (SanityManager.DEBUG_ON(Constants.LOCK_STACK_TRACE)) {
                    // The following will print the stack trace of the lock
                    // request to the log.
                    Throwable t = new Throwable();
                    java.io.PrintWriter istream =
                        SanityManager.GET_DEBUG_STREAM();
                    istream.println("Stack trace of lock request:");
                    t.printStackTrace(istream);
                }
            }
        }

        // Very fast zeroDurationLockObject() for unlocked objects.
        // If no entry exists in the lock manager for this reference
        // then it must be unlocked.
        // If the object is locked then we perform a grantable
        // check, skipping over any waiters.
        // If the caller wants to wait and the lock cannot
        // be granted then we do the slow join the queue and
        // release the lock method.

        Entry entry = locks.get(ref);
        if (entry == null) {
            return true;
        }

        entry.lock();
        try {
            Control control = entry.control;
            if (control == null) {
                return true;
            }
            if (control instanceof FastRecordReadControl
                    && !isFastRecordReadRequest(ref, qualifier)) {
                control = ((FastRecordReadControl) control).freeze();
                entry.control = control;
                if (control == null) {
                    return true;
                }
            }

            // If we are grantable, ignoring waiting locks then
            // we can also grant this request now, as skipping
            // over the waiters won't block them as we release
            // the lock rightway.
            if (control.isGrantable(true, space, qualifier)) {
                return true;
            }

            // can't be granted and are not willing to wait.
            if (AbstractPool.noLockWait(timeout, space)) {
                return false;
            }
        } finally {
            entry.unlock();
        }

        Lock lock = lockObject(space, ref, qualifier, timeout);

        if (SanityManager.DEBUG) {
            if (SanityManager.DEBUG_ON(Constants.LOCK_TRACE)) {
                D_LockControl.debugLock(
                    "Zero Lock Request Granted: ",
                    space, null, ref, qualifier, timeout);
            }
        }

        // and simply unlock it once
        unlock(lock, 1);

        return true;
    }

    /**
     * Set the deadlock timeout.
     *
     * @param timeout deadlock timeout in milliseconds
     */
    public void setDeadlockTimeout(int timeout) {
        deadlockTimeout = timeout;
    }

    /**
     * Set the wait timeout.
     *
     * @param timeout wait timeout in milliseconds
     */
    public void setWaitTimeout(int timeout) {
        waitTimeout = timeout;
    }
	
    /**
     * Get the wait timeout in milliseconds.
     */
    public int getWaitTimeout() { return waitTimeout; }
    
	/*
	** Non public methods
	*/
//EXCLUDE-START-lockdiag- 

	public void setDeadlockTrace(boolean val)
	{
		// set this without synchronization
		deadlockTrace = val;
	}			
//EXCLUDE-END-lockdiag- 

    private String toDebugString()
    {
        if (SanityManager.DEBUG)
        {
            String str = "";

            int i = 0;
            for (Entry entry : locks.values())
            {
                entry.lock();
                try {
                    str += "\n  lock[" + i + "]: " +
                        DiagnosticUtil.toDiagString(entry.control);
                } finally {
                    entry.unlock();
                }
            }

            return(str);
        }
        else
        {
            return(null);
        }
    }

    /**
     * Add all waiters in this lock table to a <code>Map</code> object.
     * This method can only be called by the thread that is currently
     * performing deadlock detection. All entries that are visited in the lock
     * table will be locked when this method returns. The entries that have
     * been seen and locked will be unlocked after the deadlock detection has
     * finished.
     */
    public void addWaiters(Map<Object,Object> waiters) {
        seenByDeadlockDetection = new ArrayList<Entry>(locks.size());
        for (Entry entry : locks.values()) {
            seenByDeadlockDetection.add(entry);
            entry.lockForDeadlockDetection();
            if (entry.control != null) {
                entry.control.addWaiters(waiters);
            }
        }
    }

//EXCLUDE-START-lockdiag- 
	/**
	 * make a shallow clone of myself and my lock controls
	 */
    public Map<Lockable, Control> shallowClone() {
        HashMap<Lockable, Control> clone = new HashMap<Lockable, Control>();

        for (Entry entry : locks.values()) {
            entry.lock();
            try {
                Control control = entry.control;
                if (control != null) {
                    clone.put(control.getLockable(), control.shallowClone());
                }
            } finally {
                entry.unlock();
            }
		}

		return clone;
	}
//EXCLUDE-END-lockdiag- 

	/**
	 * Increase blockCount by one.
	 */
	public void oneMoreWaiter() {
        blockCount.incrementAndGet();
	}

	/**
	 * Decrease blockCount by one.
	 */
	public void oneLessWaiter() {
		blockCount.decrementAndGet();
	}

    /**
     * Check whether anyone is blocked.
     * @return <code>true</code> if someone is blocked, <code>false</code>
     * otherwise
     */
	public boolean anyoneBlocked() {
        int blocked = blockCount.get();
		if (SanityManager.DEBUG) {
			SanityManager.ASSERT(
				blocked >= 0, "blockCount should not be negative");
		}
		return blocked != 0;
	}
}
