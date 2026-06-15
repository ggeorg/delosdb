package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests for the first DelosDB MVCC kernel. These tests intentionally
 * do not touch Derby heap, B-tree, lock, log, or SQL executor code.
 */
public final class MvccCoreModelTest {
    @Test
    public void testCommittedInsertIsVisibleOnlyToNewSnapshots() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "alpha", writer);

        MvccTransaction readerBeforeCommit = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(readerBeforeCommit);
        assertEquals(Optional.empty(), table.read(1, oldSnapshot, txManager));

        txManager.commit(writer);
        assertEquals(Optional.empty(), table.read(1, oldSnapshot, txManager),
                "old snapshot must not see a transaction committed later");

        MvccTransaction readerAfterCommit = txManager.begin();
        MvccSnapshot newSnapshot = txManager.snapshot(readerAfterCommit);
        assertEquals(Optional.of("alpha"), table.read(1, newSnapshot, txManager));
    }

    @Test
    public void testAbortedInsertIsNeverVisibleAndCanBeCleaned() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "temporary", writer);
        txManager.abort(writer);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(reader), txManager));
        assertEquals(1, table.physicalVersionCount(1));

        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(0, table.physicalVersionCount(1));
    }

    @Test
    public void testUpdateKeepsOldVersionVisibleToOldSnapshot() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        txManager.commit(inserter);

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);
        assertEquals(Optional.of("v1"), table.read(1, oldSnapshot, txManager));

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        assertEquals(Optional.of("v1"), table.read(1, oldSnapshot, txManager),
                "old snapshot must remain stable");

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(newReader), txManager));
        assertEquals(2, table.physicalVersionCount(1));
    }

    @Test
    public void testDeleteCreatesVisibilityBoundaryWithoutImmediateRemoval() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "live", inserter);
        txManager.commit(inserter);

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction deleter = txManager.begin();
        table.delete(1, deleter, txManager.snapshot(deleter), txManager);
        txManager.commit(deleter);

        assertEquals(Optional.of("live"), table.read(1, oldSnapshot, txManager));

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(newReader), txManager));
        assertEquals(1, table.physicalVersionCount(1),
                "the physical version remains until cleanup is safe");
    }

    @Test
    public void testCleanupRespectsOldestActiveSnapshot() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        txManager.commit(inserter);

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        assertEquals(2, table.physicalVersionCount(1));
        assertEquals(0, table.cleanup(txManager).removedVersions());
        assertEquals(Optional.of("v1"), table.read(1, oldSnapshot, txManager),
                "old reader still sees the old row version");
        assertEquals(2, table.physicalVersionCount(1));

        txManager.commit(oldReader);
        assertEquals(1, table.cleanup(txManager).removedVersions());
        assertEquals(1, table.physicalVersionCount(1));

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(newReader), txManager));
    }

    @Test
    public void testRollbackOfDeleteRestoresVisibilityToNewSnapshots() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "stable", inserter);
        txManager.commit(inserter);

        MvccTransaction deleter = txManager.begin();
        table.delete(1, deleter, txManager.snapshot(deleter), txManager);
        txManager.abort(deleter);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("stable"), table.read(1, txManager.snapshot(reader), txManager));
    }

    @Test
    public void testAbortedDeleteMarkerDoesNotBlockLaterUpdate() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        txManager.commit(inserter);

        MvccTransaction abortedDeleter = txManager.begin();
        table.delete(1, abortedDeleter, txManager.snapshot(abortedDeleter), txManager);
        txManager.abort(abortedDeleter);

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(reader), txManager));
    }


    @Test
    public void testOwnerSeesOwnUncommittedInsert() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction writer = txManager.begin();
        table.insert(1, "draft", writer);

        assertEquals(Optional.of("draft"), table.read(1, txManager.snapshot(writer), txManager));
    }

    @Test
    public void testConcurrentWriterCannotUpdatePendingVersion() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        txManager.commit(inserter);

        MvccTransaction firstWriter = txManager.begin();
        table.update(1, "v2", firstWriter, txManager.snapshot(firstWriter), txManager);

        MvccTransaction secondWriter = txManager.begin();
        try {
            table.update(1, "v3", secondWriter, txManager.snapshot(secondWriter), txManager);
        } catch (MvccWriteConflictException expected) {
            return;
        }
        throw new AssertionError("expected write conflict while another writer owns the version boundary");
    }

    @Test
    public void testAbortedUpdateVersionCanBeCleanedWithoutLosingOldValue() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", inserter);
        txManager.commit(inserter);

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.abort(updater);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("v1"), table.read(1, txManager.snapshot(reader), txManager));
        assertEquals(2, table.physicalVersionCount(1));

        assertEquals(1, table.cleanup(txManager).removedVersions());
        assertEquals(1, table.physicalVersionCount(1));
        assertEquals(Optional.of("v1"), table.read(1, txManager.snapshot(reader), txManager));
    }

}
