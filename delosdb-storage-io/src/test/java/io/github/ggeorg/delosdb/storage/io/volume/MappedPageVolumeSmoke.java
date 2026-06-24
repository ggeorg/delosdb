package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Smoke test for the S11 optional mapped page-volume candidate. */
public final class MappedPageVolumeSmoke {
    private MappedPageVolumeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("delosdb-s11-mapped-page-volume-");
        Path path = directory.resolve("mapped-volume.dat");

        provesMappedVolumeStartsEmpty(path);
        provesMappedPageSurvivesReopen(path);
        provesMaxPageBound(directory.resolve("bounded-volume.dat"));
        provesOutOfRangeReadFails(directory.resolve("range-volume.dat"));
        provesSyncPoliciesAreAccepted(directory);

        System.out.println("delosdb-storage-io-s11-mapped-page-volume: PASS");
    }

    private static void provesMappedVolumeStartsEmpty(Path path) throws Exception {
        try (MappedPageVolume volume = MappedPageVolume.open(path, 4L)) {
            assertEquals(0L, volume.pageCount(), "new mapped volume should start empty");
        }
    }

    private static void provesMappedPageSurvivesReopen(Path path) throws Exception {
        byte[] payload = "mapped-candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (MappedPageVolume volume = MappedPageVolume.open(path, DelosPageVolume.SyncPolicy.FULL, 4L)) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord(payload);
            volume.writePage(page);
            volume.force();
            assertEquals(1L, volume.pageCount(), "page count after allocation");
        }

        try (MappedPageVolume reopened = MappedPageVolume.open(path, DelosPageVolume.SyncPolicy.FULL, 4L)) {
            DelosPage page = reopened.readPage(new DelosPageId(0L));
            assertTrue(Arrays.equals(payload, page.readRecord(0)), "mapped page payload should survive reopen");
        }
    }

    private static void provesMaxPageBound(Path path) throws Exception {
        try (MappedPageVolume volume = MappedPageVolume.open(path, 1L)) {
            volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            assertThrows(IOException.class, () -> volume.allocatePage(DelosPage.DATA_PAGE_TYPE),
                    "allocation beyond maxPages should fail");
        }
    }

    private static void provesOutOfRangeReadFails(Path path) throws Exception {
        try (MappedPageVolume volume = MappedPageVolume.open(path, 2L)) {
            assertThrows(EOFException.class, () -> volume.readPage(new DelosPageId(0L)),
                    "out-of-range read should fail explicitly");
        }
    }

    private static void provesSyncPoliciesAreAccepted(Path directory) throws Exception {
        for (DelosPageVolume.SyncPolicy policy : DelosPageVolume.SyncPolicy.values()) {
            Path path = directory.resolve("mapped-" + policy.name().toLowerCase(java.util.Locale.ROOT) + ".dat");
            try (MappedPageVolume volume = MappedPageVolume.open(path, policy, 2L)) {
                volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
                volume.force();
                assertEquals(policy, volume.syncPolicy(), "sync policy should round trip");
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> void assertThrows(Class<T> type, ThrowingRunnable runnable, String message)
            throws Exception {
        try {
            runnable.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + ": expected " + type.getName() + ", got " + thrown, thrown);
        }
        throw new AssertionError(message + ": expected " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
