package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.List;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 cost/statistics proofs for provider-owned MVCC indexes.
 *
 * <p>The PostgreSQL-guided rule is preserved: index statistics describe
 * candidate rows first, then visible matches after checking the authoritative
 * MVCC version chain.</p>
 */
public final class DelosMvccIndexStatsTest {
    @Test
    public void testIndexStatsDistinguishCandidatesFromVisibleMatches() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "index_stats");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "beta"), seed);
        table.insert(2L, List.of(2, "beta"), seed);
        table.insert(3L, List.of(3, "gamma"), seed);
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

        TxContext reader = coordinator.begin();
        VersionedIndexStats betaStats = index.stats("beta", reader.currentView());
        assertEquals(3L, betaStats.indexedKeyCount());
        assertEquals(2L, betaStats.candidateCount(),
                "old beta bucket still has two candidate row identifiers before cleanup");
        assertEquals(1L, betaStats.visibleMatchCount(),
                "only one beta candidate remains visible after MVCC version-chain recheck");
        assertEquals(3L, betaStats.estimatedLookupCost());

        VersionedIndexStats alphaStats = index.stats("alpha", reader.currentView());
        assertEquals(1L, alphaStats.candidateCount());
        assertEquals(1L, alphaStats.visibleMatchCount());

        VersionedTableStats tableStats = table.stats(reader.currentView());
        assertEquals(3L, tableStats.visibleRowCount());
        assertEquals(4L, tableStats.physicalVersionCount());
        assertTrue(tableStats.deadVersionEstimate() >= 1L);
        coordinator.abort(reader);
    }

    @Test
    public void testCleanupImprovesIndexCandidateStatistics() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "index_stats_cleanup");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext seed = coordinator.begin();
        table.insert(1L, List.of(1, "beta"), seed);
        table.insert(2L, List.of(2, "beta"), seed);
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
        assertEquals(2L, index.stats("beta", beforeCleanup.currentView()).candidateCount());
        coordinator.abort(beforeCleanup);

        MvccCleanupResult cleanup = provider.cleanup();
        assertEquals(1, cleanup.removedVersions());
        assertEquals(1, cleanup.removedIndexCandidates());

        TxContext afterCleanup = coordinator.begin();
        VersionedIndexStats betaStats = index.stats("beta", afterCleanup.currentView());
        assertEquals(1L, betaStats.candidateCount());
        assertEquals(1L, betaStats.visibleMatchCount());
        assertEquals(2L, betaStats.estimatedLookupCost());
        coordinator.abort(afterCleanup);
    }
}
