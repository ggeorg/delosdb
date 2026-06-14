package io.github.ggeorg.delosdb.spi.type;

/**
 * Provider-neutral capabilities for DelosDB SQL type providers.
 */
public record TypeCapabilities(
        boolean builtInCatalogTypes,
        boolean scalarTypes,
        boolean jdbcMetadata,
        boolean comparableTypes
) {
    public TypeCapabilities {
        // Keep the constructor explicit so future flags can validate together.
    }

    /**
     * Capabilities for Derby's built-in SQL type catalog.
     */
    public static TypeCapabilities derbyBuiltIns() {
        return new TypeCapabilities(true, true, true, true);
    }
}
