package io.github.ggeorg.delosdb.storage.mvcc.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase A1 durable page-file proof for the experimental MVCC storage engine.
 */
public final class MvccPageFileTest {
    @TempDir
    private Path directory;

    @Test
    public void testWriteReadAndReopenPageRecords() throws Exception {
        Path file = directory.resolve("table-1.mvccp");
        try (MvccPageFile pageFile = MvccPageFile.open(file)) {
            MvccPage page = pageFile.allocatePage();
            int alpha = page.appendRecord("alpha".getBytes(StandardCharsets.UTF_8));
            int beta = page.appendRecord("beta".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
            pageFile.force();

            assertEquals(1L, pageFile.pageCount());
            assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), pageFile.readPage(new MvccPageId(0)).readRecord(alpha));
            assertArrayEquals("beta".getBytes(StandardCharsets.UTF_8), pageFile.readPage(new MvccPageId(0)).readRecord(beta));
        }

        try (MvccPageFile reopened = MvccPageFile.open(file)) {
            MvccPage page = reopened.readPage(new MvccPageId(0));
            assertEquals(2, page.slotCount());
            assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), page.readRecord(0));
            assertArrayEquals("beta".getBytes(StandardCharsets.UTF_8), page.readRecord(1));
        }
    }

    @Test
    public void testMultiplePagesSurviveReopen() throws Exception {
        Path file = directory.resolve("multi-page.mvccp");
        byte[] largePayload = new byte[4_000];
        Arrays.fill(largePayload, (byte) 7);

        try (MvccPageFile pageFile = MvccPageFile.open(file)) {
            MvccPage first = pageFile.allocatePage();
            MvccPage second = pageFile.allocatePage();
            first.appendRecord("first".getBytes(StandardCharsets.UTF_8));
            second.appendRecord(largePayload);
            pageFile.writePage(first);
            pageFile.writePage(second);
            pageFile.force();
            assertEquals(2L, pageFile.pageCount());
        }

        try (MvccPageFile reopened = MvccPageFile.open(file)) {
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), reopened.readPage(new MvccPageId(0)).readRecord(0));
            assertArrayEquals(largePayload, reopened.readPage(new MvccPageId(1)).readRecord(0));
        }
    }

    @Test
    public void testRejectsBadMagic() throws Exception {
        Path file = directory.resolve("bad-magic.mvccp");
        try (MvccPageFile pageFile = MvccPageFile.open(file)) {
            MvccPage page = pageFile.allocatePage();
            page.appendRecord("ok".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
        }

        byte[] bytes = Files.readAllBytes(file);
        bytes[0] = 0x12;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (MvccPageFile reopened = MvccPageFile.open(file)) {
            assertThrows(IllegalArgumentException.class, () -> reopened.readPage(new MvccPageId(0)));
        }
    }

    @Test
    public void testRejectsUnsupportedVersion() throws Exception {
        Path file = directory.resolve("bad-version.mvccp");
        try (MvccPageFile pageFile = MvccPageFile.open(file)) {
            MvccPage page = pageFile.allocatePage();
            page.appendRecord("ok".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
        }

        byte[] bytes = Files.readAllBytes(file);
        bytes[5] = 99;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (MvccPageFile reopened = MvccPageFile.open(file)) {
            assertThrows(IllegalArgumentException.class, () -> reopened.readPage(new MvccPageId(0)));
        }
    }

    @Test
    public void testRejectsOversizedRecord() {
        MvccPage page = MvccPage.empty(new MvccPageId(0));
        byte[] tooLarge = new byte[MvccPage.PAGE_SIZE];
        assertThrows(IllegalStateException.class, () -> page.appendRecord(tooLarge));
    }

    @Test
    public void testRejectsTornFileLength() throws Exception {
        Path file = directory.resolve("torn.mvccp");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IllegalStateException.class, () -> MvccPageFile.open(file));
    }
}
