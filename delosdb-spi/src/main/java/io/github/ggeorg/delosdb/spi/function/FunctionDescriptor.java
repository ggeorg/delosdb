package io.github.ggeorg.delosdb.spi.function;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Provider-neutral metadata for a SQL function known to DelosDB.
 */
public record FunctionDescriptor(
        String providerName,
        String schemaName,
        String functionName,
        String returnType,
        List<String> parameterTypes,
        String externalName
) {
    public FunctionDescriptor {
        providerName = normalizeProviderName(providerName);
        schemaName = normalizeIdentifier(schemaName, "schemaName");
        functionName = normalizeIdentifier(functionName, "functionName");
        returnType = normalizeType(returnType, "returnType");
        parameterTypes = List.copyOf(Objects.requireNonNull(parameterTypes, "parameterTypes")
                .stream()
                .map(parameterType -> normalizeType(parameterType, "parameterType"))
                .toList());
        externalName = normalizeOptionalExternalName(externalName);
    }

    public static FunctionDescriptor of(
            String providerName,
            String schemaName,
            String functionName,
            String returnType,
            List<String> parameterTypes) {
        return new FunctionDescriptor(providerName, schemaName, functionName, returnType, parameterTypes, "");
    }

    public static FunctionDescriptor of(
            String providerName,
            String schemaName,
            String functionName,
            String returnType,
            List<String> parameterTypes,
            String externalName) {
        return new FunctionDescriptor(providerName, schemaName, functionName, returnType, parameterTypes, externalName);
    }

    public String qualifiedName() {
        return schemaName + "." + functionName;
    }

    public boolean hasExternalName() {
        return !externalName.isEmpty();
    }

    private static String normalizeProviderName(String providerName) {
        String normalized = Objects.requireNonNull(providerName, "providerName")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Function provider name must not be blank");
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

    private static String normalizeType(String type, String label) {
        String normalized = Objects.requireNonNull(type, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptionalExternalName(String externalName) {
        if (externalName == null) {
            return "";
        }
        return externalName.trim();
    }
}
