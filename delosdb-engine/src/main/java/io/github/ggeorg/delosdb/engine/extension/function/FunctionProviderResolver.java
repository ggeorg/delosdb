package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps DelosDB function-provider descriptors to engine
 * implementation adapters.
 */
@InternalApi
public final class FunctionProviderResolver {
    private final ProviderResolver<FunctionProvider> providers;

    FunctionProviderResolver(ProviderResolver<FunctionProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public FunctionProviderResolver(ExtensionRegistry registry, List<FunctionProvider> providers) {
        this(new ProviderResolver<>(
                "function provider",
                ExtensionType.FUNCTION,
                registry,
                providers,
                FunctionProvider::name));
    }

    public static FunctionProviderResolver builtIns(ExtensionRegistry registry) {
        return new FunctionProviderResolver(registry, BuiltInFunctionProviders.all());
    }

    public static FunctionProviderResolver builtIns() {
        return FunctionProviderRegistry.builtIns().resolver();
    }

    public Optional<FunctionProvider> findEnabled(String name) {
        return providers.findEnabled(name);
    }

    public FunctionProvider requireEnabled(String name) {
        return providers.requireEnabled(name);
    }

    public FunctionProvider requireDefault() {
        return requireEnabled(BuiltInFunctionProviders.defaultProviderName());
    }

    public Optional<FunctionDescriptor> findFunction(String schemaName, String functionName) {
        String qualifiedName = qualifiedName(schemaName, functionName);
        return providers.providers().stream()
                .flatMap(provider -> provider.functions().stream())
                .filter(function -> function.qualifiedName().equals(qualifiedName))
                .findFirst();
    }

    public String describe(FunctionDescriptor function) {
        Objects.requireNonNull(function, "function");
        FunctionProvider provider = requireEnabled(function.providerName());
        var capabilities = provider.capabilities();
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
        return providers.providers();
    }

    private static String qualifiedName(String schemaName, String functionName) {
        return normalizeIdentifier(schemaName, "schemaName")
                + "."
                + normalizeIdentifier(functionName, "functionName");
    }

    private static String normalizeIdentifier(String identifier, String label) {
        String normalized = Objects.requireNonNull(identifier, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
