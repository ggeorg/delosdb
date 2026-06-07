package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.engine.extension.index.BuiltInIndexProviders;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.ArrayList;
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
    public static final String DEFAULT_INDEX_PROVIDER = BTREE_INDEX_PROVIDER;

    private BuiltInExtensions() {
    }

    public static void registerBuiltIns(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        BuiltInIndexProviders.all().forEach(provider -> registry.register(indexProviderDescriptor(provider)));
    }

    public static InMemoryExtensionRegistry newRegistryWithBuiltIns() {
        InMemoryExtensionRegistry registry = new InMemoryExtensionRegistry();
        registerBuiltIns(registry);
        return registry;
    }

    public static ExtensionDescriptor btreeIndexProvider() {
        return indexProviderDescriptor(BuiltInIndexProviders.btree());
    }

    public static ExtensionDescriptor indexProviderDescriptor(IndexProvider provider) {
        Objects.requireNonNull(provider, "provider");
        IndexMetadata metadata = IndexMetadata.of(provider.name(), "builtin_" + provider.name(), List.of("key"));
        return ExtensionDescriptor.builtIn(
                ExtensionType.INDEX,
                provider.name(),
                capabilityNames(provider.capabilities(metadata))
        );
    }

    private static List<String> capabilityNames(IndexCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> names = new ArrayList<>();
        names.add("default-index-provider");
        if (capabilities.supportsEqualityLookup()) {
            names.add("equality-lookup");
        }
        if (capabilities.supportsRangeScan()) {
            names.add("range-scan");
        }
        if (capabilities.supportsOrdering()) {
            names.add("ordered-scan");
        }
        if (capabilities.supportsUniqueConstraint()) {
            names.add("unique-capable");
        }
        if (capabilities.supportsNullableKeys()) {
            names.add("nullable-keys");
        }
        return List.copyOf(names);
    }
}
