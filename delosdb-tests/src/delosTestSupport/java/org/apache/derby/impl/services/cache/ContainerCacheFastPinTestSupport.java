/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.services.cache.CacheManager;
import org.apache.derby.iapi.services.cache.Cacheable;
import org.apache.derby.iapi.services.cache.CacheableFactory;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.shared.common.error.StandardException;

/** Package-private correctness proofs for stable ContainerCache fast pins. */
public final class ContainerCacheFastPinTestSupport {
    private ContainerCacheFastPinTestSupport() {
    }

    public static void verifyFastPinEvictionHandoff() {
        ContainerKey key = new ContainerKey(0, 42);
        TestCacheable item = new TestCacheable(key);
        CacheEntry entry = stableEntry(item);

        Cacheable pinned = entry.tryFastContainerKeep(key);
        if (pinned != item) {
            throw new AssertionError("stable container was not fast-pinned");
        }

        entry.lock();
        try {
            if (entry.freezeFastContainerAccessIfUnkept()) {
                throw new AssertionError("eviction freeze ignored an active fast pin");
            }
        } finally {
            entry.unlock();
        }

        if (!entry.tryFastContainerUnkeep(item)) {
            throw new AssertionError("stable container was not fast-unpinned");
        }

        entry.lock();
        try {
            if (!entry.freezeFastContainerAccessIfUnkept()) {
                throw new AssertionError("unused container could not freeze for eviction");
            }
            if (entry.tryFastContainerKeep(key) != null) {
                throw new AssertionError("fast pin crossed a frozen lifecycle boundary");
            }
            entry.unfreezeFastContainerAccess();
        } finally {
            entry.unlock();
        }

        pinned = entry.tryFastContainerKeep(key);
        if (pinned != item || !entry.tryFastContainerUnkeep(item)) {
            throw new AssertionError("fast pin did not recover after cancelled eviction");
        }
    }

    public static void verifyRemoveWaitsForFastPin() throws Exception {
        ContainerKey key = new ContainerKey(0, 84);
        TestCacheable item = new TestCacheable(key);
        CacheEntry entry = stableEntry(item);

        if (entry.tryFastContainerKeep(key) != item
                || entry.tryFastContainerKeep(key) != item) {
            throw new AssertionError("failed to establish two fast container pins");
        }

        CountDownLatch removing = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread remover = new Thread(() -> {
            entry.lock();
            try {
                entry.freezeFastContainerAccess();
                removing.countDown();
                entry.unkeepForRemove();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                entry.unlock();
            }
        }, "container-cache-remove-proof");
        remover.start();

        removing.await();
        if (!entry.tryFastContainerUnkeep(item)) {
            throw new AssertionError("active fast pin could not release during removal");
        }
        remover.join(5_000L);
        if (remover.isAlive()) {
            throw new AssertionError("remove did not drain the outstanding fast pin");
        }
        if (failure.get() != null) {
            throw new AssertionError("remove handoff failed", failure.get());
        }
        if (entry.tryFastContainerKeep(key) != null) {
            throw new AssertionError("frozen removed entry accepted a new fast pin");
        }
    }

    public static void verifyConcurrentFastPins() throws Exception {
        final int threads = 8;
        final int iterations = 25_000;
        ContainerKey key = new ContainerKey(0, 126);
        TestCacheable item = new TestCacheable(key);
        CacheEntry entry = stableEntry(item);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        Cacheable pinned = entry.tryFastContainerKeep(key);
                        if (pinned != item) {
                            throw new AssertionError("stable fast pin unexpectedly fell back");
                        }
                        if (!entry.tryFastContainerUnkeep(item)) {
                            throw new AssertionError("stable fast unpin failed");
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "container-cache-fast-pin-" + i);
            worker.start();
        }

        start.countDown();
        done.await();
        if (failure.get() != null) {
            throw new AssertionError("concurrent container fast-pin proof failed", failure.get());
        }

        entry.lock();
        try {
            if (!entry.freezeFastContainerAccessIfUnkept()) {
                throw new AssertionError("fast-pin stress leaked a keep reference");
            }
            entry.unfreezeFastContainerAccess();
        } finally {
            entry.unlock();
        }
    }

    public static void verifyCrossThreadFastRelease() throws Exception {
        ContainerKey key = new ContainerKey(0, 147);
        TestCacheable item = new TestCacheable(key);
        CacheEntry entry = stableEntry(item);

        if (entry.tryFastContainerKeep(key) != item) {
            throw new AssertionError("failed to establish fast pin for cross-thread release");
        }

        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread releaser = new Thread(() -> {
            try {
                if (!entry.tryFastContainerUnkeep(item)) {
                    throw new AssertionError("cross-thread fast release failed");
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "container-cache-cross-thread-release");
        releaser.start();
        releaser.join(5_000L);
        if (releaser.isAlive()) {
            throw new AssertionError("cross-thread fast release did not finish");
        }
        if (failure.get() != null) {
            throw new AssertionError("cross-thread fast release failed", failure.get());
        }

        entry.lock();
        try {
            if (!entry.freezeFastContainerAccessIfUnkept()) {
                throw new AssertionError("cross-thread release leaked a striped pin");
            }
            entry.unfreezeFastContainerAccess();
        } finally {
            entry.unlock();
        }
    }

    public static void verifyConcurrentCacheLifecycle() throws Exception {
        ConcurrentCache cache = new ConcurrentCache(
                new TestCacheableFactory(), "ContainerCache", 16, 64);
        ContainerKey key = new ContainerKey(0, 168);
        Cacheable first = cache.find(key);
        cache.release(first);

        final int threads = 8;
        final int iterations = 10_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        Cacheable item = cache.find(key);
                        if (item == null || !key.equals(item.getIdentity())) {
                            throw new AssertionError("ConcurrentCache returned wrong container");
                        }
                        cache.release(item);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "container-cache-integration-" + i);
            worker.start();
        }

        start.countDown();
        done.await();
        if (failure.get() != null) {
            throw new AssertionError("ConcurrentCache fast-path stress failed", failure.get());
        }

        cache.ageOut();
        Cacheable afterAgeOut = cache.find(key);
        if (afterAgeOut == null) {
            throw new AssertionError("container could not be found after age-out");
        }
        cache.remove(afterAgeOut);

        Cacheable recreated = cache.find(key);
        if (recreated == null || !key.equals(recreated.getIdentity())) {
            throw new AssertionError("container could not be recreated after remove");
        }
        cache.release(recreated);
    }

    private static CacheEntry stableEntry(Cacheable item) {
        CacheEntry entry = new CacheEntry();
        entry.lock();
        try {
            entry.settingIdentityComplete();
            entry.setCacheable(item);
        } finally {
            entry.unlock();
        }
        return entry;
    }

    private static final class TestCacheableFactory implements CacheableFactory {
        public Cacheable newCacheable(CacheManager cacheManager) {
            return new TestCacheable(null);
        }
    }

    private static final class TestCacheable implements Cacheable {
        private volatile Object identity;

        TestCacheable(Object identity) {
            this.identity = identity;
        }

        public Cacheable setIdentity(Object key) {
            identity = key;
            return this;
        }

        public Cacheable createIdentity(Object key, Object createParameter) {
            identity = key;
            return this;
        }

        public void clearIdentity() {
            identity = null;
        }

        public Object getIdentity() {
            return identity;
        }

        public boolean isDirty() {
            return false;
        }

        public void clean(boolean forRemove) throws StandardException {
        }
    }
}
