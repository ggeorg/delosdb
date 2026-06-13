package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;

import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Internal catalog-property contract for DelosDB table storage-provider metadata.
 *
 * <p>StorageProvider v0 still uses Derby heap storage. This class keeps the
 * provider identity as explicit persisted metadata on the heap conglomerate so
 * resolver smokes can distinguish a stored provider from a legacy/defaulted
 * descriptor.</p>
 */
@InternalApi
public final class StorageProviderCatalogMetadata {
    public static final String STORAGE_PROVIDER_PROPERTY = "delosdb.storage.provider";

    private StorageProviderCatalogMetadata() {
    }

    public static Properties withStorageProvider(Properties properties, String providerName) {
        Properties result = properties == null ? new Properties() : properties;
        result.setProperty(STORAGE_PROVIDER_PROPERTY, normalizeProviderName(providerName));
        return result;
    }

    public static String normalizeProviderName(String providerName) {
        if (providerName == null) {
            return TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME;
        }
        String normalized = Objects.requireNonNull(providerName, "providerName")
                .trim()
                .toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? TableDescriptor.DEFAULT_STORAGE_PROVIDER_NAME : normalized;
    }
}
