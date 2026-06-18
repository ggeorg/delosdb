package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * MVCC-7 vacuum safety-window proof.
 *
 * <p>Vacuum is allowed to remove physical garbage only after the oldest active
 * snapshot can no longer observe that version. Provider-owned index candidates
 * follow the same rule: stale candidates may remain physically present, but they
 * must not be pruned while any protected version can still be reached through an
 * older snapshot.</p>
 */
public final class MvccVacuumSafetyTest {
    @Test
    public void oldSnapshotProtectsSupersededVersionAndIndexCandidate() {
        Fixture fixture = fixture("vacuum_update_safety");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "alpha"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_name");
        TxContext oldReader = fixture.coordinator.begin();

        TxContext update = fixture.coordinator.begin();
        fixture.table.update(1L, row(1, "beta"), update);
        fixture.coordinator.commit(update);

        VersionedTableStats protectedStats = fixture.table.stats(oldReader.currentView());
        assertEquals(2L, protectedStats.physicalVersionCount());
        assertEquals(0L, protectedStats.deadVersionEstimate(),
                "old snapshot must protect the superseded alpha version");
        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));
        assertIndexStats(index.stats("alpha", oldReader.currentView()), 1L, 1L);
        assertIndexStats(index.stats("beta", oldReader.currentView()), 1L, 0L);

        MvccCleanupResult protectedCleanup = fixture.provider.cleanup();
        assertEquals(0, protectedCleanup.removedVersions());
        assertEquals(0, protectedCleanup.removedIndexCandidates());
        assertEquals(2L, fixture.table.stats(oldReader.currentView()).physicalVersionCount());

        TxContext newReader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("alpha", newReader.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(index.lookup("beta", newReader.currentView())));
        assertIndexStats(index.stats("alpha", newReader.currentView()), 1L, 0L,
                "stale alpha candidate remains physically indexed until the old snapshot closes");
        fixture.coordinator.abort(newReader);

        fixture.coordinator.abort(oldReader);

        TxContext maintenance = fixture.coordinator.begin();
        assertEquals(1L, fixture.table.stats(maintenance.currentView()).deadVersionEstimate());
        MvccCleanupResult cleanup = fixture.provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(0, cleanup.removedLogicalRows());
        assertEquals(1L, fixture.table.stats(maintenance.currentView()).physicalVersionCount());
        assertEquals(List.of(), rows(index.lookup("alpha", maintenance.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(index.lookup("beta", maintenance.currentView())));
        assertIndexStats(index.stats("alpha", maintenance.currentView()), 0L, 0L);
        fixture.coordinator.abort(maintenance);
    }

    @Test
    public void committedDeleteTombstoneIsRetainedUntilOldSnapshotCloses() {
        Fixture fixture = fixture("vacuum_delete_safety");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "alpha"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_name");
        TxContext oldReader = fixture.coordinator.begin();

        TxContext delete = fixture.coordinator.begin();
        fixture.table.delete(1L, delete);
        fixture.coordinator.commit(delete);

        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));
        assertEquals(0L, fixture.table.stats(oldReader.currentView()).deadVersionEstimate());
        MvccCleanupResult protectedCleanup = fixture.provider.cleanup();
        assertEquals(0, protectedCleanup.removedVersions());
        assertEquals(0, protectedCleanup.removedIndexCandidates());
        assertEquals(0, protectedCleanup.removedLogicalRows());

        TxContext newReader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("alpha", newReader.currentView())));
        assertIndexStats(index.stats("alpha", newReader.currentView()), 1L, 0L,
                "delete tombstone is hidden to new readers but still protected by the old reader");
        fixture.coordinator.abort(newReader);

        fixture.coordinator.abort(oldReader);

        TxContext maintenance = fixture.coordinator.begin();
        assertEquals(1L, fixture.table.stats(maintenance.currentView()).deadVersionEstimate());
        MvccCleanupResult cleanup = fixture.provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(0L, fixture.table.stats(maintenance.currentView()).logicalRowCount());
        assertEquals(0L, fixture.table.stats(maintenance.currentView()).physicalVersionCount());
        assertIndexStats(index.stats("alpha", maintenance.currentView()), 0L, 0L);
        fixture.coordinator.abort(maintenance);
    }

    @Test
    public void abortedVersionAndIndexCandidateCanBeVacuumedImmediately() {
        Fixture fixture = fixture("vacuum_abort_safety");
        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_name");

        TxContext draft = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "draft"), draft);
        fixture.coordinator.abort(draft);

        TxContext reader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("draft", reader.currentView())));
        assertTrue(fixture.table.stats(reader.currentView()).deadVersionEstimate() >= 1L);
        assertIndexStats(index.stats("draft", reader.currentView()), 1L, 0L);

        MvccCleanupResult cleanup = fixture.provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());
        assertEquals(1, cleanup.removedLogicalRows());
        assertEquals(0L, fixture.table.stats(reader.currentView()).physicalVersionCount());
        assertIndexStats(index.stats("draft", reader.currentView()), 0L, 0L);
        fixture.coordinator.abort(reader);
    }

    @Test
    public void newestCommittedVersionIsNeverPrunedAsGarbage() {
        Fixture fixture = fixture("vacuum_live_safety");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "live"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_name");
        MvccCleanupResult cleanup = fixture.provider.cleanup();
        assertEquals(0, cleanup.removedVersions());
        assertEquals(0, cleanup.removedIndexCandidates());
        assertEquals(0, cleanup.removedLogicalRows());

        TxContext reader = fixture.coordinator.begin();
        assertEquals(1L, fixture.table.stats(reader.currentView()).physicalVersionCount());
        assertEquals(List.of("1=[1, live]"), rows(index.lookup("live", reader.currentView())));
        assertIndexStats(index.stats("live", reader.currentView()), 1L, 1L);
        fixture.coordinator.abort(reader);
    }

    private static Fixture fixture(String tableName) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", tableName);
        VersionedTable<Long, List<Object>> table = provider.createTable(metadata);
        return new Fixture(provider, provider.transactionCoordinator(), metadata, table);
    }

    private static List<Object> row(int id, String name) {
        return List.of(id, name);
    }

    private static void assertIndexStats(VersionedIndexStats stats, long candidateCount, long visibleMatchCount) {
        assertIndexStats(stats, candidateCount, visibleMatchCount, "unexpected index stats");
    }

    private static void assertIndexStats(
            VersionedIndexStats stats,
            long candidateCount,
            long visibleMatchCount,
            String message) {
        assertEquals(candidateCount, stats.candidateCount(), message + ": candidate count");
        assertEquals(visibleMatchCount, stats.visibleMatchCount(), message + ": visible match count");
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

    private record Fixture(
            DelosMvccStorageProvider provider,
            VersionedTransactionCoordinator coordinator,
            VersionedTableMetadata tableMetadata,
            VersionedTable<Long, List<Object>> table) {
        private VersionedIndex<Long, List<Object>> buildNameIndex(String indexName) {
            TxContext build = coordinator.begin();
            VersionedIndex<Long, List<Object>> index = table.createIndex(
                    new VersionedIndexMetadata(tableMetadata, indexName, "name", false),
                    row -> row.get(1),
                    build.currentView());
            coordinator.commit(build);
            return index;
        }
    }
}
