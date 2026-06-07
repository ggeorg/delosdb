package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Provider-neutral metadata describing an index known to DelosDB.
 */
@ExperimentalSpi("Initial provider-neutral index metadata shape.")
public record IndexMetadata(
        String providerName,
        String indexName,
        List<String> keyColumns,
        Map<String, String> properties
) {
    public IndexMetadata {
        providerName = requireText(providerName, "providerName");
        indexName = requireText(indexName, "indexName");
        keyColumns = List.copyOf(Objects.requireNonNull(keyColumns, "keyColumns"));
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        if (keyColumns.isEmpty()) {
            throw new IllegalArgumentException("keyColumns must not be empty");
        }
        keyColumns.forEach(column -> requireText(column, "keyColumns element"));
    }

    public static IndexMetadata of(String providerName, String indexName, List<String> keyColumns) {
        return new IndexMetadata(providerName, indexName, keyColumns, Map.of());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
