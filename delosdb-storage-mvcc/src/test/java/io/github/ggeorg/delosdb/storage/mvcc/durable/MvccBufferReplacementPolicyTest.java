package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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


    @Test
    void defaultReplacementPolicyNameIsExposedForDiagnostics() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        MvccPageCache cache = new MvccPageCache(1);

        cache.putClean(page);

        assertEquals(
                "ACCESS_ORDER_LRU_SECOND_TOUCH_ADMISSION",
                cache.snapshot().replacementPolicyName());
    }

    @Test
    void firstColdReadAtCapacityIsBypassedAndSecondTouchIsAdmitted() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page1 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page2 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        MvccPageCache cache = new MvccPageCache(2);

        cache.read(volume, page0.pageId());
        cache.read(volume, page1.pageId());
        cache.read(volume, page2.pageId());

        MvccPageCache.Snapshot afterBypass = cache.snapshot();
        assertEquals(2L, afterBypass.size());
        assertEquals(1L, afterBypass.readAdmissionBypasses());
        assertEquals(0L, afterBypass.secondTouchAdmissions());
        assertEquals(1L, afterBypass.ghostHistoryPages());

        cache.read(volume, page0.pageId());
        cache.read(volume, page1.pageId());
        assertEquals(2L, cache.snapshot().hits());

        cache.read(volume, page2.pageId());
        MvccPageCache.Snapshot afterAdmission = cache.snapshot();
        assertEquals(2L, afterAdmission.size());
        assertEquals(1L, afterAdmission.secondTouchAdmissions());
        assertEquals(1L, afterAdmission.evictions());

        cache.read(volume, page2.pageId());
        assertEquals(3L, cache.snapshot().hits());
    }

    @Test
    void coldSequentialScanDoesNotEvictResidentPages() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        for (int page = 0; page < 40; page++) {
            volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        }
        MvccPageCache cache = new MvccPageCache(8);
        for (int page = 0; page < 2; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        for (int page = 2; page < 40; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        long hitsBefore = cache.snapshot().hits();
        long missesBefore = cache.snapshot().misses();

        cache.read(volume, new DelosPageId(0L));
        cache.read(volume, new DelosPageId(1L));

        MvccPageCache.Snapshot after = cache.snapshot();
        assertEquals(hitsBefore + 2L, after.hits());
        assertEquals(missesBefore, after.misses());
        assertTrue(after.readAdmissionBypasses() > 0L);
    }

    @Test
    void knownAllDirtyStateAvoidsRepeatedFullReplacementScans() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        MvccPageCache cache = new MvccPageCache(8);
        for (int page = 0; page < 16; page++) {
            DelosPage dirty = DelosPage.empty(new DelosPageId(page), DelosPage.DATA_PAGE_TYPE);
            dirty.appendRecord(new byte[] {(byte) page});
            cache.putDirty(dirty);
        }

        MvccPageCache.Snapshot pressured = cache.snapshot();
        assertEquals(16L, pressured.size());
        assertEquals(16L, pressured.dirtyPages());
        assertEquals(8L, pressured.replacementNoVictimCount());
        assertEquals(9L, pressured.replacementScans());

        cache.flushAll(volume);
        MvccPageCache.Snapshot flushed = cache.snapshot();
        assertEquals(8L, flushed.size());
        assertEquals(0L, flushed.dirtyPages());
    }

    @Test
    void replacementPolicyCanBeInjectedForProofsWithoutChangingDefaultPolicy() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page1 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        DelosPage page2 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        NewestCleanPagePolicy newestCleanPagePolicy = new NewestCleanPagePolicy();
        MvccPageCache cache = new MvccPageCache(2, newestCleanPagePolicy);

        cache.putClean(page0);
        cache.putClean(page1);
        cache.putClean(page2);

        assertEquals("NEWEST_CLEAN_TEST_POLICY", cache.snapshot().replacementPolicyName());
        assertEquals(1L, newestCleanPagePolicy.invocations);

        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page0.pageId())) {
            assertEquals(1L, cache.snapshot().hits());
        }
        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page1.pageId())) {
            assertEquals(2L, cache.snapshot().hits());
        }
        try (MvccPageCache.PinnedPage ignored = cache.readPinned(volume, page2.pageId())) {
            assertEquals(1L, cache.snapshot().misses());
        }
    }

    @Test
    void replacementPolicyInjectionRejectsNullPolicy() {
        assertThrows(NullPointerException.class, () -> new MvccPageCache(1, null));
    }

    private static final class NewestCleanPagePolicy implements MvccBufferReplacementStrategy {
        private long invocations;

        @Override
        public String name() {
            return "NEWEST_CLEAN_TEST_POLICY";
        }

        @Override
        public MvccBufferReplacementPolicy.Decision chooseVictim(
                Map<Long, ? extends MvccBufferReplacementPolicy.PageState> pages) {
            invocations++;
            long scannedPages = 0L;
            long pinnedProtectedPages = 0L;
            long dirtyProtectedPages = 0L;
            Long newestCleanPage = null;
            for (Map.Entry<Long, ? extends MvccBufferReplacementPolicy.PageState> entry : pages.entrySet()) {
                scannedPages++;
                MvccBufferReplacementPolicy.PageState page = entry.getValue();
                if (page.pinCount() > 0) {
                    pinnedProtectedPages++;
                    continue;
                }
                if (page.dirty()) {
                    dirtyProtectedPages++;
                    continue;
                }
                newestCleanPage = entry.getKey();
            }
            if (newestCleanPage == null) {
                return MvccBufferReplacementPolicy.Decision.noVictim(
                        scannedPages,
                        pinnedProtectedPages,
                        dirtyProtectedPages);
            }
            return MvccBufferReplacementPolicy.Decision.victim(
                    newestCleanPage,
                    scannedPages,
                    pinnedProtectedPages,
                    dirtyProtectedPages);
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
