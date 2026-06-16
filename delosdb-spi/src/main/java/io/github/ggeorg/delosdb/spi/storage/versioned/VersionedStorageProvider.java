package io.github.ggeorg.delosdb.spi.storage.versioned;

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
     * Provider-local transaction coordinator used by prototype SQL bridges and
     * provider tests. This is not yet wired to Derby transaction commit/rollback.
     */
    VersionedTransactionCoordinator transactionCoordinator();

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
