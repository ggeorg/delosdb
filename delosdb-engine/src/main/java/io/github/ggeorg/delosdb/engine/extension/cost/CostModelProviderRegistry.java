package io.github.ggeorg.delosdb.engine.extension.cost;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.ProviderRegistry;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;

/**
 * Controlled internal registration path for CostModelProvider implementations.
 *
 * <p>This is not a public extension API yet. It records the internal provider
 * family now that the StoreCostController adapter proof is green.</p>
 */
@InternalApi
public final class CostModelProviderRegistry {
    private final ProviderRegistry<CostModelProvider> providers = new ProviderRegistry<>(
            "cost model provider",
            ExtensionType.COST_MODEL,
            CostModelProvider::name,
            BuiltInExtensions::costModelProviderDescriptor);

    private CostModelProviderRegistry() {
    }

    public static CostModelProviderRegistry empty() {
        return new CostModelProviderRegistry();
    }

    public static CostModelProviderRegistry builtIns() {
        CostModelProviderRegistry registry = new CostModelProviderRegistry();
        BuiltInCostModelProviders.all().forEach(registry::registerBuiltIn);
        return registry;
    }

    public synchronized void registerEnabled(CostModelProvider provider, String version) {
        providers.registerEnabled(provider, version);
    }

    public synchronized void registerEnabled(CostModelProvider provider) {
        providers.registerEnabled(provider);
    }

    public synchronized CostModelProviderResolver resolver() {
        return new CostModelProviderResolver(providers.resolver());
    }

    public synchronized ExtensionRegistry descriptors() {
        return providers.descriptors();
    }

    public synchronized List<CostModelProvider> providers() {
        return providers.providers();
    }

    private void registerBuiltIn(CostModelProvider provider) {
        boolean defaultProvider = BuiltInExtensions.DEFAULT_COST_MODEL_PROVIDER.equals(
                ExtensionDescriptor.normalizeName(provider.name()));
        providers.registerBuiltIn(provider, defaultProvider);
    }
}
