package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB function-provider descriptors to engine
 * implementation adapters.
 */
@InternalApi
public final class FunctionProviderResolver {
    private final ExtensionRegistry registry;
    private final Map<String, FunctionProvider> providersByName;

    public FunctionProviderResolver(ExtensionRegistry registry, List<FunctionProvider> providers) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(providers, "providers");
        Map<String, FunctionProvider> providersByName = new LinkedHashMap<>();
        for (FunctionProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String name = ExtensionDescriptor.normalizeName(provider.name());
            FunctionProvider previous = providersByName.put(name, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate function provider: " + name);
            }
        }
        this.providersByName = Map.copyOf(providersByName);
    }

    public static FunctionProviderResolver builtIns(ExtensionRegistry registry) {
        return new FunctionProviderResolver(registry, BuiltInFunctionProviders.all());
    }

    public static FunctionProviderResolver builtIns() {
        return FunctionProviderRegistry.builtIns().resolver();
    }

    public Optional<FunctionProvider> findEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        return registry.find(ExtensionType.FUNCTION, normalizedName)
                .filter(descriptor -> descriptor.state() == ExtensionState.ENABLED)
                .flatMap(descriptor -> Optional.ofNullable(providersByName.get(descriptor.name())));
    }

    public FunctionProvider requireEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        ExtensionDescriptor descriptor = registry.find(ExtensionType.FUNCTION, normalizedName)
                .orElseThrow(() -> new ExtensionResolutionException(
                        "Function provider is not registered: " + normalizedName));
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new ExtensionResolutionException(
                    "Function provider is not enabled: " + normalizedName + " (state=" + descriptor.state() + ")");
        }
        FunctionProvider provider = providersByName.get(descriptor.name());
        if (provider == null) {
            throw new ExtensionResolutionException(
                    "Function provider descriptor has no implementation adapter: " + normalizedName);
        }
        return provider;
    }

    public FunctionProvider requireDefault() {
        return requireEnabled(BuiltInFunctionProviders.defaultProviderName());
    }

    public Optional<FunctionDescriptor> findFunction(String schemaName, String functionName) {
        String qualifiedName = FunctionDescriptor.of(
                BuiltInFunctionProviders.defaultProviderName(), schemaName, functionName, "VARCHAR(1)", List.of())
                .qualifiedName();
        return providersByName.values().stream()
                .flatMap(provider -> provider.functions().stream())
                .filter(function -> function.qualifiedName().equals(qualifiedName))
                .findFirst();
    }

    public String describe(FunctionDescriptor function) {
        Objects.requireNonNull(function, "function");
        FunctionProvider provider = requireEnabled(function.providerName());
        var capabilities = provider.capabilities(function);
        String externalName = function.hasExternalName() ? function.externalName() : "";
        return "FunctionProvider{"
                + "provider=" + provider.name()
                + ", function=" + function.qualifiedName()
                + ", returnType=" + function.returnType()
                + ", parameters=" + function.parameterTypes()
                + ", scalar=" + capabilities.scalar()
                + ", deterministic=" + capabilities.deterministic()
                + ", readsSqlData=" + capabilities.readsSqlData()
                + ", externalName=" + externalName
                + "}";
    }

    public List<FunctionProvider> providers() {
        return List.copyOf(providersByName.values());
    }
}
