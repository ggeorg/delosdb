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
