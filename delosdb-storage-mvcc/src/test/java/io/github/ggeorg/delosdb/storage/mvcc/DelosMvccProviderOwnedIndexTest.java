package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 8 provider-owned index proofs for the experimental MVCC storage module.
 *
 * <p>The index stores row-key candidates and rechecks table-version visibility
 * on lookup. It is intentionally not Derby B-tree integration yet.</p>
 */
public final class DelosMvccProviderOwnedIndexTest {
    @TempDir
    private Path storageDirectory;

    @Test
    public void testCommittedIndexLookupWorksThroughProviderOwnedIndex() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "indexed_items");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "alpha"), insert);
        table.insert(2L, List.of(2, "beta"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of("1=[1, alpha]"), rows(nameIndex.lookup("alpha", reader.currentView())));
        assertEquals(List.of("2=[2, beta]"), rows(nameIndex.lookup("beta", reader.currentView())));
        assertEquals(List.of(), rows(nameIndex.lookup("missing", reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testIndexLookupRechecksSnapshotVisibilityAfterUpdate() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "snapshot_index");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "alpha"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_snapshot_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext oldReader = provider.transactionCoordinator().begin();

        TxContext update = provider.transactionCoordinator().begin();
        table.update(1L, List.of(1, "beta"), update);
        provider.transactionCoordinator().commit(update);

        assertEquals(List.of("1=[1, alpha]"), rows(nameIndex.lookup("alpha", oldReader.currentView())));
        assertEquals(List.of(), rows(nameIndex.lookup("beta", oldReader.currentView())));

        TxContext newReader = provider.transactionCoordinator().begin();
        assertEquals(List.of(), rows(nameIndex.lookup("alpha", newReader.currentView())));
        assertEquals(List.of("1=[1, beta]"), rows(nameIndex.lookup("beta", newReader.currentView())));

        provider.transactionCoordinator().abort(oldReader);
        provider.transactionCoordinator().abort(newReader);
    }

    @Test
    public void testRollbackAndDeleteDoNotCreateFalseVisibleIndexRows() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "rollback_index");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_rollback_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext rolledBackInsert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "draft"), rolledBackInsert);
        provider.transactionCoordinator().abort(rolledBackInsert);

        TxContext committedInsert = provider.transactionCoordinator().begin();
        table.insert(2L, List.of(2, "live"), committedInsert);
        provider.transactionCoordinator().commit(committedInsert);

        TxContext rolledBackDelete = provider.transactionCoordinator().begin();
        table.delete(2L, rolledBackDelete);
        provider.transactionCoordinator().abort(rolledBackDelete);

        TxContext committedDelete = provider.transactionCoordinator().begin();
        table.delete(2L, committedDelete);
        provider.transactionCoordinator().commit(committedDelete);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of(), rows(nameIndex.lookup("draft", reader.currentView())));
        assertEquals(List.of(), rows(nameIndex.lookup("live", reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testIndexMetadataAndDuplicateIndexRulesAreProviderOwned() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.PROVIDER_OWNED_INDEXES));

        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "index_metadata");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);
        assertEquals(List.of(), table.listIndexes());

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndexMetadata indexMetadata = new VersionedIndexMetadata(tableMetadata, "idx_name", "name", false);
        table.createIndex(indexMetadata, row -> row.get(1), build.currentView());
        provider.transactionCoordinator().commit(build);

        assertEquals(List.of(indexMetadata), table.listIndexes());
        assertEquals(indexMetadata, table.openIndex("idx_name").metadata());
        assertThrows(IllegalStateException.class, () -> table.openIndex("missing"));

        TxContext duplicate = provider.transactionCoordinator().begin();
        assertThrows(IllegalStateException.class, () -> table.createIndex(indexMetadata, row -> row.get(1), duplicate.currentView()));
        provider.transactionCoordinator().abort(duplicate);
    }

    @Test
    public void testRecoveredTableCanRebuildProviderOwnedIndex() {
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "recovered_index");

        DelosMvccStorageProvider writer = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> writerTable = writer.createTable(tableMetadata);
        TxContext tx = writer.transactionCoordinator().begin();
        writerTable.insert(1L, List.of(1, "alpha"), tx);
        writerTable.insert(2L, List.of(2, "beta"), tx);
        writer.transactionCoordinator().commit(tx);

        DelosMvccStorageProvider recovered = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> table = recovered.openTable(tableMetadata);
        TxContext build = recovered.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_recovered_name", "name", false),
                row -> row.get(1),
                build.currentView());
        recovered.transactionCoordinator().commit(build);

        TxContext reader = recovered.transactionCoordinator().begin();
        assertEquals(List.of("1=[1, alpha]"), rows(nameIndex.lookup("alpha", reader.currentView())));
        assertEquals(List.of("2=[2, beta]"), rows(nameIndex.lookup("beta", reader.currentView())));
        recovered.transactionCoordinator().abort(reader);
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
