package io.github.ggeorg.delosdb.engine.extension.storage.versioned;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

import java.util.List;
import java.util.Objects;

/**
 * Controlled internal registration path for opt-in versioned storage providers.
 *
 * <p>This registry is deliberately separate from the Derby-compatible heap
 * {@code StorageProvider} registry. It makes MVCC-style storage visible as a
 * provider family without making SQL execution or Derby heap conversion happen
 * implicitly.</p>
 */
@InternalApi
public final class VersionedStorageProviderRegistry {
    private final ProviderRegistry<VersionedStorageProvider> providers = new ProviderRegistry<>(
            "versioned storage provider",
            ExtensionType.VERSIONED_STORAGE,
            VersionedStorageProvider::name,
            BuiltInExtensions::versionedStorageProviderDescriptor);

    private VersionedStorageProviderRegistry() {
    }

    public static VersionedStorageProviderRegistry empty() {
        return new VersionedStorageProviderRegistry();
    }

    public static VersionedStorageProviderRegistry discovered() {
        return discovered(Thread.currentThread().getContextClassLoader());
    }

    public static VersionedStorageProviderRegistry discovered(ClassLoader classLoader) {
        VersionedStorageProviderRegistry registry = empty();
        VersionedStorageProviderDiscovery.discover(classLoader).forEach(registry::registerDiscovered);
        return registry;
    }

    public synchronized void registerEnabled(VersionedStorageProvider provider, String version) {
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(VersionedStorageProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized VersionedStorageProviderResolver resolver() {
        return new VersionedStorageProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<VersionedStorageProvider> providers() {
        return providers.providers();
    }

    public synchronized ExtensionDescriptor requireDescriptor(String name) {
        Objects.requireNonNull(name, "name");
        return descriptors().find(ExtensionType.VERSIONED_STORAGE, name)
                .orElseThrow(() -> new IllegalStateException("Missing versioned storage descriptor: " + name));
    }

    private void registerDiscovered(VersionedStorageProvider provider) {
        providers.registerEnabled(provider, "discovered");
    }
}
