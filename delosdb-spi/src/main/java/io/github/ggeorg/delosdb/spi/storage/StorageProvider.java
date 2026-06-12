package io.github.ggeorg.delosdb.spi.storage;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

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
     * Describes the capabilities this provider offers for a specific table.
     */
    StorageCapabilities capabilities(TableStorageMetadata metadata);
}
