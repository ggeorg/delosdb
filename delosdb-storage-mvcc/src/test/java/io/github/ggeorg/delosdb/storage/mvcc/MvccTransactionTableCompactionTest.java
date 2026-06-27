package io.github.ggeorg.delosdb.storage.mvcc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proofs for compacting terminal transaction state out of the active transaction table. */
final class MvccTransactionTableCompactionTest {
    @Test
    void terminalOutcomesCompactWithoutLosingCatalogVisibility() {
        MvccTransactionManager txManager = new MvccTransactionManager();

        MvccTransaction committed = txManager.begin();
        MvccCommitSequence commitSequence = txManager.commit(committed);
        MvccTransaction aborted = txManager.begin();
        txManager.abort(aborted);

        assertEquals(0, txManager.activeTransactionCount());
        assertEquals(0, txManager.retainedTransactionOutcomeCount());
        assertEquals(new MvccTransactionId(2L), txManager.compactedTransactionIdThrough());
        assertEquals(MvccTransactionStatus.COMMITTED, txManager.statusOf(committed.id()));
        assertEquals(MvccTransactionStatus.ABORTED, txManager.statusOf(aborted.id()));
        assertEquals(commitSequence, txManager.commitSequenceOf(committed.id()).orElseThrow());

        MvccTransaction reader = txManager.begin();
        MvccCapturedVisibility visibility = txManager.captureVisibility(txManager.snapshot(reader));
        assertEquals(MvccTransactionStatus.COMMITTED, visibility.statusOf(committed.id()));
        assertEquals(MvccTransactionStatus.ABORTED, visibility.statusOf(aborted.id()));
        assertTrue(visibility.commitSequenceOf(committed.id()).orElseThrow().isAtOrBefore(visibility.visibleThrough()));
        txManager.abort(reader);
    }

    @Test
    void activeTransactionBlocksCompactionPastItsSnapshotBoundary() {
        MvccTransactionManager txManager = new MvccTransactionManager();

        MvccTransaction seed = txManager.begin();
        txManager.commit(seed);
        MvccTransaction active = txManager.begin();
        MvccSnapshotLease lease = txManager.openSnapshot(active);
        MvccTransaction later = txManager.begin();
        txManager.commit(later);

        assertEquals(1, txManager.activeTransactionCount());
        assertEquals(1, txManager.retainedTransactionOutcomeCount());
        assertEquals(new MvccTransactionId(1L), txManager.compactedTransactionIdThrough());

        lease.close();
        txManager.abort(active);
        assertEquals(0, txManager.activeTransactionCount());
        assertEquals(0, txManager.retainedTransactionOutcomeCount());
        assertEquals(new MvccTransactionId(3L), txManager.compactedTransactionIdThrough());
        assertEquals(MvccTransactionStatus.ABORTED, txManager.statusOf(active.id()));
        assertEquals(MvccTransactionStatus.COMMITTED, txManager.statusOf(later.id()));
    }
}
