package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A44 missing-history proof.
 *
 * <p>Cleanup must not turn history loss into an indistinguishable
 * Optional.empty(). Legitimate not-visible cases remain normal empty reads, but
 * a snapshot that would have seen a pruned committed version fails loudly.</p>
 */
public final class MvccHistoryPrunedSafetyTest {
    @Test
    public void insertAfterSnapshotIsLegitimatelyInvisible() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction reader = txManager.begin();
        MvccSnapshot beforeInsert = txManager.snapshot(reader);

        MvccTransaction writer = txManager.begin();
        table.insert(1, "after", writer);
        txManager.commit(writer);

        assertEquals(Optional.empty(), table.read(1, beforeInsert, txManager),
                "a snapshot taken before the insert should see a normal empty result, not a history-pruned error");
        txManager.abort(reader);
    }

    @Test
    public void deleteBeforeSnapshotIsLegitimatelyInvisibleEvenAfterCleanupRemovesRow() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "deleted-before-snapshot");

        MvccTransaction delete = txManager.begin();
        table.delete(1, delete, txManager.snapshot(delete), txManager);
        txManager.commit(delete);

        MvccTransaction reader = txManager.begin();
        MvccSnapshot afterDelete = txManager.snapshot(reader);
        assertEquals(Optional.empty(), table.read(1, afterDelete, txManager));

        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(Optional.empty(), table.read(1, afterDelete, txManager),
                "a snapshot taken after the delete should still see a normal empty result after safe cleanup");
        txManager.abort(reader);
    }

    @Test
    public void abortedCreatorIsLegitimatelyInvisibleAfterCleanup() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = new MvccTable<>();

        MvccTransaction draft = txManager.begin();
        table.insert(1, "draft", draft);
        txManager.abort(draft);

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(reader), txManager));

        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(reader), txManager),
                "an aborted creator never produced visible history, so pruning it is not missing history");
        txManager.abort(reader);
    }

    @Test
    public void prunedSupersededVersionNeededByOldSnapshotFailsLoudly() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction update = txManager.begin();
        table.update(1, "v2", update, txManager.snapshot(update), txManager);
        txManager.commit(update);

        assertEquals(Optional.of("v1"), table.read(1, oldSnapshot, txManager));

        // Simulate an unsafe maintenance boundary: the snapshot object still
        // exists, but the transaction manager no longer protects it. A44's job
        // is to fail loudly instead of returning Optional.empty().
        txManager.abort(oldReader);
        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, table.physicalVersionCount(1));

        assertThrows(MvccHistoryPrunedException.class,
                () -> table.read(1, oldSnapshot, txManager),
                "old snapshot would have seen the pruned v1 version and must not get a silent empty result");

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(newReader), txManager),
                "new snapshots still read the retained newest version normally");
        txManager.abort(newReader);
    }

    @Test
    public void prunedDeletedRowNeededByOldSnapshotFailsLoudlyAfterLogicalRowRemoval() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "live");

        MvccTransaction oldReader = txManager.begin();
        MvccSnapshot oldSnapshot = txManager.snapshot(oldReader);

        MvccTransaction delete = txManager.begin();
        table.delete(1, delete, txManager.snapshot(delete), txManager);
        txManager.commit(delete);

        assertEquals(Optional.of("live"), table.read(1, oldSnapshot, txManager));

        txManager.abort(oldReader);
        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(0, table.logicalRowCount());

        assertThrows(MvccHistoryPrunedException.class,
                () -> table.read(1, oldSnapshot, txManager),
                "table-level prune markers must survive logical row removal");

        MvccTransaction afterDelete = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(afterDelete), txManager),
                "snapshots newer than the delete still see a normal empty result");
        txManager.abort(afterDelete);
    }

    @Test
    public void safeVacuumKeepsNewestVersionReadable() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction update = txManager.begin();
        table.update(1, "v2", update, txManager.snapshot(update), txManager);
        txManager.commit(update);

        MvccCleanupResult cleanup = table.cleanup(txManager);
        assertEquals(1, cleanup.removedVersions());
        assertEquals(0, cleanup.removedLogicalRows());

        MvccTransaction reader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(reader), txManager));
        txManager.abort(reader);
    }

    private static MvccTable<Integer, String> seed(MvccTransactionManager txManager, String value) {
        MvccTable<Integer, String> table = new MvccTable<>();
        MvccTransaction seed = txManager.begin();
        table.insert(1, value, seed);
        txManager.commit(seed);
        return table;
    }
}
