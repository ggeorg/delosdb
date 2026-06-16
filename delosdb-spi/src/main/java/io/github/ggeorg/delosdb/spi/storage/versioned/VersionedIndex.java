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

    /**
     * Returns lightweight statistics for a lookup key under the supplied
     * snapshot. Implementations must count visible matches only after
     * consulting the authoritative table/version chain.
     */
    VersionedIndexStats stats(Object indexKey, TxView view);

    VersionedScan<K, V> lookup(Object indexKey, TxView view);
}
