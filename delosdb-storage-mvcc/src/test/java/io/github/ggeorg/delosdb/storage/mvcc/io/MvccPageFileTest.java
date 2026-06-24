package io.github.ggeorg.delosdb.storage.mvcc.io;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume;

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
 * Compatibility page-file proof now backed by the storage I/O FileChannel page volume.
 */
public final class MvccPageFileTest {
    @TempDir
    private Path directory;

    @Test
    public void testWriteReadAndReopenPageRecords() throws Exception {
        Path file = directory.resolve("table-1.mvccp");
        try (DelosPageVolume pageFile = FileChannelPageVolume.open(file)) {
            DelosPage page = pageFile.allocatePage(DelosPage.DATA_PAGE_TYPE);
            int alpha = page.appendRecord("alpha".getBytes(StandardCharsets.UTF_8));
            int beta = page.appendRecord("beta".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
            pageFile.force();

            assertEquals(1L, pageFile.pageCount());
            assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8), pageFile.readPage(new DelosPageId(0)).readRecord(alpha));
            assertArrayEquals("beta".getBytes(StandardCharsets.UTF_8), pageFile.readPage(new DelosPageId(0)).readRecord(beta));
        }

        try (DelosPageVolume reopened = FileChannelPageVolume.open(file)) {
            DelosPage page = reopened.readPage(new DelosPageId(0));
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

        try (DelosPageVolume pageFile = FileChannelPageVolume.open(file)) {
            DelosPage first = pageFile.allocatePage(DelosPage.DATA_PAGE_TYPE);
            DelosPage second = pageFile.allocatePage(DelosPage.DATA_PAGE_TYPE);
            first.appendRecord("first".getBytes(StandardCharsets.UTF_8));
            second.appendRecord(largePayload);
            pageFile.writePage(first);
            pageFile.writePage(second);
            pageFile.force();
            assertEquals(2L, pageFile.pageCount());
        }

        try (DelosPageVolume reopened = FileChannelPageVolume.open(file)) {
            assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), reopened.readPage(new DelosPageId(0)).readRecord(0));
            assertArrayEquals(largePayload, reopened.readPage(new DelosPageId(1)).readRecord(0));
        }
    }

    @Test
    public void testRejectsBadMagic() throws Exception {
        Path file = directory.resolve("bad-magic.mvccp");
        try (DelosPageVolume pageFile = FileChannelPageVolume.open(file)) {
            DelosPage page = pageFile.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("ok".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
        }

        byte[] bytes = Files.readAllBytes(file);
        bytes[0] = 0x12;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (DelosPageVolume reopened = FileChannelPageVolume.open(file)) {
            assertThrows(IllegalArgumentException.class, () -> reopened.readPage(new DelosPageId(0)));
        }
    }

    @Test
    public void testRejectsUnsupportedVersion() throws Exception {
        Path file = directory.resolve("bad-version.mvccp");
        try (DelosPageVolume pageFile = FileChannelPageVolume.open(file)) {
            DelosPage page = pageFile.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("ok".getBytes(StandardCharsets.UTF_8));
            pageFile.writePage(page);
        }

        byte[] bytes = Files.readAllBytes(file);
        bytes[5] = 99;
        Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        try (DelosPageVolume reopened = FileChannelPageVolume.open(file)) {
            assertThrows(IllegalArgumentException.class, () -> reopened.readPage(new DelosPageId(0)));
        }
    }

    @Test
    public void testRejectsOversizedRecord() {
        DelosPage page = DelosPage.empty(new DelosPageId(0));
        byte[] tooLarge = new byte[DelosPage.PAGE_SIZE];
        assertThrows(IllegalStateException.class, () -> page.appendRecord(tooLarge));
    }

    @Test
    public void testRejectsTornFileLength() throws Exception {
        Path file = directory.resolve("torn.mvccp");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IllegalStateException.class, () -> FileChannelPageVolume.open(file));
    }
}
