package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB storage-provider descriptors to engine
 * implementation adapters.
 */
@InternalApi
public final class StorageProviderResolver {
    private final ExtensionRegistry registry;
    private final Map<String, StorageProvider> providersByName;

    public StorageProviderResolver(ExtensionRegistry registry, List<StorageProvider> providers) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(providers, "providers");
        Map<String, StorageProvider> providersByName = new LinkedHashMap<>();
        for (StorageProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String name = ExtensionDescriptor.normalizeName(provider.name());
            StorageProvider previous = providersByName.put(name, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate storage provider: " + name);
            }
        }
        this.providersByName = Map.copyOf(providersByName);
    }

    public static StorageProviderResolver builtIns(ExtensionRegistry registry) {
        return new StorageProviderResolver(registry, BuiltInStorageProviders.all());
    }

    public static StorageProviderResolver builtIns() {
        return StorageProviderRegistry.builtIns().resolver();
    }

    public Optional<StorageProvider> findEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        return registry.find(ExtensionType.STORAGE, normalizedName)
                .filter(descriptor -> descriptor.state() == ExtensionState.ENABLED)
                .flatMap(descriptor -> Optional.ofNullable(providersByName.get(descriptor.name())));
    }

    public StorageProvider requireEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        ExtensionDescriptor descriptor = registry.find(ExtensionType.STORAGE, normalizedName)
                .orElseThrow(() -> new ExtensionResolutionException(
                        "Storage provider is not registered: " + normalizedName));
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new ExtensionResolutionException(
                    "Storage provider is not enabled: " + normalizedName + " (state=" + descriptor.state() + ")");
        }
        StorageProvider provider = providersByName.get(descriptor.name());
        if (provider == null) {
            throw new ExtensionResolutionException(
                    "Storage provider descriptor has no implementation adapter: " + normalizedName);
        }
        return provider;
    }

    public StorageProvider requireDefault() {
        return requireEnabled(BuiltInStorageProviders.defaultProviderName());
    }

    public List<StorageProvider> providers() {
        return List.copyOf(providersByName.values());
    }
}
