package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;

final class MvccPageCachePinDirtyTest {
    @TempDir
    Path tempDir;

    @Test
    void pinnedPagesAreNotEvictedUntilUnpinned() throws Exception {
        Path file = tempDir.resolve("pin-eviction.mvccp");
        try (DelosPageVolume volume = DelosPageVolumeFactories.fileChannel().open(file)) {
            DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page0.appendRecord(new byte[] {1});
            volume.writePage(page0);
            DelosPage page1 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page1.appendRecord(new byte[] {2});
            volume.writePage(page1);
            volume.force();

            MvccPageCache cache = new MvccPageCache(1);
            try (MvccPageCache.PinnedPage ignored0 = cache.readPinned(volume, new DelosPageId(0L))) {
                try (MvccPageCache.PinnedPage ignored1 = cache.readPinned(volume, new DelosPageId(1L))) {
                    MvccPageCache.Snapshot snapshot = cache.snapshot();
                    assertEquals(2L, snapshot.size());
                    assertEquals(2L, snapshot.pinnedPages());
                    assertEquals(0L, snapshot.evictions());
                    assertTrue(snapshot.pinnedEvictionSkips() > 0L);
                }
                MvccPageCache.Snapshot afterInnerUnpin = cache.snapshot();
                assertEquals(1L, afterInnerUnpin.size());
                assertEquals(1L, afterInnerUnpin.pinnedPages());
                assertEquals(1L, afterInnerUnpin.evictions());
            }

            MvccPageCache.Snapshot afterUnpin = cache.snapshot();
            assertEquals(1L, afterUnpin.size());
            assertEquals(0L, afterUnpin.pinnedPages());
            assertEquals(1L, afterUnpin.evictions());
            assertEquals(2L, afterUnpin.pins());
            assertEquals(2L, afterUnpin.unpins());
        }
    }

    @Test
    void dirtyPagesAreTrackedUntilFlushed() throws Exception {
        Path file = tempDir.resolve("dirty-flush.mvccp");
        try (DelosPageVolume volume = DelosPageVolumeFactories.fileChannel().open(file)) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord(new byte[] {7});
            volume.writePage(page);
            volume.force();

            MvccPageCache cache = new MvccPageCache(2);
            page.appendRecord(new byte[] {8});
            cache.putDirty(page);

            MvccPageCache.Snapshot dirty = cache.snapshot();
            assertEquals(1L, dirty.dirtyPages());
            assertEquals(1L, dirty.flushListPages());
            assertEquals(0L, dirty.flushes());
            assertTrue(dirty.lastPageGeneration() > 0L);

            cache.flush(volume, page.pageId());
            MvccPageCache.Snapshot flushed = cache.snapshot();
            assertEquals(0L, flushed.dirtyPages());
            assertEquals(0L, flushed.flushListPages());
            assertEquals(1L, flushed.flushes());
        }
    }
}
