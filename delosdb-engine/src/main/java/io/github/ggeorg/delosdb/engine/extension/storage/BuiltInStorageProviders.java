package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.List;

/**
 * Internal registry of storage providers built into the current engine.
 */
@InternalApi
public final class BuiltInStorageProviders {
    private BuiltInStorageProviders() {
    }

    public static StorageProvider heap() {
        return BuiltInHeapStorageProvider.INSTANCE;
    }

    public static String defaultProviderName() {
        return BuiltInExtensions.DEFAULT_STORAGE_PROVIDER;
    }

    public static StorageProvider defaultProvider() {
        return heap();
    }

    public static List<StorageProvider> all() {
        return List.of(heap());
    }
}
