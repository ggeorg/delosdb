package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.List;

/**
 * Controlled internal registration path for StorageProvider implementations.
 */
@InternalApi
public final class StorageProviderRegistry {
    private final ProviderRegistry<StorageProvider> providers = new ProviderRegistry<>(
            "storage provider",
            ExtensionType.STORAGE,
            StorageProvider::name,
            BuiltInExtensions::storageProviderDescriptor);

    private StorageProviderRegistry() {
    }

    public static StorageProviderRegistry empty() {
        return new StorageProviderRegistry();
    }

    public static StorageProviderRegistry builtIns() {
        StorageProviderRegistry registry = new StorageProviderRegistry();
        BuiltInStorageProviders.all().forEach(registry::registerBuiltIn);
        return registry;
    }

    public synchronized void registerEnabled(StorageProvider provider, String version) {
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(StorageProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized StorageProviderResolver resolver() {
        return new StorageProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<StorageProvider> providers() {
        return providers.providers();
    }

    private void registerBuiltIn(StorageProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_STORAGE_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        providers.registerBuiltIn(provider, defaultProvider);
    }
}
