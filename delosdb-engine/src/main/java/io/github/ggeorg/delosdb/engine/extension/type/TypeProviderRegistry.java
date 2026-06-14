package io.github.ggeorg.delosdb.engine.extension.type;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.type.TypeProvider;

import java.util.List;

/**
 * Controlled internal registration path for TypeProvider implementations.
 *
 * <p>TypeProvider v0 is metadata-only. It does not register new SQL syntax,
 * binder rules, storage format, or execution semantics.</p>
 */
@InternalApi
public final class TypeProviderRegistry {
    private final ProviderRegistry<TypeProvider> providers = new ProviderRegistry<>(
            "type provider",
            ExtensionType.TYPE,
            TypeProvider::name,
            BuiltInExtensions::typeProviderDescriptor);

    private TypeProviderRegistry() {
    }

    public static TypeProviderRegistry empty() {
        return new TypeProviderRegistry();
    }

    public static TypeProviderRegistry builtIns() {
        TypeProviderRegistry registry = new TypeProviderRegistry();
        BuiltInTypeProviders.all().forEach(registry::registerBuiltIn);
        return registry;
    }

    public synchronized void registerEnabled(TypeProvider provider, String version) {
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(TypeProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized TypeProviderResolver resolver() {
        return new TypeProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<TypeProvider> providers() {
        return providers.providers();
    }

    private void registerBuiltIn(TypeProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_TYPE_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        providers.registerBuiltIn(provider, defaultProvider);
    }
}
