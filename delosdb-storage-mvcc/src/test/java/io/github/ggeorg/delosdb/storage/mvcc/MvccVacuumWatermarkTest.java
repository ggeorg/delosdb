package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A45 vacuum watermark proof.
 *
 * <p>A44 made unsafe pruning fail loudly after the fact. A45 prevents that
 * pruning while a retained snapshot lease is still open.</p>
 */
public final class MvccVacuumWatermarkTest {
    @Test
    public void retainedSnapshotPinsSupersededVersionUntilClosed() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "v1");

        MvccTransaction reader = txManager.begin();
        MvccSnapshotLease lease = txManager.openSnapshot(reader);
        MvccSnapshot oldSnapshot = lease.snapshot();
        txManager.commit(reader);
        assertEquals(1, txManager.retainedSnapshotCount());

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", updater, txManager.snapshot(updater), txManager);
        txManager.commit(updater);

        MvccCleanupResult protectedCleanup = table.cleanup(txManager);
        assertEquals(0, protectedCleanup.removedVersions(),
                "retained snapshot watermark must prevent pruning the old version");
        assertEquals(2, table.physicalVersionCount(1));
        assertEquals(Optional.of("v1"), table.read(1, oldSnapshot, txManager));

        lease.close();
        assertEquals(0, txManager.retainedSnapshotCount());

        MvccCleanupResult afterClose = table.cleanup(txManager);
        assertEquals(1, afterClose.removedVersions(),
                "once the retained snapshot closes, the superseded version can be pruned");
        assertEquals(1, table.physicalVersionCount(1));
        assertThrows(MvccHistoryPrunedException.class, () -> table.read(1, oldSnapshot, txManager),
                "the old unretained snapshot now fails loudly instead of reading missing history");

        MvccTransaction newReader = txManager.begin();
        assertEquals(Optional.of("v2"), table.read(1, txManager.snapshot(newReader), txManager),
                "newer snapshots still read the retained current version");
        txManager.abort(newReader);
    }

    @Test
    public void retainedSnapshotPinsDeletedRowUntilClosed() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTable<Integer, String> table = seed(txManager, "live");

        MvccTransaction reader = txManager.begin();
        MvccSnapshotLease lease = txManager.openSnapshot(reader);
        MvccSnapshot oldSnapshot = lease.snapshot();
        txManager.commit(reader);

        MvccTransaction deleter = txManager.begin();
        table.delete(1, deleter, txManager.snapshot(deleter), txManager);
        txManager.commit(deleter);

        MvccCleanupResult protectedCleanup = table.cleanup(txManager);
        assertEquals(0, protectedCleanup.removedVersions(),
                "retained snapshot watermark must prevent pruning a deleted row still visible to the old snapshot");
        assertEquals(0, protectedCleanup.removedLogicalRows());
        assertEquals(1, table.logicalRowCount());
        assertEquals(Optional.of("live"), table.read(1, oldSnapshot, txManager));

        lease.close();

        MvccCleanupResult afterClose = table.cleanup(txManager);
        assertEquals(1, afterClose.removedVersions());
        assertEquals(1, afterClose.removedLogicalRows());
        assertEquals(0, table.logicalRowCount());
        assertThrows(MvccHistoryPrunedException.class, () -> table.read(1, oldSnapshot, txManager),
                "table-level prune markers must explain why old snapshot history is gone");

        MvccTransaction afterDelete = txManager.begin();
        assertEquals(Optional.empty(), table.read(1, txManager.snapshot(afterDelete), txManager),
                "newer snapshots still see a legitimate empty result after the committed delete");
        txManager.abort(afterDelete);
    }

    @Test
    public void snapshotLeaseCloseIsIdempotent() {
        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTransaction reader = txManager.begin();
        MvccSnapshotLease lease = txManager.openSnapshot(reader);
        assertEquals(1, txManager.retainedSnapshotCount());
        lease.close();
        lease.close();
        assertEquals(0, txManager.retainedSnapshotCount());
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
