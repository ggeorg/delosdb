package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB storage-provider descriptors to engine
 * implementation adapters.
 */
@InternalApi
public final class StorageProviderResolver {
    private final ProviderResolver<StorageProvider> providers;

    StorageProviderResolver(ProviderResolver<StorageProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public StorageProviderResolver(ExtensionRegistry registry, List<StorageProvider> providers) {
        this(new ProviderResolver<>(
                "storage provider",
                ExtensionType.STORAGE,
                registry,
                providers,
                StorageProvider::name));
    }

    public static StorageProviderResolver builtIns(ExtensionRegistry registry) {
        return new StorageProviderResolver(registry, BuiltInStorageProviders.all());
    }

    public static StorageProviderResolver builtIns() {
        return StorageProviderRegistry.builtIns().resolver();
    }

    public Optional<StorageProvider> findEnabled(String name) {
        return providers.findEnabled(name);
    }

    public StorageProvider requireEnabled(String name) {
        return providers.requireEnabled(name);
    }

    public StorageProvider requireDefault() {
        return requireEnabled(BuiltInStorageProviders.defaultProviderName());
    }

    public List<StorageProvider> providers() {
        return providers.providers();
    }
}
