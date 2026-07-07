package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

/**
 * MVCC buffer-manager phase-2 coordinator for WAL-before-page-flush and
 * grouped page-volume force boundaries.
 *
 * <p>The coordinator deliberately stays local to the page-backed MVCC store.
 * It does not introduce a Derby raw-store cache dependency and it does not
 * change heap logging semantics. Its job is narrow: a dirty page with a
 * non-zero pageLSN may not be flushed until the caller has recorded that the
 * covering MVCC log is durable, and a batch of dirty pages is followed by a
 * single page-volume force boundary.</p>
 */
final class MvccBufferFlushCoordinator {
    private long durableLogLsn = DelosLogSequenceNumber.NONE.value();
    private long walBeforeFlushChecks;
    private long walBeforeFlushFailures;
    private long groupCommitBatches;
    private long groupedPageFlushes;
    private long skippedForceBatches;

    synchronized void recordLogForcedThrough(DelosLogSequenceNumber lsn) {
        if (lsn == null || lsn.equals(DelosLogSequenceNumber.NONE)) {
            return;
        }
        if (lsn.value() < durableLogLsn) {
            throw new IllegalArgumentException("MVCC durable log LSN moved backwards: current="
                    + durableLogLsn + ", new=" + lsn.value());
        }
        durableLogLsn = lsn.value();
    }

    synchronized void beforePageFlush(DelosPage page) {
        long pageLsn = page.pageLsn();
        if (pageLsn <= DelosLogSequenceNumber.NONE.value()) {
            return;
        }
        walBeforeFlushChecks++;
        if (pageLsn > durableLogLsn) {
            walBeforeFlushFailures++;
            throw new IllegalStateException("MVCC WAL-before-flush violation: page "
                    + page.pageId().value() + " has pageLSN=" + pageLsn
                    + " but durableLogLsn=" + durableLogLsn);
        }
    }

    synchronized void forcePageVolumeAfterBatch(DelosPageVolume volume, long flushedPages) throws IOException {
        if (flushedPages <= 0L) {
            skippedForceBatches++;
            return;
        }
        volume.force();
        groupCommitBatches++;
        groupedPageFlushes += flushedPages;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(
                durableLogLsn,
                walBeforeFlushChecks,
                walBeforeFlushFailures,
                groupCommitBatches,
                groupedPageFlushes,
                skippedForceBatches);
    }

    record Snapshot(
            long durableLogLsn,
            long walBeforeFlushChecks,
            long walBeforeFlushFailures,
            long groupCommitBatches,
            long groupedPageFlushes,
            long skippedForceBatches) {
    }
}
