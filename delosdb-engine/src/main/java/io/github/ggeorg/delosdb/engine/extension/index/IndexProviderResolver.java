package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB index-provider descriptors to engine
 * implementation adapters.
 */
@InternalApi
public final class IndexProviderResolver {
    private final ProviderResolver<IndexProvider> providers;

    IndexProviderResolver(ProviderResolver<IndexProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public IndexProviderResolver(ExtensionRegistry registry, List<IndexProvider> providers) {
        this(new ProviderResolver<>(
                "index provider",
                ExtensionType.INDEX,
                registry,
                providers,
                IndexProvider::name));
    }

    public static IndexProviderResolver builtIns(ExtensionRegistry registry) {
        return new IndexProviderResolver(registry, BuiltInIndexProviders.all());
    }

    public static IndexProviderResolver builtIns() {
        return IndexProviderRegistry.builtIns().resolver();
    }

    public Optional<IndexProvider> findEnabled(String name) {
        return providers.findEnabled(name);
    }

    public IndexProvider requireEnabled(String name) {
        return providers.requireEnabled(name);
    }

    public IndexProvider requireDefault() {
        return requireEnabled(BuiltInIndexProviders.defaultProviderName());
    }

    public List<IndexProvider> providers() {
        return providers.providers();
    }
}
