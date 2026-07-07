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
        assertEquals(1L, flushSnapshot.groupCommitBatches());
        assertEquals(2L, flushSnapshot.groupedPageFlushes());
        assertEquals(1L, cacheSnapshot.groupedForceBatches());
        assertEquals(2L, cacheSnapshot.groupedForcedPages());
        assertEquals(0L, cacheSnapshot.dirtyPages());
    }

    private static DelosPage dataPage(long pageId, long pageLsn, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page.withPageLsn(pageLsn);
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();
        private long writeCount;
        private long forceCount;

        @Override
        public DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing test page " + id.value());
            }
            return page;
        }

        @Override
        public void writePage(DelosPage page) {
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
        public void force() {
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
