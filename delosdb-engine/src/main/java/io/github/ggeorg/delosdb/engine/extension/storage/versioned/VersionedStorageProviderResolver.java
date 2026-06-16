package io.github.ggeorg.delosdb.engine.extension.storage.versioned;

import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Internal resolver for opt-in versioned storage providers. */
@InternalApi
public final class VersionedStorageProviderResolver {
    private final ProviderResolver<VersionedStorageProvider> providers;

    VersionedStorageProviderResolver(ProviderResolver<VersionedStorageProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public VersionedStorageProviderResolver(
            ExtensionRegistry registry,
            List<VersionedStorageProvider> providers) {
        this(new ProviderResolver<>(
                "versioned storage provider",
                ExtensionType.VERSIONED_STORAGE,
                registry,
                providers,
                VersionedStorageProvider::name));
    }

    public Optional<VersionedStorageProvider> findEnabled(String name) {
        return providers.findEnabled(name);
    }

    public VersionedStorageProvider requireEnabled(String name) {
        return providers.requireEnabled(name);
    }

    public List<VersionedStorageProvider> providers() {
        return providers.providers();
    }
}
