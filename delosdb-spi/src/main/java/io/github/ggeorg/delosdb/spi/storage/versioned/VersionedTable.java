package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.List;
import java.util.Optional;

/**
 * Minimal table contract for an experimental versioned storage provider.
 */
public interface VersionedTable<K, V> {
    VersionedTableMetadata metadata();

    Optional<V> read(K key, TxView view);

    VersionedScan<K, V> openScan(TxView view);

    List<VersionedIndexMetadata> listIndexes();

    VersionedIndex<K, V> createIndex(
            VersionedIndexMetadata metadata,
            VersionedIndexKeyExtractor<V> extractor,
            TxView buildView);

    VersionedIndex<K, V> openIndex(String indexName);

    void insert(K key, V value, TxContext transaction);

    void update(K key, V value, TxContext transaction);

    void delete(K key, TxContext transaction);

    VersionedTableStats stats(TxView view);
}
