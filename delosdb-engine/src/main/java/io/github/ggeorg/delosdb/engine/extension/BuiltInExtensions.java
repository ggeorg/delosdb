package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.engine.extension.function.BuiltInFunctionProviders;
import io.github.ggeorg.delosdb.engine.extension.index.BuiltInIndexProviders;
import io.github.ggeorg.delosdb.engine.extension.storage.BuiltInStorageProviders;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexCapabilities;
import io.github.ggeorg.delosdb.spi.index.IndexMetadata;
import io.github.ggeorg.delosdb.spi.index.IndexProvider;
import io.github.ggeorg.delosdb.spi.function.FunctionCapabilities;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;
import io.github.ggeorg.delosdb.spi.storage.StorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

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
    public static final String HEAP_STORAGE_PROVIDER = "heap";
    public static final String DEFAULT_STORAGE_PROVIDER = HEAP_STORAGE_PROVIDER;
    public static final String BUILTIN_FUNCTION_PROVIDER = "delos";
    public static final String DEFAULT_FUNCTION_PROVIDER = BUILTIN_FUNCTION_PROVIDER;

    private BuiltInExtensions() {
    }

    public static void registerBuiltIns(ExtensionRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        BuiltInIndexProviders.all().forEach(provider -> registry.register(indexProviderDescriptor(provider)));
        BuiltInStorageProviders.all().forEach(provider -> registry.register(storageProviderDescriptor(provider)));
        BuiltInFunctionProviders.all().forEach(provider -> registry.register(functionProviderDescriptor(provider)));
    }

    public static InMemoryExtensionRegistry newRegistryWithBuiltIns() {
        InMemoryExtensionRegistry registry = new InMemoryExtensionRegistry();
        registerBuiltIns(registry);
        return registry;
    }

    public static ExtensionDescriptor btreeIndexProvider() {
        return indexProviderDescriptor(BuiltInIndexProviders.btree());
    }

    public static ExtensionDescriptor heapStorageProvider() {
        return storageProviderDescriptor(BuiltInStorageProviders.heap());
    }

    public static ExtensionDescriptor builtinFunctionProvider() {
        return functionProviderDescriptor(BuiltInFunctionProviders.builtin());
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
                capabilityNames(provider.capabilities(metadata), defaultProvider)
        );
    }

    public static ExtensionDescriptor storageProviderDescriptor(StorageProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return storageProviderDescriptor(
                provider,
                BUILTIN_VERSION,
                DEFAULT_STORAGE_PROVIDER.equals(ExtensionDescriptor.normalizeName(provider.name())));
    }

    public static ExtensionDescriptor storageProviderDescriptor(
            StorageProvider provider,
            String version,
            boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        TableStorageMetadata metadata = TableStorageMetadata.of(provider.name(), "APP", "PROVIDER_" + provider.name());
        return ExtensionDescriptor.enabled(
                ExtensionType.STORAGE,
                provider.name(),
                version,
                storageCapabilityNames(provider.capabilities(metadata), defaultProvider)
        );
    }

    public static ExtensionDescriptor functionProviderDescriptor(FunctionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return functionProviderDescriptor(
                provider,
                BUILTIN_VERSION,
                DEFAULT_FUNCTION_PROVIDER.equals(ExtensionDescriptor.normalizeName(provider.name())));
    }

    public static ExtensionDescriptor functionProviderDescriptor(
            FunctionProvider provider,
            String version,
            boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        return ExtensionDescriptor.enabled(
                ExtensionType.FUNCTION,
                provider.name(),
                version,
                functionCapabilityNames(provider.capabilities(), defaultProvider, !provider.functions().isEmpty())
        );
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

    private static List<String> storageCapabilityNames(StorageCapabilities capabilities, boolean defaultProvider) {
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> names = new ArrayList<>();
        if (defaultProvider) {
            names.add("default-storage-provider");
        }
        if (capabilities.rowStore()) {
            names.add("row-store");
        }
        if (capabilities.transactional()) {
            names.add("transactional");
        }
        if (capabilities.derbyHeapCompatible()) {
            names.add("derby-heap-compatible");
        }
        return List.copyOf(names);
    }

    private static List<String> functionCapabilityNames(
            FunctionCapabilities capabilities,
            boolean defaultProvider,
            boolean hasFunctions) {
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> names = new ArrayList<>();
        if (defaultProvider) {
            names.add("default-function-provider");
        }
        if (hasFunctions) {
            names.add("function-metadata");
        }
        if (capabilities.scalar()) {
            names.add("scalar-function");
        }
        if (capabilities.deterministic()) {
            names.add("deterministic");
        }
        if (!capabilities.readsSqlData()) {
            names.add("no-sql-data");
        }
        return List.copyOf(names);
    }
}
