package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

final class MvccBufferManagerPhase2Test {
    @Test
    void flushRequiresAnExplicitWalCoordinator() {
        MvccPageCache cache = new MvccPageCache(4);
        CountingPageVolume volume = new CountingPageVolume();

        assertThrows(NullPointerException.class, () -> cache.flushAll(volume, null));
    }

    @Test
    void walBeforeFlushRejectsDirtyPageUntilCoveringLogRecordIsForced() throws Exception {
        MvccPageCache cache = new MvccPageCache(4);
        CountingPageVolume volume = new CountingPageVolume();
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        DelosPage page = dataPage(0L, 7L, (byte) 1);
        cache.putDirty(page);

        IllegalStateException violation = assertThrows(
                IllegalStateException.class,
                () -> cache.flushAll(volume, coordinator));
        org.junit.jupiter.api.Assertions.assertTrue(violation.getMessage().contains("WAL-before-flush violation"));
        assertEquals(0L, volume.writeCount);
        assertEquals(0L, volume.forceCount);
        assertEquals(1L, cache.snapshot().dirtyPages());
        assertEquals(1L, coordinator.snapshot().walBeforeFlushFailures());

        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(7L));
        assertEquals(1L, cache.flushAll(volume, coordinator));

        assertEquals(1L, volume.writeCount);
        assertEquals(1L, volume.forceCount);
        assertEquals(0L, cache.snapshot().dirtyPages());
        assertEquals(2L, coordinator.snapshot().walBeforeFlushChecks());
    }

    @Test
    void groupCommitForcesOnceForMultipleDirtyPagesInTheSameFlushBatch() throws Exception {
        MvccPageCache cache = new MvccPageCache(4);
        CountingPageVolume volume = new CountingPageVolume();
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        cache.putDirty(dataPage(0L, 2L, (byte) 2));
        cache.putDirty(dataPage(1L, 3L, (byte) 3));
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(3L));

        assertEquals(2L, cache.flushAll(volume, coordinator));

        MvccBufferFlushCoordinator.Snapshot flushSnapshot = coordinator.snapshot();
        MvccPageCache.Snapshot cacheSnapshot = cache.snapshot();
        assertEquals(2L, volume.writeCount);
        assertEquals(1L, volume.forceCount);
        assertEquals(1L, flushSnapshot.pageFlushBatches());
        assertEquals(2L, flushSnapshot.pageFlushPages());
        assertEquals(1L, cacheSnapshot.groupedForceBatches());
        assertEquals(2L, cacheSnapshot.groupedForcedPages());
        assertEquals(0L, cacheSnapshot.dirtyPages());
    }

    @Test
    void midBatchWriteFailureKeepsEveryPageDirtyForACompleteRetry() throws Exception {
        MvccPageCache cache = new MvccPageCache(4);
        CountingPageVolume volume = new CountingPageVolume();
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        cache.putDirty(dataPage(0L, 2L, (byte) 2));
        cache.putDirty(dataPage(1L, 3L, (byte) 3));
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(3L));
        volume.failWriteAttempt = 2L;

        assertThrows(IOException.class, () -> cache.flushAll(volume, coordinator));

        assertEquals(2L, cache.snapshot().dirtyPages(),
                "a partial page-volume write must not clear any member of the dirty batch");
        assertEquals(0L, volume.forceCount);

        volume.failWriteAttempt = -1L;
        assertEquals(2L, cache.flushAll(volume, coordinator));
        assertEquals(0L, cache.snapshot().dirtyPages());
        assertEquals(1L, volume.forceCount);
    }

    @Test
    void groupedForceFailureKeepsEveryPageDirtyForACompleteRetry() throws Exception {
        MvccPageCache cache = new MvccPageCache(4);
        CountingPageVolume volume = new CountingPageVolume();
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        cache.putDirty(dataPage(0L, 2L, (byte) 2));
        cache.putDirty(dataPage(1L, 3L, (byte) 3));
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(3L));
        volume.failForce = true;

        assertThrows(IOException.class, () -> cache.flushAll(volume, coordinator));

        assertEquals(2L, cache.snapshot().dirtyPages(),
                "pages are not clean until the grouped force boundary succeeds");
        assertEquals(0L, coordinator.snapshot().pageFlushBatches());

        volume.failForce = false;
        assertEquals(2L, cache.flushAll(volume, coordinator));
        assertEquals(0L, cache.snapshot().dirtyPages());
        assertEquals(1L, coordinator.snapshot().pageFlushBatches());
        assertEquals(2L, coordinator.snapshot().pageFlushPages());
    }

    private static DelosPage dataPage(long pageId, long pageLsn, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page.withPageLsn(pageLsn);
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();
        private long writeCount;
        private long writeAttempts;
        private long forceCount;
        private long failWriteAttempt = -1L;
        private boolean failForce;

        @Override
        public DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing test page " + id.value());
            }
            return page;
        }

        @Override
        public void writePage(DelosPage page) throws IOException {
            writeAttempts++;
            if (writeAttempts == failWriteAttempt) {
                throw new IOException("injected page write failure");
            }
            pages.put(page.pageId().value(), page);
            writeCount++;
        }

        @Override
        public DelosPage allocatePage(int pageType) {
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.put(page.pageId().value(), page);
            return page;
        }

        @Override
        public long pageCount() {
            return pages.size();
        }

        @Override
        public void force() throws IOException {
            if (failForce) {
                throw new IOException("injected page force failure");
            }
            forceCount++;
        }

        @Override
        public SyncPolicy syncPolicy() {
            return SyncPolicy.NONE;
        }

        @Override
        public void close() throws IOException {
            pages.clear();
        }
    }
}
