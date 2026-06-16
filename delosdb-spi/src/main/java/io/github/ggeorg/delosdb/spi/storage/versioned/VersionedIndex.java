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

    /**
     * Returns lightweight statistics for an ordered range lookup. Implementations
     * must count candidates first, then visible matches after consulting the
     * authoritative table/version chain. A {@code null} bound means unbounded.
     */
    VersionedIndexStats statsRange(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            TxView view);

    /**
     * Looks up rows whose indexed value is inside the requested ordered range.
     * Implementations must recheck MVCC visibility and the visible indexed value
     * before returning rows. A {@code null} bound means unbounded.
     */
    VersionedScan<K, V> lookupRange(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            TxView view);

    /**
     * Looks up at most {@code maxRows} rows inside an ordered range. This is the
     * provider-owned counterpart of PostgreSQL-style bounded index scans for
     * {@code ORDER BY ... LIMIT}: the storage provider can stop producing
     * candidates once enough MVCC-visible rows have been found.
     */
    VersionedScan<K, V> lookupRange(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive,
            long maxRows,
            TxView view);
}
