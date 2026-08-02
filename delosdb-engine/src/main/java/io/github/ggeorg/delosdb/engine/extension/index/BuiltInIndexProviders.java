package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.List;

/**
 * Internal registry of executable index providers built into the engine.
 */
@InternalApi
public final class BuiltInIndexProviders {
    private BuiltInIndexProviders() {
    }

    public static IndexProvider btree() {
        return BuiltInBTreeIndexProvider.INSTANCE;
    }

    public static String defaultProviderName() {
        return BuiltInExtensions.DEFAULT_INDEX_PROVIDER;
    }

    public static IndexProvider defaultProvider() {
        return btree();
    }

    public static List<IndexProvider> all() {
        return List.of(btree());
    }

    public static boolean isSqlCreatable(String providerName) {
        return BuiltInExtensions.BTREE_INDEX_PROVIDER.equals(providerName);
    }
}
