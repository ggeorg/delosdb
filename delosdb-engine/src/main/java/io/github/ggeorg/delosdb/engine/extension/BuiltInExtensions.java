package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;
import java.util.Objects;

/**
 * Internal registration point for DelosDB providers that are built into the
 * current engine implementation.
 *
 * <p>This class records provider identity only. It does not expose Derby
 * storage, optimizer, or monitor classes as public SPI.</p>
 */
@InternalApi
public final class BuiltInExtensions {
    public static final String BUILTIN_VERSION = "builtin";
    public static final String BTREE_INDEX_PROVIDER = "btree";

    private BuiltInExtensions() {
    }

    public static void registerBuiltIns(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.register(btreeIndexProvider());
    }

    public static InMemoryExtensionRegistry newRegistryWithBuiltIns() {
        InMemoryExtensionRegistry registry = new InMemoryExtensionRegistry();
        registerBuiltIns(registry);
        return registry;
    }

    public static ExtensionDescriptor btreeIndexProvider() {
        return ExtensionDescriptor.builtIn(
                ExtensionType.INDEX,
                BTREE_INDEX_PROVIDER,
                List.of(
                        "default-index-provider",
                        "ordered-scan",
                        "range-scan",
                        "unique-capable"
                )
        );
    }
}
