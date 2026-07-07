package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;

final class MvccBufferReplacementPolicyTest {
    @Test
    void leastRecentlyUsedCleanPageIsEvictedFirst() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page1 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page2 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        MvccPageCache cache = new MvccPageCache(2);
        cache.putClean(page0);
        cache.putClean(page1);

        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page0.pageId())) {
            assertEquals(1L, cache.snapshot().hits());
        }

        cache.putClean(page2);
        MvccPageCache.Snapshot afterEviction = cache.snapshot();
        assertEquals(2L, afterEviction.size());
        assertEquals(1L, afterEviction.evictions());
        assertTrue(afterEviction.replacementScans() >= 1L);

        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page0.pageId())) {
            assertEquals(2L, cache.snapshot().hits());
        }
        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page1.pageId())) {
            assertEquals(1L, cache.snapshot().misses());
        }
    }

    @Test
    void dirtyPagesAreProtectedUntilFlushMakesThemEvictable() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage dirty = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage clean = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        MvccPageCache cache = new MvccPageCache(1);
        cache.putDirty(dirty);
        cache.putClean(clean);

        MvccPageCache.Snapshot protectedDirty = cache.snapshot();
        assertEquals(1L, protectedDirty.size());
        assertEquals(1L, protectedDirty.dirtyPages());
        assertEquals(1L, protectedDirty.evictions());
        assertTrue(protectedDirty.replacementDirtyProtectionSkips() > 0L);

        cache.flushAll(volume);
        cache.putClean(clean);
        MvccPageCache.Snapshot afterFlush = cache.snapshot();
        assertEquals(1L, afterFlush.size());
        assertEquals(0L, afterFlush.dirtyPages());
        assertTrue(afterFlush.evictions() >= 2L);
    }

    @Test
    void noVictimIsReportedWhenEveryPageIsProtected() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page1 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        MvccPageCache cache = new MvccPageCache(1);
        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page0.pageId())) {
            cache.putDirty(page1);
            MvccPageCache.Snapshot snapshot = cache.snapshot();
            assertEquals(2L, snapshot.size());
            assertEquals(1L, snapshot.pinnedPages());
            assertEquals(1L, snapshot.dirtyPages());
            assertTrue(snapshot.pinnedEvictionSkips() > 0L);
            assertTrue(snapshot.replacementDirtyProtectionSkips() > 0L);
            assertTrue(snapshot.replacementNoVictimCount() > 0L);
        }
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();

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
        }

        @Override
        public DelosPage allocatePage(int pageType) {
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            page.appendRecord(new byte[] {(byte) pages.size()});
            pages.put(page.pageId().value(), page);
            return page;
        }

        @Override
        public long pageCount() {
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
        public void close() throws IOException {
            pages.clear();
        }
    }
}
