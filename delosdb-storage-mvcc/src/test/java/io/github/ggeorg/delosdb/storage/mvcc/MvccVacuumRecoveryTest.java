package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * MVCC-8 vacuum/recovery interaction proof.
 *
 * <p>Vacuum is an optimization, not part of the visibility contract. A crash
 * before a compact checkpoint may leave garbage safely retained after recovery.
 * A checkpoint after vacuum may remove garbage from the recovered image. Both
 * paths must preserve committed visible rows, committed deletes, and the table
 * catalog.</p>
 */
public final class MvccVacuumRecoveryTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void crashAfterVacuumBeforeCheckpointRecoversCorrectVisibleStateWithGarbageRetained() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "vacuum_crash_before_checkpoint");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext seed = transactions.begin();
        table.insert(1L, row(1, "v1"), seed);
        table.insert(2L, row(2, "delete-me"), seed);
        transactions.commit(seed);

        TxContext update = transactions.begin();
        table.update(1L, row(1, "v2"), update);
        transactions.commit(update);

        TxContext delete = transactions.begin();
        table.delete(2L, delete);
        transactions.commit(delete);

        MvccCleanupResult cleanup = provider.cleanup();
        assertTrue(cleanup.removedVersions() >= 1, "vacuum should remove in-memory superseded versions");
        assertTrue(cleanup.removedLogicalRows() >= 1, "vacuum should remove the fully-deleted logical row");

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        try {
            assertEquals(Optional.of(row(1, "v2")), recoveredTable.read(1L, reader.currentView()));
            assertEquals(Optional.empty(), recoveredTable.read(2L, reader.currentView()));
            assertEquals(List.of("1=[1, v2]"), rows(recoveredTable.openScan(reader.currentView())));

            VersionedTableStats stats = recoveredTable.stats(reader.currentView());
            assertEquals(1L, stats.visibleRowCount());
            assertTrue(stats.logicalRowCount() >= 1L,
                    "without checkpoint, recovery may safely retain deleted logical rows from the append-only log");
            assertTrue(stats.physicalVersionCount() >= 2L,
                    "without checkpoint, recovery may safely retain pre-vacuum garbage from the append-only log");
        } finally {
            recovered.transactionCoordinator().abort(reader);
        }
    }

    @Test
    public void crashAfterVacuumCheckpointRecoversCompactedVisibleImage() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "vacuum_checkpoint_compacted");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext seed = transactions.begin();
        table.insert(1L, row(1, "v1"), seed);
        table.insert(2L, row(2, "delete-me"), seed);
        transactions.commit(seed);

        TxContext update = transactions.begin();
        table.update(1L, row(1, "v2"), update);
        transactions.commit(update);

        TxContext delete = transactions.begin();
        table.delete(2L, delete);
        transactions.commit(delete);

        MvccCleanupResult cleanup = provider.cleanup();
        assertTrue(cleanup.removedVersions() >= 1, "vacuum should prune old committed versions before checkpoint");
        assertTrue(cleanup.removedLogicalRows() >= 1, "vacuum should prune fully-deleted logical rows before checkpoint");
        provider.checkpoint();

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        assertEquals(List.of(metadata), recovered.listTables());
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        try {
            assertEquals(Optional.of(row(1, "v2")), recoveredTable.read(1L, reader.currentView()));
            assertEquals(Optional.empty(), recoveredTable.read(2L, reader.currentView()));
            assertEquals(List.of("1=[1, v2]"), rows(recoveredTable.openScan(reader.currentView())));

            VersionedTableStats stats = recoveredTable.stats(reader.currentView());
            assertEquals(1L, stats.logicalRowCount());
            assertEquals(1L, stats.visibleRowCount());
            assertEquals(1L, stats.physicalVersionCount(),
                    "checkpoint after vacuum should recover only the compact committed image");
            assertEquals(0L, stats.deadVersionEstimate());
        } finally {
            recovered.transactionCoordinator().abort(reader);
        }
    }

    @Test
    public void activeSnapshotPreventsUnsafeVacuumCheckpointCompaction() {
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "vacuum_active_snapshot_checkpoint");

        DelosMvccStorageProvider provider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();

        TxContext seed = transactions.begin();
        table.insert(1L, row(1, "old"), seed);
        transactions.commit(seed);

        TxContext oldReader = transactions.begin();

        TxContext update = transactions.begin();
        table.update(1L, row(1, "new"), update);
        transactions.commit(update);

        MvccCleanupResult protectedCleanup = provider.cleanup();
        assertEquals(0, protectedCleanup.removedVersions(),
                "active old snapshot must protect the superseded version from vacuum");
        assertEquals(Optional.of(row(1, "old")), table.read(1L, oldReader.currentView()));
        assertThrows(IllegalStateException.class, provider::checkpoint,
                "checkpoint must not compact while an old snapshot can still need pre-vacuum versions");

        transactions.abort(oldReader);

        MvccCleanupResult cleanup = provider.cleanup();
        assertTrue(cleanup.removedVersions() >= 1, "after the old snapshot closes, vacuum may prune the old version");
        provider.checkpoint();

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recovered.openTable(metadata);
        TxContext reader = recovered.transactionCoordinator().begin();
        try {
            assertEquals(Optional.of(row(1, "new")), recoveredTable.read(1L, reader.currentView()));
            assertEquals(1L, recoveredTable.stats(reader.currentView()).physicalVersionCount());
        } finally {
            recovered.transactionCoordinator().abort(reader);
        }
    }

    private static List<Object> row(int id, String name) {
        return List.of(id, name);
    }

    private static List<String> rows(VersionedScan<Long, List<Object>> scan) {
        java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        try (scan) {
            while (scan.next()) {
                rows.add(scan.row().key() + "=" + scan.row().value());
            }
        }
        return rows;
    }
}
