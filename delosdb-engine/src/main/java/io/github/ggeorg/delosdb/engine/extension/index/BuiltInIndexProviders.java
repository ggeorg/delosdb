package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.List;

/**
 * Internal registry of index providers built into the current engine.
 *
 * <p>These providers are implementation adapters. They are not loaded through a
 * public extension mechanism yet and they must not expose Derby Monitor or store
 * classes to extension authors.</p>
 */
@InternalApi
public final class BuiltInIndexProviders {
    private BuiltInIndexProviders() {
    }

    public static IndexProvider btree() {
        return BuiltInBTreeIndexProvider.INSTANCE;
    }

    public static List<IndexProvider> all() {
        return List.of(btree());
    }
}
