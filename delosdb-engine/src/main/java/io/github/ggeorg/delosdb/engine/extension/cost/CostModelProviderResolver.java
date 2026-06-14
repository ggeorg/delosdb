package io.github.ggeorg.delosdb.engine.extension.cost;

import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Internal resolver that maps CostModelProvider descriptors to engine adapters.
 */
@InternalApi
public final class CostModelProviderResolver {
    private final ProviderResolver<CostModelProvider> providers;

    CostModelProviderResolver(ProviderResolver<CostModelProvider> providers) {
        this.providers = Objects.requireNonNull(providers, "providers");
    }

    public CostModelProviderResolver(ExtensionRegistry registry, List<CostModelProvider> providers) {
        this(new ProviderResolver<>(
                "cost model provider",
                ExtensionType.COST_MODEL,
                registry,
                providers,
                CostModelProvider::name));
    }

    public static CostModelProviderResolver builtIns(ExtensionRegistry registry) {
        return new CostModelProviderResolver(registry, BuiltInCostModelProviders.all());
    }

    public static CostModelProviderResolver builtIns() {
        return CostModelProviderRegistry.builtIns().resolver();
    }

    public Optional<CostModelProvider> findEnabled(String name) {
        return providers.findEnabled(name);
    }

    public Optional<CostModelProvider> findEnabledForFactoryId(int factoryId) {
        return BuiltInCostModelProviders.providerNameForFactoryId(factoryId)
                .flatMap(this::findEnabled);
    }

    public CostModelProvider requireEnabled(String name) {
        return providers.requireEnabled(name);
    }

    public CostModelProvider requireDefault() {
        return requireEnabled(BuiltInCostModelProviders.defaultProviderName());
    }

    public List<CostModelProvider> providers() {
        return providers.providers();
    }
}
