package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.Objects;

/** A logical row returned by a versioned table scan. */
public record VersionedRow<K, V>(K key, V value) {
    public VersionedRow {
        key = Objects.requireNonNull(key, "key");
        value = Objects.requireNonNull(value, "value");
    }
}
