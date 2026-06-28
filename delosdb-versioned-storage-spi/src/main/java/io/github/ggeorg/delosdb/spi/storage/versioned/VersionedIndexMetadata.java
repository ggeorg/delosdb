package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.Locale;
import java.util.Objects;

/** Stable identity for an index owned by a versioned storage provider. */
public record VersionedIndexMetadata(
        VersionedTableMetadata table,
        String indexName,
        String indexedColumnName,
        boolean unique) {
    public VersionedIndexMetadata {
        table = Objects.requireNonNull(table, "table");
        indexName = normalizeIdentifier(indexName, "indexName");
        indexedColumnName = normalizeIdentifier(indexedColumnName, "indexedColumnName");
    }

    public String qualifiedName() {
        return table.qualifiedName() + "." + indexName;
    }

    private static String normalizeIdentifier(String value, String fieldName) {
        String trimmed = Objects.requireNonNull(value, fieldName).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
