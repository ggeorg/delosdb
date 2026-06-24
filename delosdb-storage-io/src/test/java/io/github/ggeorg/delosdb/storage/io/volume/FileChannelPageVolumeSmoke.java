package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** S4 smoke for the file-backed DelosPageVolume boundary. */
public final class FileChannelPageVolumeSmoke {
    private FileChannelPageVolumeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("delosdb-s4-file-channel-page-volume-");
        try {
            Path path = dir.resolve("pages.dat");

            try (FileChannelPageVolume volume = FileChannelPageVolume.open(path, DelosPageVolume.SyncPolicy.FULL)) {
                require(volume.syncPolicy() == DelosPageVolume.SyncPolicy.FULL, "sync policy must be FULL");
                require(volume.pageCount() == 0L, "new volume must be empty");

                DelosPage first = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
                require(first.pageId().equals(new DelosPageId(0L)), "first page id must be zero");
                first.appendRecord("alpha".getBytes(StandardCharsets.UTF_8));
                volume.writePage(first);
                volume.force();

                DelosPage second = volume.allocatePage(7);
                require(second.pageId().equals(new DelosPageId(1L)), "second page id must be one");
                require(second.pageType() == 7, "second page type must round trip");
                second.appendRecord("beta".getBytes(StandardCharsets.UTF_8));
                volume.writePage(second);
                require(volume.pageCount() == 2L, "page count must include both allocated pages");
            }

            try (FileChannelPageVolume reopened = FileChannelPageVolume.open(path, DelosPageVolume.SyncPolicy.METADATA_ONLY)) {
                require(reopened.syncPolicy() == DelosPageVolume.SyncPolicy.METADATA_ONLY,
                        "sync policy must be METADATA_ONLY");
                require(reopened.pageCount() == 2L, "reopened volume must preserve page count");
                require("alpha".equals(new String(reopened.readPage(new DelosPageId(0L)).readRecord(0), StandardCharsets.UTF_8)),
                        "first page payload must survive reopen");
                require("beta".equals(new String(reopened.readPage(new DelosPageId(1L)).readRecord(0), StandardCharsets.UTF_8)),
                        "second page payload must survive reopen");
                expectEof(reopened);
                reopened.force();
            }

            Path noSyncPath = dir.resolve("nosync-pages.dat");
            try (FileChannelPageVolume noSync = FileChannelPageVolume.open(noSyncPath, DelosPageVolume.SyncPolicy.NONE)) {
                require(noSync.syncPolicy() == DelosPageVolume.SyncPolicy.NONE, "sync policy must be NONE");
                noSync.allocatePage(DelosPage.DATA_PAGE_TYPE);
                noSync.force();
                require(noSync.pageCount() == 1L, "no-sync volume must still allocate pages");
            }
        } finally {
            deleteRecursively(dir);
        }

        System.out.println("delosdb-storage-io-s4-file-channel-page-volume: PASS");
    }

    private static void expectEof(FileChannelPageVolume volume) throws Exception {
        try {
            volume.readPage(new DelosPageId(2L));
            throw new AssertionError("expected EOF for page outside volume");
        } catch (EOFException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }
}
