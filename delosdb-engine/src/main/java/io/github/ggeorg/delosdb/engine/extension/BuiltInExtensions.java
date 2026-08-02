package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.engine.extension.cost.CostModelProvider;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Internal descriptor factory for the provider families that participate in
 * executable DelosDB runtime paths.
 */
@InternalApi
public final class BuiltInExtensions {
    public static final String BUILTIN_VERSION = "builtin";
    public static final String BTREE_INDEX_PROVIDER = "btree";
    public static final String DEFAULT_INDEX_PROVIDER = BTREE_INDEX_PROVIDER;
    public static final String HEAP_COST_MODEL_PROVIDER = "heap";
    public static final String BTREE_COST_MODEL_PROVIDER = "btree";
    public static final String DEFAULT_COST_MODEL_PROVIDER = BTREE_COST_MODEL_PROVIDER;

    private BuiltInExtensions() {
    }

    public static ExtensionDescriptor indexProviderDescriptor(IndexProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return indexProviderDescriptor(
                provider,
                BUILTIN_VERSION,
                DEFAULT_INDEX_PROVIDER.equals(ExtensionDescriptor.normalizeName(provider.name())));
    }

    public static ExtensionDescriptor indexProviderDescriptor(
            IndexProvider provider,
            String version,
            boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        IndexMetadata metadata = IndexMetadata.of(provider.name(), "provider_" + provider.name(), List.of("key"));
        return ExtensionDescriptor.enabled(
                ExtensionType.INDEX,
                provider.name(),
                version,
                capabilityNames(provider.capabilities(metadata), defaultProvider));
    }

    public static ExtensionDescriptor costModelProviderDescriptor(CostModelProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return costModelProviderDescriptor(
                provider,
                BUILTIN_VERSION,
                DEFAULT_COST_MODEL_PROVIDER.equals(ExtensionDescriptor.normalizeName(provider.name())));
    }

    public static ExtensionDescriptor costModelProviderDescriptor(
            CostModelProvider provider,
            String version,
            boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        return ExtensionDescriptor.enabled(
                ExtensionType.COST_MODEL,
                provider.name(),
                version,
                costModelCapabilityNames(provider, defaultProvider));
    }

    private static List<String> capabilityNames(IndexCapabilities capabilities, boolean defaultProvider) {
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> names = new ArrayList<>();
        if (defaultProvider) {
            names.add("default-index-provider");
        }
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

    private static List<String> costModelCapabilityNames(
            CostModelProvider provider,
            boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        List<String> names = new ArrayList<>();
        if (defaultProvider) {
            names.add("default-cost-model-provider");
        }
        names.add("native-store-cost-controller-adapter");
        names.add("registry-resolved-provider");
        names.add("diagnostic-mode");
        names.add("enabled-mode");
        if (provider.accessMethodFactoryId() == 0) {
            names.add("heap-access-method");
        } else if (provider.accessMethodFactoryId() == 1) {
            names.add("btree-access-method");
        }
        return List.copyOf(names);
    }
}
