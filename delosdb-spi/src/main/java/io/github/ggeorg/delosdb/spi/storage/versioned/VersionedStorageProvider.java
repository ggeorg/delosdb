package io.github.ggeorg.delosdb.spi.storage.versioned;

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

    <K, V> VersionedTable<K, V> createTable(VersionedTableMetadata metadata);

    <K, V> VersionedTable<K, V> openTable(VersionedTableMetadata metadata);
}
