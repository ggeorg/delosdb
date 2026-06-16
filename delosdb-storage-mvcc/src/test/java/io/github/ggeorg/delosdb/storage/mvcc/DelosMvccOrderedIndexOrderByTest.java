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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 15 provider-owned ordered-index proofs.
 *
 * <p>This is the storage-side counterpart of SQL {@code ORDER BY}. The ordered
 * index can produce a full ordered candidate stream, but every row is still
 * rechecked against the authoritative MVCC version chain before it is returned.</p>
 */
public final class DelosMvccOrderedIndexOrderByTest {
    @Test
    public void testFullOrderedIndexScanProducesAscendingOrder() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "order_items");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "gamma"), insert);
        table.insert(2L, List.of(2, "alpha"), insert);
        table.insert(3L, List.of(3, "beta"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_order_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of("2=[2, alpha]", "3=[3, beta]", "1=[1, gamma]"),
                rows(nameIndex.lookupRange(null, true, null, true, reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testOrderedIndexScanRechecksSnapshotVisibilityAfterUpdateAndDelete() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "order_visibility");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "gamma"), insert);
        table.insert(2L, List.of(2, "alpha"), insert);
        table.insert(3L, List.of(3, "beta"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_visibility_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext oldReader = provider.transactionCoordinator().begin();

        TxContext update = provider.transactionCoordinator().begin();
        table.update(1L, List.of(1, "aardvark"), update);
        table.delete(3L, update);
        provider.transactionCoordinator().commit(update);

        assertEquals(List.of("2=[2, alpha]", "3=[3, beta]", "1=[1, gamma]"),
                rows(nameIndex.lookupRange(null, true, null, true, oldReader.currentView())));

        TxContext newReader = provider.transactionCoordinator().begin();
        assertEquals(List.of("1=[1, aardvark]", "2=[2, alpha]"),
                rows(nameIndex.lookupRange(null, true, null, true, newReader.currentView())));

        provider.transactionCoordinator().abort(oldReader);
        provider.transactionCoordinator().abort(newReader);
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
