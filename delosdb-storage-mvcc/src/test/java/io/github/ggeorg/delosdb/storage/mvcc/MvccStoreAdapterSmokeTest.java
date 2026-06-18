package io.github.ggeorg.delosdb.storage.mvcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/** MVCC-11 store-adapter smoke: opt-in provider path before SQL integration. */
final class MvccStoreAdapterSmokeTest {
    private static final VersionedTableMetadata TABLE = new VersionedTableMetadata("app", "store_adapter");

    @TempDir
    Path tempDir;

    @Test
    void adapterRequiresExplicitMvccProviderOptIn() {
        Properties defaults = new Properties();

        assertFalse(DelosMvccStoreAdapter.isEnabled(defaults));
        assertThrows(IllegalStateException.class, () -> DelosMvccStoreAdapter.open(defaults));

        Properties wrongProvider = properties("heap", tempDir);
        assertFalse(DelosMvccStoreAdapter.isEnabled(wrongProvider));
        assertThrows(IllegalStateException.class, () -> DelosMvccStoreAdapter.open(wrongProvider));
    }

    @Test
    void adapterRequiresDurableStorageDirectory() {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, DelosMvccStoreAdapter.PROVIDER_MVCC);

        assertTrue(DelosMvccStoreAdapter.isEnabled(properties));
        assertThrows(IllegalArgumentException.class, () -> DelosMvccStoreAdapter.open(properties));
    }

    @Test
    void createInsertReadDeleteAndReopenThroughOptInAdapter() {
        Properties properties = properties(DelosMvccStoreAdapter.PROVIDER_MVCC, tempDir.resolve("mvcc-store"));

        VersionedStorageProvider provider = DelosMvccStoreAdapter.open(properties);
        assertEquals(DelosMvccStorageProvider.PROVIDER_NAME, provider.name());
        assertTrue(provider.capabilities().supports("page-backed-table-store"));
        VersionedTable<Long, List<Object>> table = provider.createTable(TABLE);

        TxContext insert = provider.transactionCoordinator().begin();
        table.insert(1L, row("alpha", 10), insert);
        provider.transactionCoordinator().commit(insert);

        assertEquals(Optional.of(row("alpha", 10)), readFresh(provider, table, 1L));
        assertEquals(List.of("1=[alpha, 10]"), scanFresh(provider, table));

        VersionedStorageProvider reopenedAfterInsert = DelosMvccStoreAdapter.open(properties);
        VersionedTable<Long, List<Object>> reopenedTable = reopenedAfterInsert.createTable(TABLE);
        assertEquals(Optional.of(row("alpha", 10)), readFresh(reopenedAfterInsert, reopenedTable, 1L));

        TxContext update = reopenedAfterInsert.transactionCoordinator().begin();
        reopenedTable.update(1L, row("beta", 20), update);
        reopenedAfterInsert.transactionCoordinator().commit(update);

        VersionedStorageProvider reopenedAfterUpdate = DelosMvccStoreAdapter.open(properties);
        VersionedTable<Long, List<Object>> updatedTable = reopenedAfterUpdate.createTable(TABLE);
        assertEquals(Optional.of(row("beta", 20)), readFresh(reopenedAfterUpdate, updatedTable, 1L));

        TxContext delete = reopenedAfterUpdate.transactionCoordinator().begin();
        updatedTable.delete(1L, delete);
        reopenedAfterUpdate.transactionCoordinator().commit(delete);

        VersionedStorageProvider reopenedAfterDelete = DelosMvccStoreAdapter.open(properties);
        VersionedTable<Long, List<Object>> deletedTable = reopenedAfterDelete.createTable(TABLE);
        assertEquals(Optional.empty(), readFresh(reopenedAfterDelete, deletedTable, 1L));
        assertEquals(List.of(), scanFresh(reopenedAfterDelete, deletedTable));
    }

    private static Properties properties(String providerName, Path storageDirectory) {
        Properties properties = new Properties();
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_PROVIDER_PROPERTY, providerName);
        properties.setProperty(DelosMvccStoreAdapter.STORAGE_DIRECTORY_PROPERTY, storageDirectory.toString());
        return properties;
    }

    private static List<Object> row(String name, int value) {
        return List.of(name, value);
    }

    private static Optional<List<Object>> readFresh(
            VersionedStorageProvider provider,
            VersionedTable<Long, List<Object>> table,
            long key) {
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();
        TxContext reader = transactions.begin();
        try {
            return table.read(key, reader.currentView());
        } finally {
            transactions.abort(reader);
        }
    }

    private static List<String> scanFresh(
            VersionedStorageProvider provider,
            VersionedTable<Long, List<Object>> table) {
        VersionedTransactionCoordinator transactions = provider.transactionCoordinator();
        TxContext reader = transactions.begin();
        try {
            List<String> rows = new ArrayList<>();
            try (VersionedScan<Long, List<Object>> scan = table.openScan(reader.currentView())) {
                while (scan.next()) {
                    VersionedRow<Long, List<Object>> row = scan.row();
                    rows.add(row.key() + "=" + row.value());
                }
            }
            return rows;
        } finally {
            transactions.abort(reader);
        }
    }
}
