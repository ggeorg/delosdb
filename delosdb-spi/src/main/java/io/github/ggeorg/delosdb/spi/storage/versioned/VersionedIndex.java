package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Provider-owned index over a versioned table.
 *
 * <p>The index returns candidate row identifiers and validates visibility
 * against the table/version chain before returning rows. This mirrors the
 * PostgreSQL design point that index entries are not independently visible;
 * heap/version visibility remains authoritative.</p>
 */
public interface VersionedIndex<K, V> {
    VersionedIndexMetadata metadata();

    VersionedScan<K, V> lookup(Object indexKey, TxView view);
}
