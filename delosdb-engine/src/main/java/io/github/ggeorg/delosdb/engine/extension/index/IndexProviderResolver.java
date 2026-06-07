package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB index-provider descriptors to engine
 * implementation adapters.
 *
 * <p>This is still not public extension loading. It only proves the bridge
 * shape for built-in providers: an {@link ExtensionRegistry} records provider
 * identity and this resolver maps enabled descriptors to internal adapters.</p>
 */
@InternalApi
public final class IndexProviderResolver {
    private final ExtensionRegistry registry;
    private final Map<String, IndexProvider> providersByName;

    public IndexProviderResolver(ExtensionRegistry registry, List<IndexProvider> providers) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(providers, "providers");
        Map<String, IndexProvider> providersByName = new LinkedHashMap<>();
        for (IndexProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String name = ExtensionDescriptor.normalizeName(provider.name());
            IndexProvider previous = providersByName.put(name, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate index provider: " + name);
            }
        }
        this.providersByName = Map.copyOf(providersByName);
    }

    public static IndexProviderResolver builtIns(ExtensionRegistry registry) {
        return new IndexProviderResolver(registry, BuiltInIndexProviders.all());
    }

    public Optional<IndexProvider> findEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        return registry.find(ExtensionType.INDEX, normalizedName)
                .filter(descriptor -> descriptor.state() == ExtensionState.ENABLED)
                .flatMap(descriptor -> Optional.ofNullable(providersByName.get(descriptor.name())));
    }

    public IndexProvider requireEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        ExtensionDescriptor descriptor = registry.find(ExtensionType.INDEX, normalizedName)
                .orElseThrow(() -> new ExtensionResolutionException(
                        "Index provider is not registered: " + normalizedName));
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new ExtensionResolutionException(
                    "Index provider is not enabled: " + normalizedName + " (state=" + descriptor.state() + ")");
        }
        IndexProvider provider = providersByName.get(descriptor.name());
        if (provider == null) {
            throw new ExtensionResolutionException(
                    "Index provider descriptor has no implementation adapter: " + normalizedName);
        }
        return provider;
    }


    public IndexProvider requireDefault() {
        return requireEnabled(BuiltInIndexProviders.defaultProviderName());
    }

    public List<IndexProvider> providers() {
        return List.copyOf(providersByName.values());
    }
}
