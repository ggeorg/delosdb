package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused contract for the low-level MVCC visibility vocabulary.
 *
 * <p>This proof intentionally stays below Derby store/SQL integration. It locks
 * the transaction id, commit-sequence, snapshot, row-version, and visibility
 * rules before the durable page/index layers start depending on them.</p>
 */
public final class MvccSnapshotVisibilityTest {
    @Test
    public void testOwnWritesVisibleButHiddenFromOtherActiveSnapshots() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "draft", writer);

        assertEquals(Optional.of("draft"), table.read(1, txManager.snapshot(writer), txManager),
                "a transaction must see its own uncommitted insert");

        MvccTransaction otherReader = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(otherReader), txManager),
                "another active transaction must not see the uncommitted insert");

        txManager.abort(writer);
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(otherReader), txManager),
                "aborting the writer must not make the draft visible");
        txManager.abort(otherReader);
    }

    @Test
    public void testCommittedBeforeSnapshotVisible() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "committed", writer);
        txManager.commit(writer);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("committed"), table.read(1, txManager.snapshot(reader), txManager),
                "a version committed before snapshot capture must be visible");
        txManager.abort(reader);
    }

    @Test
    public void testCommitAfterSnapshotRemainsInvisibleToCapturedSnapshot() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "later", writer);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot capturedBeforeCommit = txManager.snapshot(reader);

        txManager.commit(writer);

        assertEquals(Optional.empty(), table.read(1, capturedBeforeCommit, txManager),
                "a transaction active at capture remains invisible to that snapshot after it commits");

        MvccTransaction newerReader = txManager.begin();
        assertEquals(Optional.of("later"), table.read(1, txManager.snapshot(newerReader), txManager),
                "a newer snapshot must see the committed version");
        txManager.abort(reader);
        txManager.abort(newerReader);
    }

    @Test
    public void testAbortedVersionInvisibleAndLatestCommittedVersionSelected() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction seed = txManager.begin();
        table.insert(1, "v1", seed);
        txManager.commit(seed);

        MvccTransaction abortedUpdater = txManager.begin();
        table.update(1, "v2-aborted", abortedUpdater, txManager.snapshot(abortedUpdater), txManager);
        txManager.abort(abortedUpdater);

        MvccTransaction committedUpdater = txManager.begin();
        table.update(1, "v3", committedUpdater, txManager.snapshot(committedUpdater), txManager);
        txManager.commit(committedUpdater);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("v3"), table.read(1, txManager.snapshot(reader), txManager),
                "visibility must skip aborted versions and choose the newest committed visible version");
        txManager.abort(reader);
    }

    @Test
    public void testCommittedDeleteHiddenFromNewSnapshotsButVisibleToOldSnapshots() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction seed = txManager.begin();
        table.insert(1, "live", seed);
        txManager.commit(seed);

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction deleter = txManager.begin();
        table.delete(1, deleter, txManager.snapshot(deleter), txManager);
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(deleter), txManager),
                "a transaction must not see a row it deleted itself");
        txManager.commit(deleter);

        assertEquals(Optional.of("live"), table.read(1, oldSnapshot, txManager),
                "an old snapshot must keep seeing the pre-delete version");

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(newReader), txManager),
                "a new snapshot must hide the committed delete");
        txManager.abort(oldReader);
        txManager.abort(newReader);
    }
}
