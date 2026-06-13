package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.util.Objects;

/**
 * Diagnostic storage-provider metadata with catalog/descriptor source information.
 */
@InternalApi
public record TableStorageCatalogMetadata(
        TableStorageMetadata metadata,
        Source source,
        String catalogProviderName
) {
    public TableStorageCatalogMetadata {
        metadata = Objects.requireNonNull(metadata, "metadata");
        source = Objects.requireNonNull(source, "source");
    }

    public enum Source {
        DESCRIPTOR
    }

    /**
     * Backward-compatible accessor for the short-lived catalog-integrity smoke.
     */
    public String storedProviderName() {
        return catalogProviderName;
    }

    public String describe() {
        return metadata.schemaName()
                + "."
                + metadata.tableName()
                + " storageProvider="
                + metadata.providerName()
                + " source="
                + source.name().toLowerCase(java.util.Locale.ROOT);
    }
}
