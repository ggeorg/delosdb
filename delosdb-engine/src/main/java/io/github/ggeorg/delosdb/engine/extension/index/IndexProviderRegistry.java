package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.List;

/**
 * Controlled internal registration path for IndexProvider implementations.
 */
@InternalApi
public final class IndexProviderRegistry {
    private final ProviderRegistry<IndexProvider> providers = new ProviderRegistry<>(
            "index provider",
            ExtensionType.INDEX,
            IndexProvider::name,
            BuiltInExtensions::indexProviderDescriptor);

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
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(IndexProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized IndexProviderResolver resolver() {
        return new IndexProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<IndexProvider> providers() {
        return providers.providers();
    }

    private void registerBuiltIn(IndexProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_INDEX_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        providers.registerBuiltIn(provider, defaultProvider);
    }
}
