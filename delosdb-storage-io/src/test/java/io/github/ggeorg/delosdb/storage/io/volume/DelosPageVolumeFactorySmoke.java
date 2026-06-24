package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Smoke test for S12 page-volume factory construction. */
public final class DelosPageVolumeFactorySmoke {
    private DelosPageVolumeFactorySmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("delosdb-storage-io-s12-");
        provesFileChannelFactoryReopensDurableBytes(root.resolve("file-channel.pages"));
        provesMappedFactoryReopensDurableBytes(root.resolve("mapped.pages"));
        provesOffHeapFactoryIsFreshAndNonDurable(root.resolve("offheap.pages"));
        provesFaultInjectingFactoryDecoratesDelegate(root.resolve("fault.pages"));
        System.out.println("delosdb-storage-io-s12-page-volume-factory: PASS");
    }

    private static void provesFileChannelFactoryReopensDurableBytes(Path path) throws Exception {
        DelosPageVolumeFactory factory = DelosPageVolumeFactories.fileChannel(DelosPageVolume.SyncPolicy.FULL);
        byte[] payload = new byte[] {1, 2, 3, 4};
        writeOneRecord(factory, path, payload);
        assertRecord(factory, path, payload);
    }

    private static void provesMappedFactoryReopensDurableBytes(Path path) throws Exception {
        DelosPageVolumeFactory factory = DelosPageVolumeFactories.mapped(DelosPageVolume.SyncPolicy.FULL, 4L);
        byte[] payload = new byte[] {5, 6, 7, 8};
        writeOneRecord(factory, path, payload);
        assertRecord(factory, path, payload);
    }

    private static void provesOffHeapFactoryIsFreshAndNonDurable(Path path) throws Exception {
        DelosPageVolumeFactory factory = DelosPageVolumeFactories.offHeap();
        try (DelosPageVolume volume = factory.open(path)) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord(new byte[] {9});
            volume.writePage(page);
            assertEquals(1L, volume.pageCount(), "offheap pageCount before close");
        }
        try (DelosPageVolume reopened = factory.open(path)) {
            assertEquals(0L, reopened.pageCount(), "offheap factory opens a fresh non-durable volume");
        }
    }

    private static void provesFaultInjectingFactoryDecoratesDelegate(Path path) throws Exception {
        DelosPageVolumeFactory factory = DelosPageVolumeFactories.faultInjecting(
                DelosPageVolumeFactories.offHeap(),
                FaultInjectingPageVolume.FaultSchedule.failOnWrite(1L));
        try (DelosPageVolume volume = factory.open(path)) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord(new byte[] {10});
            assertThrows(IOException.class, () -> volume.writePage(page), "injected factory write failure");
        }
    }

    private static void writeOneRecord(DelosPageVolumeFactory factory, Path path, byte[] payload) throws Exception {
        try (DelosPageVolume volume = factory.open(path)) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord(payload);
            volume.writePage(page);
            volume.force();
        }
    }

    private static void assertRecord(DelosPageVolumeFactory factory, Path path, byte[] expected) throws Exception {
        try (DelosPageVolume volume = factory.open(path)) {
            assertEquals(1L, volume.pageCount(), "reopened pageCount");
            DelosPage page = volume.readPage(new DelosPageId(0L));
            if (!Arrays.equals(expected, page.readRecord(0))) {
                throw new AssertionError("reopened payload mismatch");
            }
        }
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable action, String label)
            throws Exception {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(label + ": expected " + expected.getName() + ", got "
                    + actual.getClass().getName(), actual);
        }
        throw new AssertionError(label + ": expected " + expected.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
