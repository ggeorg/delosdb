package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider-boundary tests for the experimental MVCC storage module. These tests
 * prove that the MVCC model is usable through the narrow DelosDB
 * VersionedStorageProvider SPI, still without SQL, Derby heap, WAL, or index
 * integration.
 */
public final class VersionedStorageProviderSpiTest {
    @Test
    public void testProviderMetadataCapabilitiesAndTableCatalog() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();

        assertEquals("delos_mvcc", provider.name());
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.SNAPSHOT_VISIBILITY));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.TABLE_SCAN));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.MANUAL_CLEANUP));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE));
        assertEquals(List.of(), provider.listTables());

        VersionedTableMetadata first = new VersionedTableMetadata("app", "account");
        VersionedTableMetadata second = new VersionedTableMetadata("app", "ledger");
        provider.createTable(first);
        provider.createTable(second);

        assertEquals(List.of(first, second), provider.listTables());
        assertThrows(UnsupportedOperationException.class, () -> provider.listTables().add(first));
    }

    @Test
    public void testCreateOpenAndScanThroughVersionedStorageSpi() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        DelosMvccTransactionCoordinator transactions = new DelosMvccTransactionCoordinator();
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "account");
        VersionedTable<Integer, String> table = provider.createTable(metadata);

        assertEquals("APP.ACCOUNT", table.metadata().qualifiedName());
        assertThrows(IllegalStateException.class, () -> provider.createTable(metadata));
        assertThrows(IllegalStateException.class, () -> provider.openTable(new VersionedTableMetadata("app", "missing")));
        assertEquals(table, provider.<Integer, String>openTable(metadata));

        DelosMvccTxContext writer = transactions.begin();
        table.insert(1, "alpha", writer);
        table.insert(2, "beta", writer);
        transactions.commit(writer);

        DelosMvccTxContext reader = transactions.begin();
        DelosMvccTxView view = transactions.view(reader);
        assertEquals(Optional.of("alpha"), table.read(1, view));
        assertEquals(List.of("1=alpha", "2=beta"), rows(table.openScan(view)));

        VersionedTableStats stats = table.stats(view);
        assertEquals(2, stats.logicalRowCount());
        assertEquals(2, stats.visibleRowCount());
        assertEquals(2, stats.physicalVersionCount());
        transactions.abort(reader);
    }

    @Test
    public void testRollbackInsertIsInvisibleThroughSpi() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        DelosMvccTransactionCoordinator transactions = new DelosMvccTransactionCoordinator();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "rollback_insert"));

        DelosMvccTxContext writer = transactions.begin();
        table.insert(10, "draft", writer);
        transactions.abort(writer);

        DelosMvccTxContext reader = transactions.begin();
        DelosMvccTxView view = transactions.view(reader);
        assertEquals(Optional.empty(), table.read(10, view));
        assertEquals(List.of(), rows(table.openScan(view)));
        transactions.abort(reader);
    }

    @Test
    public void testSpiPreservesSnapshotVisibilityAcrossUpdate() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        DelosMvccTransactionCoordinator transactions = new DelosMvccTransactionCoordinator();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "items"));

        DelosMvccTxContext inserter = transactions.begin();
        table.insert(1, "v1", inserter);
        transactions.commit(inserter);

        DelosMvccTxContext oldReader = transactions.begin();
        DelosMvccTxView oldView = transactions.view(oldReader);

        DelosMvccTxContext updater = transactions.begin();
        table.update(1, "v2", updater);
        transactions.commit(updater);

        assertEquals(Optional.of("v1"), table.read(1, oldView));

        DelosMvccTxContext newReader = transactions.begin();
        DelosMvccTxView newView = transactions.view(newReader);
        assertEquals(Optional.of("v2"), table.read(1, newView));
        assertEquals(List.of("1=v2"), rows(table.openScan(newView)));
        transactions.abort(oldReader);
        transactions.abort(newReader);
    }

    @Test
    public void testRollbackDeleteDoesNotHideCommittedRowThroughSpi() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        DelosMvccTransactionCoordinator transactions = new DelosMvccTransactionCoordinator();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "rollback_delete"));

        DelosMvccTxContext inserter = transactions.begin();
        table.insert(1, "still-visible", inserter);
        transactions.commit(inserter);

        DelosMvccTxContext deleter = transactions.begin();
        table.delete(1, deleter);
        transactions.abort(deleter);

        DelosMvccTxContext reader = transactions.begin();
        DelosMvccTxView view = transactions.view(reader);
        assertEquals(Optional.of("still-visible"), table.read(1, view));
        assertEquals(List.of("1=still-visible"), rows(table.openScan(view)));
        transactions.abort(reader);
    }

    @Test
    public void testSpiRejectsNonMvccContext() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "bad_context"));

        assertThrows(IllegalArgumentException.class, () -> table.insert(1, "bad", new FakeTxContext()));
        assertThrows(IllegalArgumentException.class, () -> table.read(1, new FakeTxView()));
    }

    private static <K, V> List<String> rows(VersionedScan<K, V> scan) {
        List<String> values = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                VersionedRow<K, V> row = scan.row();
                values.add(row.key() + "=" + row.value());
            }
        }
        return values;
    }

    private static final class FakeTxContext implements TxContext {
        @Override
        public long transactionId() {
            return 1L;
        }

        @Override
        public TxView currentView() {
            return new FakeTxView();
        }
    }

    private static final class FakeTxView implements TxView {
        @Override
        public boolean isVisible(long createdByTransactionId, long deletedByTransactionId) {
            return true;
        }

        @Override
        public long oldestVisibleTransaction() {
            return 0L;
        }
    }
}
