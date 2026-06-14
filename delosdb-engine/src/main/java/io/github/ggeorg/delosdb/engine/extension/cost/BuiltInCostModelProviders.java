package io.github.ggeorg.delosdb.engine.extension.cost;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;

/**
 * Built-in DelosDB cost-model providers known to the engine.
 *
 * <p>The provider family is still engine-internal. This class makes the
 * built-in proof provider visible to the same registry machinery used by
 * index, storage, and function providers without exposing Derby cost classes
 * as public SPI.</p>
 */
@InternalApi
public final class BuiltInCostModelProviders {
    private BuiltInCostModelProviders() {
    }

    public static CostModelProvider btree() {
        return BuiltInBTreeCostModelProvider.INSTANCE;
    }

    public static String defaultProviderName() {
        return BuiltInExtensions.DEFAULT_COST_MODEL_PROVIDER;
    }

    public static List<CostModelProvider> all() {
        return List.of(btree());
    }
}
