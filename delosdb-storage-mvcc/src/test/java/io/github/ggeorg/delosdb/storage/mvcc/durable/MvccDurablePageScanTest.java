package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;

final class MvccDurablePageScanTest {
    @TempDir
    Path tempDir;

    @Test
    void scanReportsSlotsAndEmptyPagesFromOneDurableWalk() throws Exception {
        Path file = tempDir.resolve("scan.mvccp");
        try (DelosPageVolume volume = DelosPageVolumeFactories.fileChannel().open(file)) {
            DelosPage page0 = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page0.appendRecord(new byte[] {1, 2, 3});
            volume.writePage(page0);
            volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            volume.force();
        }

        try (DelosPageVolume volume = DelosPageVolumeFactories.fileChannel().open(file)) {
            MvccDurablePageScan scan = MvccDurablePageScan.scan(new MvccDurablePageScan.PageSource() {
                @Override
                public long pageCount() throws java.io.IOException {
                    return volume.pageCount();
                }

                @Override
                public DelosPage readPage(DelosPageId pageId) throws java.io.IOException {
                    return volume.readPage(pageId);
                }
            });

            assertEquals(2L, scan.pageCount());
            assertEquals(1, scan.slotRecords().size());
            assertTrue(scan.emptyPageIds().contains(1L));
            assertArrayEquals(new byte[] {1, 2, 3}, scan.slotRecords().get(0).payload());
            assertEquals(new MvccVersionLocator(new DelosPageId(0L), 0), scan.slotRecords().get(0).locator());
        }
    }
}
