package io.github.ggeorg.delosdb.spi.storage.versioned;

/** Extracts a provider-owned index key from a visible row value. */
@FunctionalInterface
public interface VersionedIndexKeyExtractor<V> {
    Object extract(V rowValue);
}
