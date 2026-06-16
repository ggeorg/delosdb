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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 16 provider-owned bounded ordered-index proofs.
 *
 * <p>PostgreSQL can stop an ordered index scan early for {@code ORDER BY ... LIMIT}.
 * This prototype mirrors that storage behavior inside the delos_mvcc provider:
 * index entries remain candidates, visibility is still rechecked against the
 * MVCC version chain, and the scan stops after enough visible rows are found.</p>
 */
public final class DelosMvccOrderedIndexLimitTest {
    @Test
    public void testBoundedOrderedIndexScanStopsAfterVisibleLimit() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "limit_items");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "delta"), insert);
        table.insert(2L, List.of(2, "alpha"), insert);
        table.insert(3L, List.of(3, "charlie"), insert);
        table.insert(4L, List.of(4, "bravo"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_limit_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of("2=[2, alpha]", "4=[4, bravo]"),
                rows(nameIndex.lookupRange(null, true, null, true, 2L, reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testBoundedOrderedIndexScanSkipsStaleCandidatesBeforeCountingLimit() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "limit_visibility");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "alpha"), insert);
        table.insert(2L, List.of(2, "bravo"), insert);
        table.insert(3L, List.of(3, "charlie"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_limit_visibility_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext update = provider.transactionCoordinator().begin();
        table.update(1L, List.of(1, "zulu"), update);
        table.delete(2L, update);
        provider.transactionCoordinator().commit(update);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of("3=[3, charlie]", "1=[1, zulu]"),
                rows(nameIndex.lookupRange(null, true, null, true, 2L, reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testZeroLimitReturnsEmptyScan() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "limit_zero");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, List.of(1, "alpha"), insert);
        provider.transactionCoordinator().commit(insert);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_limit_zero_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext reader = provider.transactionCoordinator().begin();
        assertEquals(List.of(), rows(nameIndex.lookupRange(null, true, null, true, 0L, reader.currentView())));
        provider.transactionCoordinator().abort(reader);
    }

    @Test
    public void testNegativeLimitIsRejected() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata tableMetadata = new VersionedTableMetadata("app", "limit_negative");
        VersionedTable<Long, List<Object>> table = provider.createTable(tableMetadata);

        TxContext build = provider.transactionCoordinator().begin();
        VersionedIndex<Long, List<Object>> nameIndex = table.createIndex(
                new VersionedIndexMetadata(tableMetadata, "idx_limit_negative_name", "name", false),
                row -> row.get(1),
                build.currentView());
        provider.transactionCoordinator().commit(build);

        TxContext reader = provider.transactionCoordinator().begin();
        assertThrows(IllegalArgumentException.class,
                () -> nameIndex.lookupRange(null, true, null, true, -1L, reader.currentView()));
        provider.transactionCoordinator().abort(reader);
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
