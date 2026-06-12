package io.github.ggeorg.delosdb.spi.storage;

import java.util.Locale;
import java.util.Objects;

/**
 * Provider-neutral table storage metadata passed to StorageProvider hooks.
 */
public record TableStorageMetadata(
        String providerName,
        String schemaName,
        String tableName
) {
    public TableStorageMetadata {
        providerName = normalizeProviderName(providerName);
        schemaName = normalizeIdentifier(schemaName, "schemaName");
        tableName = normalizeIdentifier(tableName, "tableName");
    }

    public static TableStorageMetadata of(String providerName, String schemaName, String tableName) {
        return new TableStorageMetadata(providerName, schemaName, tableName);
    }

    private static String normalizeProviderName(String providerName) {
        String normalized = Objects.requireNonNull(providerName, "providerName")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Storage provider name must not be blank");
        }
        return normalized;
    }

    private static String normalizeIdentifier(String identifier, String label) {
        String normalized = Objects.requireNonNull(identifier, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
