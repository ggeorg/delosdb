package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DelosPageVolumeContractSmoke {
    private DelosPageVolumeContractSmoke() {
    }

    public static void main(String[] args) throws Exception {
        provesContractUsesStorageOwnedPagePrimitives();
        provesAllocateReadWriteCountForceAndCloseShape();
        System.out.println("delosdb-storage-io-s3-page-volume-contract: PASS");
    }

    private static void provesContractUsesStorageOwnedPagePrimitives() throws Exception {
        assertEquals(
                DelosPage.class,
                DelosPageVolume.class.getMethod("readPage", DelosPageId.class).getReturnType(),
                "readPage return type");
        assertEquals(
                DelosPageId.class,
                DelosPageVolume.class.getMethod("readPage", DelosPageId.class).getParameterTypes()[0],
                "readPage id type");
        assertEquals(
                DelosPage.class,
                DelosPageVolume.class.getMethod("writePage", DelosPage.class).getParameterTypes()[0],
                "writePage page type");
        assertEquals(
                DelosPage.class,
                DelosPageVolume.class.getMethod("allocatePage", int.class).getReturnType(),
                "allocatePage return type");
    }

    private static void provesAllocateReadWriteCountForceAndCloseShape() throws Exception {
        RecordingPageVolume volume = new RecordingPageVolume();
        DelosPage allocated = volume.allocatePage(7);
        assertEquals(new DelosPageId(0L), allocated.pageId(), "first allocated page id");
        assertEquals(7, allocated.pageType(), "allocated page type");
        assertEquals(1L, volume.pageCount(), "page count after allocate");

        allocated.appendRecord(new byte[] {4, 3, 2, 1});
        volume.writePage(allocated);
        assertEquals(1L, volume.pageCount(), "page count after rewrite");
        assertArrayEquals(new byte[] {4, 3, 2, 1}, volume.readPage(new DelosPageId(0L)).readRecord(0), "payload");

        assertEquals(DelosPageVolume.SyncPolicy.NONE, volume.syncPolicy(), "sync policy");
        volume.force();
        assertEquals(1, volume.forceCount, "force count");
        volume.close();
        assertThrows(IOException.class, () -> volume.pageCount(), "closed page count");
    }

    private static final class RecordingPageVolume implements DelosPageVolume {
        private final Map<DelosPageId, DelosPage> pages = new LinkedHashMap<>();
        private boolean closed;
        private int forceCount;

        @Override
        public DelosPage readPage(DelosPageId id) throws IOException {
            ensureOpen();
            DelosPage page = pages.get(id);
            if (page == null) {
                throw new IOException("page not found: " + id.value());
            }
            return page;
        }

        @Override
        public void writePage(DelosPage page) throws IOException {
            ensureOpen();
            pages.put(page.pageId(), page);
        }

        @Override
        public DelosPage allocatePage(int pageType) throws IOException {
            ensureOpen();
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.put(page.pageId(), page);
            return page;
        }

        @Override
        public long pageCount() throws IOException {
            ensureOpen();
            return pages.size();
        }

        @Override
        public void force() throws IOException {
            ensureOpen();
            forceCount++;
        }

        @Override
        public SyncPolicy syncPolicy() {
            return SyncPolicy.NONE;
        }

        @Override
        public void close() {
            closed = true;
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("volume is closed");
            }
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (expected.length != actual.length) {
            throw new AssertionError(label + ": expected length " + expected.length + " but found " + actual.length);
        }
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != actual[index]) {
                throw new AssertionError(label + ": expected byte " + expected[index] + " at " + index
                        + " but found " + actual[index]);
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but found " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable, String label) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(label + ": expected " + expected.getName()
                    + " but caught " + actual.getClass().getName(), actual);
        }
        throw new AssertionError(label + ": expected " + expected.getName() + " but nothing was thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
