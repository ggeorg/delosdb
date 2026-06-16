package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * prove that the MVCC model is now usable through the narrow DelosDB
 * VersionedStorageProvider SPI, still without SQL, Derby heap, WAL, or index
 * integration.
 */
public final class VersionedStorageProviderSpiTest {
    @Test
    public void testProviderMetadataAndCapabilities() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();

        assertEquals("delos_mvcc", provider.name());
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.SNAPSHOT_VISIBILITY));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.TABLE_SCAN));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.MANUAL_CLEANUP));
        assertTrue(provider.capabilities().supports(VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE));
    }

    @Test
    public void testCreateOpenAndScanThroughVersionedStorageSpi() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "account");
        VersionedTable<Integer, String> table = provider.createTable(metadata);

        assertEquals("APP.ACCOUNT", table.metadata().qualifiedName());
        assertThrows(IllegalStateException.class, () -> provider.createTable(metadata));
        assertEquals(table, provider.<Integer, String>openTable(metadata));

        MvccTransactionManager txManager = new MvccTransactionManager();
        MvccTransaction writer = txManager.begin();
        DelosMvccTxContext writerContext = context(txManager, writer);
        table.insert(1, "alpha", writerContext);
        table.insert(2, "beta", writerContext);
        txManager.commit(writer);

        MvccTransaction reader = txManager.begin();
        DelosMvccTxView view = view(txManager, reader);
        assertEquals(Optional.of("alpha"), table.read(1, view));
        assertEquals(List.of("1=alpha", "2=beta"), rows(table.openScan(view)));

        VersionedTableStats stats = table.stats(view);
        assertEquals(2, stats.logicalRowCount());
        assertEquals(2, stats.visibleRowCount());
        assertEquals(2, stats.physicalVersionCount());
    }

    @Test
    public void testSpiPreservesSnapshotVisibilityAcrossUpdate() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "items"));
        MvccTransactionManager txManager = new MvccTransactionManager();

        MvccTransaction inserter = txManager.begin();
        table.insert(1, "v1", context(txManager, inserter));
        txManager.commit(inserter);

        MvccTransaction oldReader = txManager.begin();
        DelosMvccTxView oldView = view(txManager, oldReader);

        MvccTransaction updater = txManager.begin();
        table.update(1, "v2", context(txManager, updater));
        txManager.commit(updater);

        assertEquals(Optional.of("v1"), table.read(1, oldView));

        MvccTransaction newReader = txManager.begin();
        DelosMvccTxView newView = view(txManager, newReader);
        assertEquals(Optional.of("v2"), table.read(1, newView));
        assertEquals(List.of("1=v2"), rows(table.openScan(newView)));
    }

    @Test
    public void testSpiRejectsNonMvccContext() {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedTable<Integer, String> table = provider.createTable(new VersionedTableMetadata("app", "bad_context"));

        assertThrows(IllegalArgumentException.class, () -> table.insert(1, "bad", new FakeTxContext()));
    }

    private static DelosMvccTxContext context(MvccTransactionManager txManager, MvccTransaction transaction) {
        return new DelosMvccTxContext(
                transaction,
                txManager.snapshot(transaction),
                txManager,
                txManager.oldestActiveVisibleThrough());
    }

    private static DelosMvccTxView view(MvccTransactionManager txManager, MvccTransaction transaction) {
        return new DelosMvccTxView(
                txManager.snapshot(transaction),
                txManager,
                txManager.oldestActiveVisibleThrough());
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

    private static final class FakeTxContext implements io.github.ggeorg.delosdb.spi.storage.versioned.TxContext {
        @Override
        public long transactionId() {
            return 1L;
        }

        @Override
        public io.github.ggeorg.delosdb.spi.storage.versioned.TxView currentView() {
            return new io.github.ggeorg.delosdb.spi.storage.versioned.TxView() {
                @Override
                public boolean isVisible(long createdByTransactionId, long deletedByTransactionId) {
                    return true;
                }

                @Override
                public long oldestVisibleTransaction() {
                    return 0L;
                }
            };
        }
    }
}
