package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MVCC-5 visibility proof for provider-owned index candidates.
 *
 * <p>The index is allowed to keep stale physical candidates. Lookup correctness
 * comes from rechecking each candidate against the authoritative MVCC version
 * chain and the caller's snapshot before returning a row. This proof deliberately
 * does not remove index garbage; recovery and vacuum get their own later gates.</p>
 */
public final class MvccDurableIndexVisibilityTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void committedVisibleCandidateIsReturnedFromDurableIndexLookup() {
        Fixture fixture = fixture("committed-visible");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "alpha"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_committed_visible");

        TxContext reader = fixture.coordinator.begin();
        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", reader.currentView())));
        VersionedIndexStats stats = index.stats("alpha", reader.currentView());
        assertEquals(1L, stats.candidateCount());
        assertEquals(1L, stats.visibleMatchCount());
        fixture.coordinator.abort(reader);
    }

    @Test
    public void newerCommittedCandidateIsInvisibleToOlderSnapshot() {
        Fixture fixture = fixture("newer-invisible");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "alpha"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_newer_invisible");
        TxContext oldReader = fixture.coordinator.begin();

        TxContext update = fixture.coordinator.begin();
        fixture.table.update(1L, row(1, "beta"), update);
        fixture.coordinator.commit(update);

        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));
        assertEquals(List.of(), rows(index.lookup("beta", oldReader.currentView())));
        assertEquals(1L, index.stats("beta", oldReader.currentView()).candidateCount());
        assertEquals(0L, index.stats("beta", oldReader.currentView()).visibleMatchCount());

        TxContext newReader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("alpha", newReader.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(index.lookup("beta", newReader.currentView())));

        fixture.coordinator.abort(oldReader);
        fixture.coordinator.abort(newReader);
    }

    @Test
    public void deletedCandidateIsHiddenFromNewSnapshotsButVisibleToOldSnapshots() {
        Fixture fixture = fixture("deleted-hidden");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "alpha"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_deleted_hidden");
        TxContext oldReader = fixture.coordinator.begin();

        TxContext delete = fixture.coordinator.begin();
        fixture.table.delete(1L, delete);
        fixture.coordinator.commit(delete);

        assertEquals(List.of("1=[1, alpha]"), rows(index.lookup("alpha", oldReader.currentView())));

        TxContext newReader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("alpha", newReader.currentView())));
        assertEquals(1L, index.stats("alpha", newReader.currentView()).candidateCount());
        assertEquals(0L, index.stats("alpha", newReader.currentView()).visibleMatchCount());

        fixture.coordinator.abort(oldReader);
        fixture.coordinator.abort(newReader);
    }

    @Test
    public void duplicateKeyCandidateCommittedAfterSnapshotIsIgnored() {
        Fixture fixture = fixture("duplicate-newer-invisible");

        TxContext seed = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "shared"), seed);
        fixture.coordinator.commit(seed);

        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_duplicate_snapshot");
        TxContext oldReader = fixture.coordinator.begin();

        TxContext laterInsert = fixture.coordinator.begin();
        fixture.table.insert(2L, row(2, "shared"), laterInsert);
        fixture.coordinator.commit(laterInsert);

        assertEquals(List.of("1=[1, shared]"), rows(index.lookup("shared", oldReader.currentView())));
        assertEquals(2L, index.stats("shared", oldReader.currentView()).candidateCount());
        assertEquals(1L, index.stats("shared", oldReader.currentView()).visibleMatchCount());

        TxContext newReader = fixture.coordinator.begin();
        assertEquals(List.of("1=[1, shared]", "2=[2, shared]"), rows(index.lookup("shared", newReader.currentView())));
        assertEquals(2L, index.stats("shared", newReader.currentView()).visibleMatchCount());

        fixture.coordinator.abort(oldReader);
        fixture.coordinator.abort(newReader);
    }

    @Test
    public void rolledBackCandidateIsRetainedPhysicallyButNeverReturned() {
        Fixture fixture = fixture("rollback-hidden");
        VersionedIndex<Long, List<Object>> index = fixture.buildNameIndex("idx_rollback_hidden");

        TxContext rolledBackInsert = fixture.coordinator.begin();
        fixture.table.insert(1L, row(1, "draft"), rolledBackInsert);
        fixture.coordinator.abort(rolledBackInsert);

        TxContext committedInsert = fixture.coordinator.begin();
        fixture.table.insert(2L, row(2, "live"), committedInsert);
        fixture.coordinator.commit(committedInsert);

        TxContext reader = fixture.coordinator.begin();
        assertEquals(List.of(), rows(index.lookup("draft", reader.currentView())));
        assertEquals(1L, index.stats("draft", reader.currentView()).candidateCount());
        assertEquals(0L, index.stats("draft", reader.currentView()).visibleMatchCount());
        assertEquals(List.of("2=[2, live]"), rows(index.lookup("live", reader.currentView())));
        fixture.coordinator.abort(reader);
    }

    private Fixture fixture(String name) {
        DelosMvccStorageProvider provider = DelosMvccStorageProvider.openPageBacked(storageDirectory.resolve(name));
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", name.replace('-', '_'));
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        return new Fixture(tableMetadata, table, provider.transactionCoordinator());
    }

    private static List<Object> row(int id, String name) {
        return List.of(id, name);
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
            VersionedTableMetadata tableMetadata,
            VersionedTable<Long, List<Object>> table,
            VersionedTransactionCoordinator coordinator) {
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
