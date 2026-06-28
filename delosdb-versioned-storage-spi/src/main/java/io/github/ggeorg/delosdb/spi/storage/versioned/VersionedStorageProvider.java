package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.nio.file.Path;
import java.util.List;

/**
 * Narrow SPI for an opt-in storage implementation that owns row versions and
 * snapshot visibility.
 *
 * <p>This is deliberately separate from Derby-compatible heap storage. A
 * provider behind this SPI may use a non-Derby row format, but it must be
 * selected explicitly by DelosDB metadata or SQL syntax.</p>
 */
public interface VersionedStorageProvider {
    String name();

    VersionedStorageCapabilities capabilities();

    /**
     * Provider-local transaction coordinator used by prototype SQL bridges,
     * provider tests, and current Derby transaction lifecycle hooks.
     */
    VersionedTransactionCoordinator transactionCoordinator();

    /**
     * Opens a provider instance scoped to a Derby database directory. Providers
     * that do not keep database-local durable state may return {@code this}.
     */
    default VersionedStorageProvider openForDatabase(Path databaseDirectory) {
        return this;
    }

    /**
     * Returns a stable snapshot of tables currently owned by this provider.
     *
     * <p>The list is provider-local metadata only. It does not imply that SQL
     * execution is wired to the table yet.</p>
     */
    List<VersionedTableMetadata> listTables();

    <K, V> VersionedTable<K, V> createTable(VersionedTableMetadata metadata);

    <K, V> VersionedTable<K, V> openTable(VersionedTableMetadata metadata);
}
