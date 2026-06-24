package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** S7 smoke for the in-memory DelosPageVolume implementation. */
public final class OffHeapPageVolumeSmoke {
    private OffHeapPageVolumeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        provesOffHeapVolumeImplementsDensePageContract();
        provesReadsAndWritesDoNotExposeObjectAliasing();
        provesNoSyncPolicyAndClosedStateAreExplicit();
        System.out.println("delosdb-storage-io-s7-offheap-page-volume: PASS");
    }

    private static void provesOffHeapVolumeImplementsDensePageContract() throws Exception {
        try (OffHeapPageVolume volume = OffHeapPageVolume.open()) {
            require(volume.syncPolicy() == DelosPageVolume.SyncPolicy.NONE, "sync policy must be NONE");
            require(volume.pageCount() == 0L, "new off-heap volume must be empty");

            DelosPage first = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            require(first.pageId().equals(new DelosPageId(0L)), "first page id must be zero");
            first.appendRecord("alpha".getBytes(StandardCharsets.UTF_8));
            volume.writePage(first);

            DelosPage second = volume.allocatePage(7);
            require(second.pageId().equals(new DelosPageId(1L)), "second page id must be one");
            require(second.pageType() == 7, "second page type must be preserved");
            second.appendRecord("beta".getBytes(StandardCharsets.UTF_8));
            volume.writePage(second);

            require(volume.pageCount() == 2L, "page count must include both allocated pages");
            require("alpha".equals(new String(volume.readPage(new DelosPageId(0L)).readRecord(0), StandardCharsets.UTF_8)),
                    "first page payload must round trip");
            require("beta".equals(new String(volume.readPage(new DelosPageId(1L)).readRecord(0), StandardCharsets.UTF_8)),
                    "second page payload must round trip");
            expectEof(() -> volume.readPage(new DelosPageId(2L)), "read outside page count");
            expectEof(() -> volume.writePage(DelosPage.empty(new DelosPageId(3L))), "sparse write");
            volume.force();
        }
    }

    private static void provesReadsAndWritesDoNotExposeObjectAliasing() throws Exception {
        try (OffHeapPageVolume volume = OffHeapPageVolume.open()) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("before".getBytes(StandardCharsets.UTF_8));
            volume.writePage(page);

            page.appendRecord("after-write-local-only".getBytes(StandardCharsets.UTF_8));
            DelosPage firstRead = volume.readPage(new DelosPageId(0L));
            require(firstRead.slotCount() == 1, "mutating source page after write must not mutate stored image");
            require("before".equals(new String(firstRead.readRecord(0), StandardCharsets.UTF_8)),
                    "stored image must preserve written payload");

            firstRead.appendRecord("read-local-only".getBytes(StandardCharsets.UTF_8));
            DelosPage secondRead = volume.readPage(new DelosPageId(0L));
            require(secondRead.slotCount() == 1, "mutating read page must not mutate stored image");
        }
    }

    private static void provesNoSyncPolicyAndClosedStateAreExplicit() throws Exception {
        OffHeapPageVolume volume = OffHeapPageVolume.open();
        volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
        volume.force();
        volume.close();
        expectIOException(volume::pageCount, "closed page count");
        expectIOException(() -> volume.allocatePage(DelosPage.DATA_PAGE_TYPE), "closed allocate");
    }

    private static void expectEof(ThrowingRunnable runnable, String label) throws Exception {
        try {
            runnable.run();
            throw new AssertionError(label + ": expected EOFException");
        } catch (EOFException expected) {
            // expected
        }
    }

    private static void expectIOException(ThrowingRunnable runnable, String label) throws Exception {
        try {
            runnable.run();
            throw new AssertionError(label + ": expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
