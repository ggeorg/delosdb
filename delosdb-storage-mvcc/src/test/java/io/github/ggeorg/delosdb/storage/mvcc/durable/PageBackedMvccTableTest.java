package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccPageRecordCodec;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

final class PageBackedMvccTableTest {
    @TempDir
    Path tempDir;

    @Test
    void committedInsertSurvivesReopen() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals(1, table.logicalRowCount());
            assertEquals(1, table.physicalVersionCount("account:1"));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals(1, reopened.logicalRowCount());
            assertEquals(1, reopened.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedInsertUsesMvccPageRecordHeaderAndSurvivesReopen() throws Exception {
        Path tableFile = tempDir.resolve("record-header-table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.validateConsistency().assertValid();
        }

        byte[] bytes = Files.readAllBytes(tableFile);
        int pageRecordMagicOffset = indexOf(bytes, magicBytes(MvccPageRecordCodec.MAGIC));
        int versionRecordMagicOffset = indexOf(bytes, magicBytes(MvccVersionRecordCodec.MAGIC));
        assertTrue(pageRecordMagicOffset >= 0, "MVCC page slot should carry a page-record header");
        assertTrue(versionRecordMagicOffset > pageRecordMagicOffset,
                "legacy version-record bytes should be wrapped after the page-record header");

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            reopened.validateConsistency().assertValid();
        }
    }


