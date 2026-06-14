package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

/**
 * Built-in storage provider describing Derby's current heap table storage.
 *
 * <p>This adapter is metadata-only in StorageProvider v0. It does not replace or
 * bypass Derby raw store. It gives DelosDB a stable provider identity for the
 * existing default table-storage behavior.</p>
 */
@InternalApi
final class BuiltInHeapStorageProvider implements StorageProvider {
    static final BuiltInHeapStorageProvider INSTANCE = new BuiltInHeapStorageProvider();

    private BuiltInHeapStorageProvider() {
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
