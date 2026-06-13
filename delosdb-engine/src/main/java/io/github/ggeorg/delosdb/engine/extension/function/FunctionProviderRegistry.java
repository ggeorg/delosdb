package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.InMemoryExtensionRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Controlled internal registration path for FunctionProvider implementations.
 */
@InternalApi
public final class FunctionProviderRegistry {
    private final InMemoryExtensionRegistry descriptors = new InMemoryExtensionRegistry();
    private final Map<String, FunctionProvider> providersByName = new LinkedHashMap<>();

    private FunctionProviderRegistry() {
    }

    public static FunctionProviderRegistry empty() {
        return new FunctionProviderRegistry();
    }

    public static FunctionProviderRegistry builtIns() {
        FunctionProviderRegistry registry = new FunctionProviderRegistry();
        BuiltInFunctionProviders.all().forEach(registry::registerBuiltIn);
        return registry;
    }

    public synchronized void registerEnabled(FunctionProvider provider, String version) {
        register(provider, version, false);
    }

    public synchronized void registerEnabled(FunctionProvider provider) {
        registerEnabled(provider, "manual");
    }

    public synchronized FunctionProviderResolver resolver() {
        return new FunctionProviderResolver(descriptors, new ArrayList<>(providersByName.values()));
    }

    public synchronized ExtensionRegistry descriptors() {
        return descriptors;
    }

    public synchronized List<FunctionProvider> providers() {
        return List.copyOf(providersByName.values());
    }

    private void registerBuiltIn(FunctionProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_FUNCTION_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        register(provider, BuiltInExtensions.BUILTIN_VERSION, defaultProvider);
    }

    private void register(FunctionProvider provider, String version, boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        String name = ExtensionDescriptor.normalizeName(provider.name());
        if (providersByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate function provider: " + name);
        }
        descriptors.register(BuiltInExtensions.functionProviderDescriptor(provider, version, defaultProvider));
        providersByName.put(name, provider);
    }
}