    @Test
    void pageRecordStatsAccountForWrappedVersionSlotsAcrossVacuumAndReopen() throws Exception {
        Path tableFile = tempDir.resolve("record-stats-table.mvccp");
        PageBackedMvccTableStore.PageRecordStats statsAfterVacuum;
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.updateCommitted("account:1", "beta", 2L, 2L);
            table.insertCommitted("account:2", "gamma", 3L, 3L);
            table.deleteCommitted("account:2", 4L, 4L);

            PageBackedMvccTableStore.PageRecordStats beforeVacuum = table.pageRecordStats();
            assertEquals(table.physicalVersionCount(), beforeVacuum.slotCount());
            assertEquals(beforeVacuum.slotCount(), beforeVacuum.wrappedRecordCount());
            assertEquals(0, beforeVacuum.legacyRecordCount());
            assertEquals(beforeVacuum.slotCount(), beforeVacuum.versionRecordCount());
            assertEquals(0, beforeVacuum.nonVersionRecordCount());
            assertTrue(beforeVacuum.containsOnlyWrappedVersionRecords());

            table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            statsAfterVacuum = table.pageRecordStats();
            assertEquals(table.physicalVersionCount(), statsAfterVacuum.slotCount());
            assertEquals(statsAfterVacuum.slotCount(), statsAfterVacuum.wrappedRecordCount());
            assertEquals(0, statsAfterVacuum.legacyRecordCount());
            assertEquals(statsAfterVacuum.slotCount(), statsAfterVacuum.versionRecordCount());
            assertEquals(0, statsAfterVacuum.nonVersionRecordCount());
            assertTrue(statsAfterVacuum.containsOnlyWrappedVersionRecords());
            table.validateConsistency().assertValid();
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(statsAfterVacuum, reopened.pageRecordStats());
            assertEquals("beta", reopened.read("account:1", new MvccCommitSequence(4L)).orElseThrow());
            assertFalse(reopened.read("account:2", new MvccCommitSequence(4L)).isPresent());
            reopened.validateConsistency().assertValid();
        }
    }

    @Test
    void uncommittedInsertIsInvisibleAfterReopen() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertUncommitted("account:1", "alpha", 1L);
            assertFalse(table.read("account:1", new MvccCommitSequence(100L)).isPresent());
            assertEquals(1, table.physicalVersionCount("account:1"));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertFalse(reopened.read("account:1", new MvccCommitSequence(100L)).isPresent());
            assertEquals(1, reopened.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedUpdateSurvivesReopenAndKeepsOldVersionPhysically() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.updateCommitted("account:1", "beta", 2L, 2L);
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", table.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
            assertEquals(2, table.physicalVersionCount("account:1"));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals("beta", reopened.read("account:1", new MvccCommitSequence(2L)).orElseThrow());
            assertEquals(2, reopened.physicalVersionCount("account:1"));
        }
    }

    @Test
    void committedDeleteSurvivesReopen() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.deleteCommitted("account:1", 2L, 2L);
            assertEquals("alpha", table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(table.read("account:1", new MvccCommitSequence(2L)).isPresent());
            assertEquals(2, table.physicalVersionCount("account:1"));
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals("alpha", reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(reopened.read("account:1", new MvccCommitSequence(2L)).isPresent());
            assertEquals(2, reopened.physicalVersionCount("account:1"));
        }
    }

    @Test
    void appendsAcrossMultiplePagesAndSurvivesReopen() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        String largeValue = "x".repeat(700);
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            for (int index = 1; index <= 80; index++) {
                table.insertCommitted("row:" + index, largeValue + index, index, index);
            }
            assertEquals(80, table.logicalRowCount());
            if (table.pageCount() < 2L) {
                throw new AssertionError("expected multiple durable pages");
            }
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(80, reopened.logicalRowCount());
            assertEquals(largeValue + 1, reopened.read("row:1", new MvccCommitSequence(80L)).orElseThrow());
            assertEquals(largeValue + 80, reopened.read("row:80", new MvccCommitSequence(80L)).orElseThrow());
        }
    }



    @Test
    void pageCacheTracksReadsWritesAndConsistencyHits() throws Exception {
        Path tableFile = tempDir.resolve("cache-table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
            table.insertCommitted("account:2", "beta", 2L, 2L);
            assertTrue(table.pageCacheSize() > 0L, "append path should populate the page cache");
            assertTrue(table.pageCacheWriteCount() > 0L, "append path should publish page writes to the cache");
            assertTrue(table.pageCacheHitCount() > 0L, "second append should read the cached last page");

            long hitsBeforeConsistency = table.pageCacheHitCount();
            table.validateConsistency().assertValid();
            assertTrue(table.pageCacheHitCount() > hitsBeforeConsistency,
                    "consistency check should read pages through the cache boundary");
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertTrue(reopened.pageCacheMissCount() > 0L,
                    "reopen should hydrate durable pages through cache misses");
            long hitsBeforeConsistency = reopened.pageCacheHitCount();
            reopened.validateConsistency().assertValid();
            assertTrue(reopened.pageCacheHitCount() > hitsBeforeConsistency,
                    "reopened consistency check should hit the hydrated page cache");
        }
    }

    @Test
    void committedLargePayloadUsesOverflowPagesAndSurvivesVacuumAndReopen() throws Exception {
        Path tableFile = tempDir.resolve("overflow-table.mvccp");
        String largeValue = "x".repeat(16_000);
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", largeValue, 1L, 1L);
            assertEquals(largeValue, table.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            if (table.overflowPageCount() == 0L) {
                throw new AssertionError("expected large MVCC payload to allocate overflow pages");
            }
            table.validateConsistency().assertValid();
        }

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(largeValue, reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertEquals(1, reopened.physicalVersionCount("account:1"));
            assertEquals(1, reopened.logicalRowCount());
            reopened.validateConsistency().assertValid();
            reopened.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            assertEquals(largeValue, reopened.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            assertFalse(Files.notExists(PageBackedMvccTable.overflowPath(tableFile)));
        }

        try (PageBackedMvccTable reopenedAgain = PageBackedMvccTable.open(tableFile)) {
            assertEquals(largeValue, reopenedAgain.read("account:1", new MvccCommitSequence(1L)).orElseThrow());
            reopenedAgain.validateConsistency().assertValid();
        }
    }


    @Test
    void vacuumPersistsReusablePageIndexAndReconcilesMissingEntriesOnReopen() throws Exception {
        Path tableFile = tempDir.resolve("free-index-table.mvccp");
        String largeValue = "x".repeat(2400);
        long pageCountAfterVacuum;
        long reusablePagesAfterVacuum;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", largeValue, 1L, 1L);
            table.insertCommitted("account:2", largeValue, 1L, 1L);
            for (int round = 2; round <= 7; round++) {
                table.updateCommitted("account:1", largeValue + round, round, round);
                table.updateCommitted("account:2", largeValue + round, round, round);
            }
            long pageCountBeforeVacuum = table.pageCount();
            assertTrue(pageCountBeforeVacuum > 1L, "updates should create multiple MVCC pages");

            table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            pageCountAfterVacuum = table.pageCount();
            reusablePagesAfterVacuum = table.reusablePageCount();
            assertEquals(pageCountBeforeVacuum, pageCountAfterVacuum);
            assertTrue(reusablePagesAfterVacuum > 0L, "vacuum should mark whole pages reusable");
            assertTrue(Files.exists(PageBackedMvccTable.reusablePageIndexPath(tableFile)),
                    "vacuum should persist the reusable-page sidecar index");
        }

        MvccReusablePageIndexStore.open(PageBackedMvccTable.reusablePageIndexPath(tableFile))
                .rewrite(pageCountAfterVacuum, new TreeSet<>());

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(reusablePagesAfterVacuum, reopened.reusablePageCount(),
                    "open should reconcile an incomplete reusable-page index from empty page images");
            assertEquals(largeValue + 7, reopened.read("account:1", new MvccCommitSequence(7L)).orElseThrow());
            assertEquals(largeValue + 7, reopened.read("account:2", new MvccCommitSequence(7L)).orElseThrow());
            reopened.validateConsistency().assertValid();
        }

        MvccReusablePageIndexStore.Snapshot reconciled = MvccReusablePageIndexStore.open(
                PageBackedMvccTable.reusablePageIndexPath(tableFile)).read();
        assertEquals(pageCountAfterVacuum, reconciled.pageCount());
        assertEquals(reusablePagesAfterVacuum, reconciled.reusablePageIds().size());
    }


    @Test
    void corruptReusablePageIndexIsRebuiltFromEmptyPageImagesOnReopen() throws Exception {
        Path tableFile = tempDir.resolve("corrupt-free-index-table.mvccp");
        Path reusablePageIndexFile = PageBackedMvccTable.reusablePageIndexPath(tableFile);
        String largeValue = "x".repeat(2400);
        long pageCountAfterVacuum;
        long reusablePagesAfterVacuum;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", largeValue, 1L, 1L);
            table.insertCommitted("account:2", largeValue, 1L, 1L);
            for (int round = 2; round <= 7; round++) {
                table.updateCommitted("account:1", largeValue + round, round, round);
                table.updateCommitted("account:2", largeValue + round, round, round);
            }

            table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            pageCountAfterVacuum = table.pageCount();
            reusablePagesAfterVacuum = table.reusablePageCount();
            assertTrue(reusablePagesAfterVacuum > 0L, "vacuum should mark whole pages reusable");
        }

        corruptLastByte(reusablePageIndexFile);

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(pageCountAfterVacuum, reopened.pageCount());
            assertEquals(reusablePagesAfterVacuum, reopened.reusablePageCount(),
                    "open should rebuild a corrupt reusable-page index from empty page images");
            assertEquals(largeValue + 7, reopened.read("account:1", new MvccCommitSequence(7L)).orElseThrow());
            assertEquals(largeValue + 7, reopened.read("account:2", new MvccCommitSequence(7L)).orElseThrow());
            reopened.validateConsistency().assertValid();
        }

        MvccReusablePageIndexStore.Snapshot repaired = MvccReusablePageIndexStore.open(reusablePageIndexFile).read();
        assertEquals(pageCountAfterVacuum, repaired.pageCount());
        assertEquals(reusablePagesAfterVacuum, repaired.reusablePageIds().size());
    }

    @Test
    void staleReusablePageIndexDropsConsumedPagesOnReopen() throws Exception {
        Path tableFile = tempDir.resolve("stale-free-index-table.mvccp");
        Path reusablePageIndexFile = PageBackedMvccTable.reusablePageIndexPath(tableFile);
        String largeValue = "x".repeat(2400);
        long pageCountAfterReuse;
        long consumedPageId;
        TreeSet<Long> reusablePagesAfterReuse;

        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", largeValue, 1L, 1L);
            table.insertCommitted("account:2", largeValue, 1L, 1L);
            for (int round = 2; round <= 7; round++) {
                table.updateCommitted("account:1", largeValue + round, round, round);
                table.updateCommitted("account:2", largeValue + round, round, round);
            }

            table.vacuum(MvccVacuumPlan.through(Long.MAX_VALUE));
            MvccReusablePageIndexStore.Snapshot afterVacuum = MvccReusablePageIndexStore.open(
                    reusablePageIndexFile).read();
            assertTrue(!afterVacuum.reusablePageIds().isEmpty(), "vacuum should create reusable pages");
            MvccIndexTuple inserted = table.insertCommitted("account:3", "small", 8L, 8L);
            consumedPageId = inserted.versionLocator().pageId().value();
            pageCountAfterReuse = table.pageCount();
            MvccReusablePageIndexStore.Snapshot afterReuse = MvccReusablePageIndexStore.open(
                    reusablePageIndexFile).read();
            reusablePagesAfterReuse = new TreeSet<>(afterReuse.reusablePageIds());
            assertFalse(reusablePagesAfterReuse.contains(consumedPageId),
                    "page containing the inserted record must not be listed in the allocation index");
            table.validateConsistency().assertValid();
        }

        TreeSet<Long> staleReusablePages = new TreeSet<>(reusablePagesAfterReuse);
        staleReusablePages.add(consumedPageId);
        MvccReusablePageIndexStore.open(reusablePageIndexFile).rewrite(pageCountAfterReuse, staleReusablePages);

        try (PageBackedMvccTable reopened = PageBackedMvccTable.open(tableFile)) {
            assertEquals(pageCountAfterReuse, reopened.pageCount());
            assertEquals(reusablePagesAfterReuse.size(), reopened.reusablePageCount(),
                    "open should discard valid-but-stale reusable-page entries for consumed pages");
            assertEquals("small", reopened.read("account:3", new MvccCommitSequence(8L)).orElseThrow());
            reopened.validateConsistency().assertValid();
        }

        MvccReusablePageIndexStore.Snapshot repaired = MvccReusablePageIndexStore.open(reusablePageIndexFile).read();
        assertFalse(repaired.reusablePageIds().contains(consumedPageId),
                "repaired allocation index must not list a page containing live data");
        assertEquals(reusablePagesAfterReuse, repaired.reusablePageIds());
    }

    @Test
    void corruptPayloadIsRejectedOnOpen() throws Exception {
        Path tableFile = tempDir.resolve("table.mvccp");
        try (PageBackedMvccTable table = PageBackedMvccTable.open(tableFile)) {
            table.insertCommitted("account:1", "alpha", 1L, 1L);
        }
        byte[] bytes = Files.readAllBytes(tableFile);
        int recordMagicOffset = indexOf(bytes, new byte[] {0x44, 0x4d, 0x56, 0x52});
        if (recordMagicOffset < 0) {
            throw new AssertionError("could not find MVCC version-record magic in page file");
        }
        bytes[recordMagicOffset] = 0x00;
        Files.write(tableFile, bytes);

        assertThrows(IllegalArgumentException.class, () -> PageBackedMvccTable.open(tableFile));
    }


    private static void corruptLastByte(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) {
            throw new AssertionError("cannot corrupt empty file: " + path);
        }
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(path, bytes);
    }

    private static byte[] magicBytes(int magic) {
        return new byte[] {
                (byte) ((magic >>> 24) & 0xff),
                (byte) ((magic >>> 16) & 0xff),
                (byte) ((magic >>> 8) & 0xff),
                (byte) (magic & 0xff)
        };
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return offset;
            }
        }
        return -1;
    }
}
