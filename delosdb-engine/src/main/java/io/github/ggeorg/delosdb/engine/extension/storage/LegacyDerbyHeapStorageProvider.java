package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

/**
 * Built-in storage provider describing the inherited Derby-compatible heap/raw/access/WAL store.
 *
 * <p>This adapter is metadata-only in StorageProvider v0. It does not replace or
 * bypass the inherited Derby raw/access store. It gives DelosDB a stable
 * provider identity for the existing default table-storage behavior before the
 * legacy store is physically extracted into its own module.</p>
 */
@InternalApi
final class LegacyDerbyHeapStorageProvider implements StorageProvider {
    static final LegacyDerbyHeapStorageProvider INSTANCE = new LegacyDerbyHeapStorageProvider();

    private LegacyDerbyHeapStorageProvider() {
    }

    @Override
    public String name() {
        return BuiltInStorageProviders.defaultProviderName();
    }

    @Override
    public StorageCapabilities capabilities() {
        return StorageCapabilities.heap();
    }
}
