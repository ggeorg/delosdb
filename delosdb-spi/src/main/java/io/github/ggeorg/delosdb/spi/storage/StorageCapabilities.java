package io.github.ggeorg.delosdb.spi.storage;

import java.util.Objects;

/**
 * Provider-neutral capabilities for DelosDB table storage providers.
 */
public record StorageCapabilities(
        boolean rowStore,
        boolean transactional,
        boolean derbyHeapCompatible
) {
    public StorageCapabilities {
        // Keep the constructor explicit so future flags can validate together.
    }

    /**
     * Capabilities for the built-in Derby-compatible heap storage provider.
     */
    public static StorageCapabilities heap() {
        return new StorageCapabilities(true, true, true);
    }

    public static StorageCapabilities require(StorageCapabilities capabilities) {
        return Objects.requireNonNull(capabilities, "capabilities");
    }
}
