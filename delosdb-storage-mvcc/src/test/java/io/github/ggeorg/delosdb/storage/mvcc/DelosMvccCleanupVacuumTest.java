package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 10 PostgreSQL-guided cleanup/vacuum proofs for the experimental MVCC provider.
 *
 * <p>Cleanup may remove dead physical versions only after no active snapshot can
 * still see them. Provider-owned index candidates follow the same rule: index
 * entries are pruned only when the authoritative version chain no longer has a
 * visible-or-protected version for that indexed value.</p>
 */
public final class DelosMvccCleanupVacuumTest {
    @Test
    public void testOldSnapshotProtectsOldVersionAndOldIndexCandidate() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "vacuum_update");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);

        TxContext oldReader = coordinator.begin();

        TxContext update = coordinator.begin();
        table.update(1L, List.of(1, "beta"), update);
        coordinator.commit(update);

        VersionedTableStats protectedStats = table.stats(oldReader.currentView());
        assertEquals(2L, protectedStats.physicalVersionCount());
        assertEquals(0L, protectedStats.deadVersionEstimate(),
                "the old version is not dead while an old snapshot can still see it");

        MvccCleanupResult protectedCleanup = provider.cleanup();
        assertEquals(0, protectedCleanup.removedVersions());
        assertEquals(0, protectedCleanup.removedIndexCandidates());
        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));

        TxContext newReader = coordinator.begin();
        assertEquals(List.of("1=[1, beta]"), rows(index.lookup("beta", newReader.currentView())));
        coordinator.abort(newReader);

        coordinator.abort(oldReader);

        TxContext maintenanceView = coordinator.begin();
        assertEquals(1L, table.stats(maintenanceView.currentView()).deadVersionEstimate());
        MvccCleanupResult cleanup = provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(0, cleanup.removedLogicalRows());
        assertEquals(List.of(), rows(index.lookup("alpha", maintenanceView.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(index.lookup("beta", maintenanceView.currentView())));
        assertEquals(1L, table.stats(maintenanceView.currentView()).physicalVersionCount());
        coordinator.abort(maintenanceView);
    }

    @Test
    public void testCommittedDeleteIsCleanedOnlyAfterOldSnapshotIsGone() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "vacuum_delete");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "alpha"), seed);
        coordinator.commit(seed);

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);

        TxContext oldReader = coordinator.begin();

        TxContext delete = coordinator.begin();
        table.delete(1L, delete);
        coordinator.commit(delete);

        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));
        assertEquals(0, provider.cleanup().removedVersions(),
                "delete tombstone must be retained while the old snapshot is active");

        coordinator.abort(oldReader);

        TxContext maintenanceView = coordinator.begin();
        assertEquals(1L, table.stats(maintenanceView.currentView()).deadVersionEstimate());
        MvccCleanupResult cleanup = provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(List.of(), rows(index.lookup("alpha", maintenanceView.currentView())));
        assertEquals(0L, table.stats(maintenanceView.currentView()).logicalRowCount());
        assertEquals(0L, table.stats(maintenanceView.currentView()).physicalVersionCount());
        coordinator.abort(maintenanceView);
    }

    @Test
    public void testAbortedInsertAndItsIndexCandidateAreVacuumed() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "vacuum_abort");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);

        TxContext aborted = coordinator.begin();
        table.insert(1L, List.of(1, "draft"), aborted);
        coordinator.abort(aborted);

        TxContext reader = coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("draft", reader.currentView())));
        assertTrue(table.stats(reader.currentView()).deadVersionEstimate() >= 1L);
        MvccCleanupResult cleanup = provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(0L, table.stats(reader.currentView()).physicalVersionCount());
        coordinator.abort(reader);
    }

    private static List<String> rows(VersionedScan<Long, List<Object>> scan) {
        List<String> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                rows.add(row.key() + "=" + row.value());
            }
        }
        return rows;
    }
}
