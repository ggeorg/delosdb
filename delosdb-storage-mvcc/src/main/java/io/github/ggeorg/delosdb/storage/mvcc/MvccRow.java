package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * One logical row returned by an MVCC table scan.
 *
 * <p>The key is the stable logical row identifier. The
 * value is the visible row payload for the scan snapshot.</p>
 */
public record MvccRow<K, V>(K key, V value) {
    public MvccRow {
        if (key == null) {
            throw new IllegalArgumentException("row key must not be null");
        }
        if (value == null) {
            throw new IllegalArgumentException("row value must not be null");
        }
    }
}
