package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.List;

/**
 * Controlled internal registration path for FunctionProvider implementations.
 */
@InternalApi
public final class FunctionProviderRegistry {
    private final ProviderRegistry<FunctionProvider> providers = new ProviderRegistry<>(
            "function provider",
            ExtensionType.FUNCTION,
            FunctionProvider::name,
            BuiltInExtensions::functionProviderDescriptor);

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
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(FunctionProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized FunctionProviderResolver resolver() {
        return new FunctionProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<FunctionProvider> providers() {
        return providers.providers();
    }

    private void registerBuiltIn(FunctionProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_FUNCTION_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        providers.registerBuiltIn(provider, defaultProvider);
    }
}
