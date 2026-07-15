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

/**
 * Phase P long-reader validation: a held pin must survive buffer pressure.
 * The full SQL long-reader-vs-vacuum soak remains an external validation task;
 * this built-in harness keeps the low-level pin invariant deterministic.
 */
final class MvccLongReaderBufferPressureValidationTest {
    @Test
    void longReaderPinSurvivesBufferPressureUntilClosed() throws Exception {
        CountingPageVolume volume = new CountingPageVolume();
        for (int pageId = 0; pageId < 32; pageId++) {
            volume.writePage(dataPage(pageId, (byte) pageId));
        }

        MvccPageCache cache = new MvccPageCache(2);
        try (MvccPageCache.PinnedPage longReader = cache.readPinned(volume, new DelosPageId(0L))) {
            for (int pageId = 1; pageId < 32; pageId++) {
                DelosPageId candidate = new DelosPageId(pageId);
                cache.read(volume, candidate);
                // The first cold read is intentionally bypassed. The second
                // touch forces admission and therefore exercises replacement
                // while the long-reader page remains pinned.
                cache.read(volume, candidate);
            }
            MvccPageCache.Snapshot underPressure = cache.snapshot();
            assertEquals(1L, underPressure.pinnedPages());
            assertTrue(underPressure.pinnedEvictionSkips() > 0L);
            assertEquals(0L, longReader.page().pageId().value());
        }

        MvccPageCache.Snapshot afterReaderCloses = cache.snapshot();
        assertEquals(0L, afterReaderCloses.pinnedPages());
        assertTrue(afterReaderCloses.size() <= 2L);
    }

    private static DelosPage dataPage(long pageId, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page;
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();

        @Override
        public DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing validation page " + id.value());
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
