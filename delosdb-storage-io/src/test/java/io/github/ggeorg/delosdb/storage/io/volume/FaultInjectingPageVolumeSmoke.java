package io.github.ggeorg.delosdb.storage.io.volume;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** S8 smoke for deterministic I/O-level page-volume fault injection. */
public final class FaultInjectingPageVolumeSmoke {
    private FaultInjectingPageVolumeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        provesNoFaultSchedulePreservesDelegateBehavior();
        provesWriteFailureIsDeterministicAndPreventsDelegateWrite();
        provesForceFailureIsDeterministicAndRetryCanPass();
        provesScheduleRejectsInvalidOperationNumbers();
        System.out.println("delosdb-storage-io-s8-fault-injecting-page-volume: PASS");
    }

    private static void provesNoFaultSchedulePreservesDelegateBehavior() throws Exception {
        try (FaultInjectingPageVolume volume = FaultInjectingPageVolume.wrap(OffHeapPageVolume.open())) {
            require(volume.syncPolicy() == DelosPageVolume.SyncPolicy.NONE, "sync policy must delegate");
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("alpha".getBytes(StandardCharsets.UTF_8));
            volume.writePage(page);
            volume.force();
            require(volume.pageCount() == 1L, "page count must delegate");
            require("alpha".equals(new String(volume.readPage(new DelosPageId(0L)).readRecord(0), StandardCharsets.UTF_8)),
                    "payload must round trip without configured faults");
        }
    }

    private static void provesWriteFailureIsDeterministicAndPreventsDelegateWrite() throws Exception {
        try (FaultInjectingPageVolume volume = FaultInjectingPageVolume.wrap(
                OffHeapPageVolume.open(),
                FaultInjectingPageVolume.FaultSchedule.failOnWrite(2))) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("before".getBytes(StandardCharsets.UTF_8));
            volume.writePage(page);

            DelosPage changed = volume.readPage(new DelosPageId(0L));
            changed.appendRecord("after".getBytes(StandardCharsets.UTF_8));
            expectIOException(() -> volume.writePage(changed), "second write must fail");

            DelosPage persisted = volume.readPage(new DelosPageId(0L));
            require(persisted.slotCount() == 1, "failed write must not update stored page image");
            require("before".equals(new String(persisted.readRecord(0), StandardCharsets.UTF_8)),
                    "failed write must leave prior payload intact");

            volume.writePage(changed);
            DelosPage retried = volume.readPage(new DelosPageId(0L));
            require(retried.slotCount() == 2, "third write should pass after deterministic second-write failure");
        }
    }

    private static void provesForceFailureIsDeterministicAndRetryCanPass() throws Exception {
        try (FaultInjectingPageVolume volume = FaultInjectingPageVolume.wrap(
                OffHeapPageVolume.open(),
                FaultInjectingPageVolume.FaultSchedule.failOnForce(1))) {
            DelosPage page = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            page.appendRecord("force".getBytes(StandardCharsets.UTF_8));
            volume.writePage(page);
            expectIOException(volume::force, "first force must fail");
            volume.force();
        }
    }

    private static void provesScheduleRejectsInvalidOperationNumbers() {
        expectIllegalArgument(() -> FaultInjectingPageVolume.FaultSchedule.failOnWrite(0), "zero write fault");
        expectIllegalArgument(() -> FaultInjectingPageVolume.FaultSchedule.failOnForce(-2), "negative force fault");
        FaultInjectingPageVolume.FaultSchedule both = FaultInjectingPageVolume.FaultSchedule.of(3, 4);
        require(both.failOnWrite() == 3L, "combined schedule must preserve write fault number");
        require(both.failOnForce() == 4L, "combined schedule must preserve force fault number");
    }

    private static void expectIOException(ThrowingRunnable runnable, String label) throws Exception {
        try {
            runnable.run();
            throw new AssertionError(label + ": expected IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    private static void expectIllegalArgument(ThrowingRunnable runnable, String label) {
        try {
            runnable.run();
            throw new AssertionError(label + ": expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (Exception ex) {
            throw new AssertionError(label + ": expected IllegalArgumentException, got " + ex, ex);
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
