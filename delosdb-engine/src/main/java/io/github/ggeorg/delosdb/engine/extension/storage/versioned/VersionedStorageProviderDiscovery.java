package io.github.ggeorg.delosdb.engine.extension.storage.versioned;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * ServiceLoader based discovery for optional versioned storage providers.
 *
 * <p>Discovery is intentionally read-only. Loading a provider only makes its
 * descriptor and implementation available to the versioned-storage registry;
 * it does not make SQL use that provider and it does not change existing Derby
 * storage.</p>
 */
@InternalApi
public final class VersionedStorageProviderDiscovery {
    private VersionedStorageProviderDiscovery() {
    }

    public static List<VersionedStorageProvider> discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static List<VersionedStorageProvider> discover(ClassLoader classLoader) {
        ClassLoader loader = classLoader != null
                ? classLoader
                : VersionedStorageProviderDiscovery.class.getClassLoader();
        ServiceLoader<VersionedStorageProvider> serviceLoader = ServiceLoader.load(
                VersionedStorageProvider.class,
                loader);
        List<VersionedStorageProvider> providers = new ArrayList<>();
        for (VersionedStorageProvider provider : serviceLoader) {
            providers.add(Objects.requireNonNull(provider, "provider"));
        }
        return List.copyOf(providers);
    }
}
