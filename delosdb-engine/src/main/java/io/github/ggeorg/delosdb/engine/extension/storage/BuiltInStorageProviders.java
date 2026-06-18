package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.List;

/**
 * Internal registry of storage providers built into the current engine.
 *
 * <p>The public provider name {@code heap} intentionally maps to the inherited
 * Derby-compatible heap/raw/access/WAL store. Naming the implementation as a
 * legacy Derby heap provider makes that identity explicit before the store is
 * extracted into its own module.</p>
 */
@InternalApi
public final class BuiltInStorageProviders {
    private BuiltInStorageProviders() {
    }

    public static StorageProvider heap() {
        return LegacyDerbyHeapStorageProvider.INSTANCE;
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
