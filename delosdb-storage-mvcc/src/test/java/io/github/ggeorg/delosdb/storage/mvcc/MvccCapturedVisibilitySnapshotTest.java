package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A51 proof for captured MVCC visibility state.
 *
 * <p>A44-A48 defined the correctness semantics. This proof adds a frozen
 * transaction-status catalog that row visibility checks can use without going
 * back to the live transaction manager for every version.</p>
 */
public final class MvccCapturedVisibilitySnapshotTest {
    @Test
    public void capturedVisibilityKeepsSnapshotStableAcrossLaterCommits() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction writer = txManager.begin();
        table.update(1, "v2", writer, txManager.snapshot(writer), txManager);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot snapshot = txManager.snapshot(reader);
        MvccCapturedVisibility captured = txManager.captureVisibility(snapshot);
        assertTrue(captured.knownTransactionCount() >= 2,
                "captured visibility records active transaction state at snapshot time");
        assertEquals(new MvccTransactionId(1L), captured.compactedTransactionIdThrough(),
                "captured visibility carries compacted committed seed transaction metadata");
        assertEquals(snapshot.owner(), captured.owner());
        assertEquals(snapshot.visibleThrough(), captured.visibleThrough());
        assertEquals(snapshot.visibleThroughCommand(), captured.ownerVisibleThroughCommand());
        assertTrue(captured.toString().contains("knownTransactionCount="),
                "captured visibility must be inspectable for proof diagnostics");

        assertEquals(Optional.of("v1"), table.read(1, snapshot, captured),
                "captured visibility hides an active-at-capture writer");

        txManager.commit(writer);
        assertEquals(Optional.of("v1"), table.read(1, snapshot, captured),
                "captured visibility remains stable after the hidden writer commits later");

        MvccTransaction laterReader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(laterReader), txManager),
                "a newer live snapshot sees the committed update");
        txManager.abort(reader);
        txManager.abort(laterReader);
    }

    @Test
    public void capturedVisibilityTreatsTransactionsCreatedAfterCaptureAsInvisible() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "base");

        MvccTransaction reader = txManager.begin();
        MvccSnapshot snapshot = txManager.snapshot(reader);
        MvccCapturedVisibility captured = txManager.captureVisibility(snapshot);

        MvccTransaction laterWriter = txManager.begin();
        table.update(1, "later", laterWriter, txManager.snapshot(laterWriter), txManager);
        txManager.commit(laterWriter);

        assertEquals(Optional.of("base"), table.read(1, snapshot, captured),
                "transactions unknown to the captured view are treated as in-progress/invisible");

        MvccTransaction liveReader = txManager.begin();
        assertEquals(Optional.of("later"), table.read(1, txManager.snapshot(liveReader), txManager),
                "the live manager still exposes the later committed update to newer snapshots");
        txManager.abort(reader);
        txManager.abort(liveReader);
    }

    @Test
    public void capturedVisibilityPreservesStatementCommandBoundaries() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction tx = txManager.begin();
        MvccStatementSnapshot insert = txManager.beginStatement(tx);
        table.insert(1, "A", insert);

        MvccStatementSnapshot readBetweenUpdates = txManager.beginStatement(tx);
        MvccCapturedVisibility captured = txManager.captureVisibility(readBetweenUpdates.snapshot());
        assertEquals(Optional.of("A"), table.read(1, readBetweenUpdates.snapshot(), captured));

        MvccStatementSnapshot update = txManager.beginStatement(tx);
        table.update(1, "B", update, txManager);

        assertEquals(Optional.of("A"), table.read(1, readBetweenUpdates.snapshot(), captured),
                "captured visibility preserves owner command boundary after a later same-transaction update");

        MvccStatementSnapshot afterUpdate = txManager.beginStatement(tx);
        assertEquals(Optional.of("B"), table.read(1, afterUpdate, txManager),
                "a new statement snapshot sees the later same-transaction update");
    }

    @Test
    public void capturedVisibilityStillDetectsPrunedHistory() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "live");

        MvccTransaction reader = txManager.begin();
        MvccSnapshot snapshot = txManager.snapshot(reader);
        MvccCapturedVisibility captured = txManager.captureVisibility(snapshot);
        txManager.commit(reader);

        MvccTransaction deleter = txManager.begin();
        table.delete(1, deleter, txManager.snapshot(deleter), txManager);
        txManager.commit(deleter);

        table.cleanup(txManager);

        assertThrows(MvccHistoryPrunedException.class, () -> table.read(1, snapshot, captured),
                "captured visibility must keep A44 missing-history detection loud");
    }

    private static MvccTable<Integer, String> seed(MvccTransactionManager txManager, String value) {
        MvccTable<Integer, String> table = new MvccTable<>();
        MvccTransaction seed = txManager.begin();
        table.insert(1, value, seed);
        txManager.commit(seed);
        return table;
    }
}
