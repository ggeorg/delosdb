package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

/**
 * Phase P built-in concurrency validation harness for the MVCC buffer/cache
 * structures that are too low-level for SQL-only tests.
 */
final class MvccConcurrencyValidationTest {
    @Test
    void concurrentPinUnpinStressKeepsCountersBalanced() throws Exception {
        int pageCount = 16;
        CountingPageVolume volume = new CountingPageVolume();
        for (int pageId = 0; pageId < pageCount; pageId++) {
            volume.writePage(dataPage(pageId, 0L, (byte) pageId));
        }

        MvccPageCache cache = new MvccPageCache(8);
        int workers = 6;
        int iterations = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        Future<?>[] futures = new Future<?>[workers];
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            futures[worker] = executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        long pageId = (workerId + i) % pageCount;
                        try (MvccPageCache.PinnedPage pinned = cache.readPinned(volume, new DelosPageId(pageId))) {
                            assertEquals(pageId, pinned.page().pageId().value());
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        if (failure.get() != null) {
            throw new AssertionError("concurrent pin/unpin validation failed", failure.get());
        }
        MvccPageCache.Snapshot snapshot = cache.snapshot();
        assertEquals(workers * iterations, snapshot.pins());
        assertEquals(snapshot.pins(), snapshot.unpins());
        assertEquals(0L, snapshot.pinnedPages());
        assertTrue(snapshot.pinnedEvictionSkips() > 0L,
                "bounded cache should encounter pinned/dirty skip pressure under concurrent readers");
    }

    @Test
    void concurrentDirtyWritersStillFlushUnderWalBeforeFlushDiscipline() throws Exception {
        int workers = 4;
        int pagesPerWorker = 32;
        MvccPageCache cache = new MvccPageCache(256);
        CountingPageVolume volume = new CountingPageVolume();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Future<?>[] futures = new Future<?>[workers];
        for (int worker = 0; worker < workers; worker++) {
            final int workerId = worker;
            futures[worker] = executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < pagesPerWorker; i++) {
                        long pageId = (long) workerId * pagesPerWorker + i;
                        cache.putDirty(dataPage(pageId, pageId + 1L, (byte) pageId));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();
        if (failure.get() != null) {
            throw new AssertionError("concurrent dirty writer validation failed", failure.get());
        }

        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(workers * pagesPerWorker));
        assertEquals(workers * pagesPerWorker, cache.flushAll(volume, coordinator));
        assertEquals(0L, cache.snapshot().dirtyPages());
        assertEquals(0L, cache.snapshot().walBeforeFlushFailures());
        assertEquals(1L, coordinator.snapshot().groupCommitBatches());
    }

    private static DelosPage dataPage(long pageId, long pageLsn, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page.withPageLsn(pageLsn);
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();

        @Override
        public synchronized DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing validation page " + id.value());
            }
            return page;
        }

        @Override
        public synchronized void writePage(DelosPage page) {
            pages.put(page.pageId().value(), page);
        }

        @Override
        public synchronized DelosPage allocatePage(int pageType) {
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.put(page.pageId().value(), page);
            return page;
        }

        @Override
        public synchronized long pageCount() {
            return pages.size();
        }

        @Override
        public void force() {
        }

        @Override
        public SyncPolicy syncPolicy() {
            return SyncPolicy.NONE;
        }

        @Override
        public synchronized void close() throws IOException {
            pages.clear();
        }
    }
}
