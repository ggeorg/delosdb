package io.github.ggeorg.delosdb.engine.extension.storage.versioned;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

import java.util.List;

/**
 * Controlled internal registration path for experimental VersionedStorageProvider implementations.
 *
 * <p>This registry is intentionally separate from the Derby-compatible heap StorageProvider registry.
 * It lets DelosDB describe and resolve opt-in versioned storage providers without making any
 * provider executable by SQL yet.</p>
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
        return descriptors().find(ExtensionType.VERSIONED_STORAGE, name)
                .orElseThrow(() -> new IllegalStateException("Missing versioned storage descriptor: " + name));
    }
}
