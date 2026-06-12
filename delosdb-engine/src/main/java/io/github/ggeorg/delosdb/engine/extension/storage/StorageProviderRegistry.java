package io.github.ggeorg.delosdb.engine.extension.storage;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.InMemoryExtensionRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlled internal registration path for StorageProvider implementations.
 *
 * <p>This is deliberately not discovery and not public plugin loading. It lets
 * engine code and tests assemble explicit storage provider descriptors before a
 * later catalog/parser bridge records table-level provider choices.</p>
 */
@InternalApi
public final class StorageProviderRegistry {
    private final InMemoryExtensionRegistry descriptors = new InMemoryExtensionRegistry();
    private final Map<String, StorageProvider> providersByName = new LinkedHashMap<>();

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
        register(provider, version, false);
    }

    public synchronized void registerEnabled(StorageProvider provider) {
        registerEnabled(provider, "manual");
    }

    public synchronized StorageProviderResolver resolver() {
        return new StorageProviderResolver(descriptors, new ArrayList<>(providersByName.values()));
    }

    public synchronized ExtensionRegistry descriptors() {
        return descriptors;
    }

    public synchronized List<StorageProvider> providers() {
        return List.copyOf(providersByName.values());
    }

    private void registerBuiltIn(StorageProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_STORAGE_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        register(provider, BuiltInExtensions.BUILTIN_VERSION, defaultProvider);
    }

    private void register(StorageProvider provider, String version, boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        String name = ExtensionDescriptor.normalizeName(provider.name());
        if (providersByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate storage provider: " + name);
        }
        descriptors.register(BuiltInExtensions.storageProviderDescriptor(provider, version, defaultProvider));
        providersByName.put(name, provider);
    }
}
