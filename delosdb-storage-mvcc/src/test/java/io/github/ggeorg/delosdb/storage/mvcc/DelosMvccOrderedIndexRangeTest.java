package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 14 ordered-index range scan proofs for provider-owned MVCC indexes.
 *
 * <p>This follows the PostgreSQL B-tree/heap rule used by the MVCC prototype:
 * an ordered index scan yields candidate row identifiers in index-key order,
 * but every row is still rechecked against the authoritative version chain for
 * the reader snapshot.</p>
 */
public final class DelosMvccOrderedIndexRangeTest {
    @Test
    public void testOrderedRangeScanRechecksMvccVisibility() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "range_index");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "beta"), seed);
        table.insert(2L, List.of(2, "beta"), seed);
        table.insert(3L, List.of(3, "gamma"), seed);
        table.insert(4L, List.of(4, "omega"), seed);
        coordinator.commit(seed);

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);

        TxContext oldReader = coordinator.begin();

        TxContext update = coordinator.begin();
        table.update(1L, List.of(1, "delta"), update);
        coordinator.commit(update);

        assertEquals(List.of(1L, 2L), rowKeys(index.lookupRange(
                "beta", true, "beta", true, oldReader.currentView())),
                "old snapshot still sees the old beta version through the index candidate");

        TxContext newReader = coordinator.begin();
        assertEquals(List.of(2L), rowKeys(index.lookupRange(
                "beta", true, "beta", true, newReader.currentView())),
                "new snapshot sees only the still-visible beta row");
        assertEquals(List.of(1L, 3L), rowKeys(index.lookupRange(
                "delta", true, "gamma", true, newReader.currentView())),
                "range scan returns visible rows in ordered index-key buckets");

        VersionedIndexStats betaStats = index.statsRange("beta", true, "beta", true, newReader.currentView());
        assertEquals(2L, betaStats.candidateCount(),
                "the stale beta candidate remains until cleanup");
        assertEquals(1L, betaStats.visibleMatchCount(),
                "visibility recheck removes the updated row from the beta result");

        coordinator.abort(oldReader);
        coordinator.abort(newReader);
    }

    @Test
    public void testCleanupPrunesStaleRangeCandidates() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "range_cleanup");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "beta"), seed);
        table.insert(2L, List.of(2, "gamma"), seed);
        coordinator.commit(seed);

        TxContext build = coordinator.begin();
        VersionedIndex<Long, List<Object>> index = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        coordinator.commit(build);

        TxContext update = coordinator.begin();
        table.update(1L, List.of(1, "alpha"), update);
        coordinator.commit(update);

        TxContext beforeCleanup = coordinator.begin();
        assertEquals(1L, index.statsRange("beta", true, "beta", true, beforeCleanup.currentView()).candidateCount());
        coordinator.abort(beforeCleanup);

        MvccCleanupResult cleanup = provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());

        TxContext afterCleanup = coordinator.begin();
        VersionedIndexStats betaStats = index.statsRange("beta", true, "beta", true, afterCleanup.currentView());
        assertEquals(0L, betaStats.candidateCount());
        assertEquals(0L, betaStats.visibleMatchCount());
        assertEquals(List.of(1L, 2L), rowKeys(index.lookupRange(
                "alpha", true, "gamma", true, afterCleanup.currentView())));
        coordinator.abort(afterCleanup);
    }

    private static List<Long> rowKeys(VersionedScan<Long, List<Object>> scan) {
        List<Long> keys = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                VersionedRow<Long, List<Object>> row = scan.row();
                keys.add(row.key());
            }
        }
        return keys;
    }
}
