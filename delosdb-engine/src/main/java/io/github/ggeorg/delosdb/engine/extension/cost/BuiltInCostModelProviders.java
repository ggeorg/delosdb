package io.github.ggeorg.delosdb.engine.extension.cost;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;
import java.util.Optional;

/**
 * Built-in DelosDB cost-model providers known to the engine.
 *
 * <p>The provider family is engine-internal and uses the same registry
 * machinery as the executable B-tree index provider without exposing Derby
 * cost classes as public SPI.</p>
 */
@InternalApi
public final class BuiltInCostModelProviders {
    private BuiltInCostModelProviders() {
    }

    public static CostModelProvider heap() {
        return BuiltInHeapCostModelProvider.INSTANCE;
    }

    public static CostModelProvider btree() {
        return BuiltInBTreeCostModelProvider.INSTANCE;
    }

    public static String defaultProviderName() {
        return BuiltInExtensions.DEFAULT_COST_MODEL_PROVIDER;
    }

    public static List<CostModelProvider> all() {
        return List.of(heap(), btree());
    }

    public static Optional<String> providerNameForFactoryId(int factoryId) {
        return all().stream()
                .filter(provider -> provider.accessMethodFactoryId() == factoryId)
                .map(CostModelProvider::name)
                .map(ExtensionDescriptor::normalizeName)
                .findFirst();
    }
}
