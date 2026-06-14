package io.github.ggeorg.delosdb.spi.storage;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.Objects;

/**
 * Experimental DelosDB contract for table storage provider implementations.
 *
 * <p>StorageProvider v0 is deliberately metadata-only. It establishes stable
 * provider identity and capability reporting without exposing Derby raw store,
 * page, transaction, conglomerate, or container internals.</p>
 */
@ExperimentalSpi("Initial storage provider contract; physical storage hooks are intentionally deferred.")
public interface StorageProvider {
    /**
     * Returns the stable provider name used by DelosDB metadata.
     *
     * <p>The built-in/default provider is {@code heap}. Future provider names
     * should be lowercase, registered explicitly, and stable across releases of
     * the same provider family.</p>
     */
    String name();

    /**
     * Describes provider-level capabilities that do not depend on a specific table.
     *
     * <p>This is the capability method used when DelosDB registers the provider
     * itself. It avoids manufacturing table metadata for a non-existent table
     * just to describe a provider.</p>
     */
    StorageCapabilities capabilities();

    /**
     * Describes the capabilities this provider offers for a specific table.
     *
     * <p>StorageProvider v0 providers normally report the same capabilities for
     * every table. Future physical storage providers may override this method
     * when capabilities depend on table-level metadata.</p>
     */
    default StorageCapabilities capabilities(TableStorageMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return capabilities();
    }
}
