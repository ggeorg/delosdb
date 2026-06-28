package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.Locale;
import java.util.Objects;

/** Stable identity for a table owned by a versioned storage provider. */
public record VersionedTableMetadata(String schemaName, String tableName) {
    public VersionedTableMetadata {
        schemaName = normalizeIdentifier(schemaName, "schemaName");
        tableName = normalizeIdentifier(tableName, "tableName");
    }

    public String qualifiedName() {
        return schemaName + "." + tableName;
    }

    private static String normalizeIdentifier(String value, String fieldName) {
        String trimmed = Objects.requireNonNull(value, fieldName).trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
