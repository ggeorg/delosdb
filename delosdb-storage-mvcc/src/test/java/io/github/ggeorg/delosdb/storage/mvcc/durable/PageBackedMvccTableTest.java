package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;

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
