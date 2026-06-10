package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.InMemoryExtensionRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlled internal registration path for IndexProvider implementations.
 *
 * <p>This is deliberately not discovery and not public plugin loading. It lets
 * engine code and tests assemble an explicit registry of provider descriptors
 * and provider adapters, then hand that registry to {@link IndexProviderResolver}.
 * ServiceLoader or catalog-backed loading can be added later on top of this
 * seam without exposing Derby internals to the public SPI.</p>
 */
@InternalApi
public final class IndexProviderRegistry {
    private final InMemoryExtensionRegistry descriptors = new InMemoryExtensionRegistry();
    private final Map<String, IndexProvider> providersByName = new LinkedHashMap<>();

    private IndexProviderRegistry() {
    }

    public static IndexProviderRegistry empty() {
        return new IndexProviderRegistry();
    }

    public static IndexProviderRegistry builtIns() {
        IndexProviderRegistry registry = new IndexProviderRegistry();
        BuiltInIndexProviders.all().forEach(registry::registerBuiltIn);
        return registry;
    }

    public synchronized void registerEnabled(IndexProvider provider, String version) {
        register(provider, version, false);
    }

    public synchronized void registerEnabled(IndexProvider provider) {
        registerEnabled(provider, "manual");
    }

    public synchronized IndexProviderResolver resolver() {
        return new IndexProviderResolver(descriptors, new ArrayList<>(providersByName.values()));
    }

    public synchronized ExtensionRegistry descriptors() {
        return descriptors;
    }

    public synchronized List<IndexProvider> providers() {
        return List.copyOf(providersByName.values());
    }

    private void registerBuiltIn(IndexProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_INDEX_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        register(provider, BuiltInExtensions.BUILTIN_VERSION, defaultProvider);
    }

    private void register(IndexProvider provider, String version, boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        String name = ExtensionDescriptor.normalizeName(provider.name());
        if (providersByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate index provider: " + name);
        }
        descriptors.register(BuiltInExtensions.indexProviderDescriptor(provider, version, defaultProvider));
        providersByName.put(name, provider);
    }
}
