package io.github.ggeorg.delosdb.spi.type;

import java.util.Locale;
import java.util.Objects;

/**
 * Provider-neutral metadata for a SQL type known to DelosDB.
 */
public record TypeDescriptor(
        String providerName,
        String typeName,
        String jdbcTypeName,
        String javaTypeName,
        boolean nullable,
        boolean comparable
) {
    public TypeDescriptor {
        providerName = normalizeProviderName(providerName);
        typeName = normalizeIdentifier(typeName, "typeName");
        jdbcTypeName = normalizeIdentifier(jdbcTypeName, "jdbcTypeName");
        javaTypeName = normalizeJavaTypeName(javaTypeName);
    }

    public static TypeDescriptor scalar(
            String providerName,
            String typeName,
            String jdbcTypeName,
            String javaTypeName,
            boolean nullable,
            boolean comparable) {
        return new TypeDescriptor(providerName, typeName, jdbcTypeName, javaTypeName, nullable, comparable);
    }

    private static String normalizeProviderName(String providerName) {
        String normalized = Objects.requireNonNull(providerName, "providerName")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Type provider name must not be blank");
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

    private static String normalizeJavaTypeName(String javaTypeName) {
        String normalized = Objects.requireNonNull(javaTypeName, "javaTypeName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("javaTypeName must not be blank");
        }
        return normalized;
    }
}
