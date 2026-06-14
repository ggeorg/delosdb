package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionResolutionException;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.storage.BuiltInStorageProviders;
import io.github.ggeorg.delosdb.engine.extension.storage.StorageProviderResolver;
import io.github.ggeorg.delosdb.spi.storage.StorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.StorageProvider;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.util.List;

/**
 * Verifies the initial StorageProvider v0 metadata seam.
 *
 * <p>This smoke intentionally does not exercise CREATE TABLE syntax or physical
 * storage changes. It proves the built-in Derby-compatible heap provider is
 * registered as DelosDB storage-provider metadata and is resolvable through the
 * same internal extension descriptor shape used by IndexProvider.</p>
 */
public final class StorageProviderMetadataSmoke {
    private static final String DEFAULT_PROVIDER = "heap";

    private StorageProviderMetadataSmoke() {
    }

    public static void main(String[] args) {
        ExtensionRegistry registry = BuiltInExtensions.newRegistryWithBuiltIns();
        ExtensionDescriptor descriptor = registry.find(ExtensionType.STORAGE, DEFAULT_PROVIDER)
                .orElseThrow(() -> new IllegalStateException("Missing heap storage provider descriptor"));

        assertEquals(ExtensionType.STORAGE, descriptor.type(), "descriptor type");
        assertEquals(DEFAULT_PROVIDER, descriptor.name(), "descriptor name");
        assertEquals(BuiltInExtensions.BUILTIN_VERSION, descriptor.version(), "descriptor version");
        assertEquals(ExtensionState.ENABLED, descriptor.state(), "descriptor state");
        assertCapabilities(descriptor.capabilities(), List.of(
                "default-storage-provider",
                "row-store",
                "transactional",
                "derby-heap-compatible"));

        StorageProviderResolver resolver = StorageProviderResolver.builtIns(registry);
        StorageProvider defaultProvider = resolver.requireDefault();
        StorageProvider explicitProvider = resolver.requireEnabled(DEFAULT_PROVIDER);

        assertEquals(BuiltInStorageProviders.defaultProviderName(), defaultProvider.name(), "default provider");
        assertEquals(defaultProvider.name(), explicitProvider.name(), "explicit provider");
        if (resolver.findEnabled("hash").isPresent()) {
            throw new IllegalStateException("Unexpected hash storage provider");
        }
        assertUnregisteredProvider(resolver, "hash");

        TableStorageMetadata metadata = TableStorageMetadata.of(defaultProvider.name(), "app", "storage_provider_smoke");
        assertEquals(DEFAULT_PROVIDER, metadata.providerName(), "metadata provider");
        assertEquals("APP", metadata.schemaName(), "metadata schema");
        assertEquals("STORAGE_PROVIDER_SMOKE", metadata.tableName(), "metadata table");

        StorageCapabilities providerCapabilities = defaultProvider.capabilities();
        if (!providerCapabilities.rowStore()
                || !providerCapabilities.transactional()
                || !providerCapabilities.derbyHeapCompatible()) {
            throw new IllegalStateException(
                    "Heap storage provider-level capabilities are incomplete: " + providerCapabilities);
        }

        StorageCapabilities tableCapabilities = defaultProvider.capabilities(metadata);
        if (!tableCapabilities.equals(providerCapabilities)) {
            throw new IllegalStateException(
                    "StorageProvider v0 table capabilities should match provider capabilities: "
                            + tableCapabilities + " vs " + providerCapabilities);
        }

        System.out.println("DelosDB StorageProvider metadata smoke test passed.");
    }

    private static void assertCapabilities(List<String> actual, List<String> expected) {
        for (String capability : expected) {
            if (!actual.contains(capability)) {
                throw new IllegalStateException("Missing storage capability " + capability + " in " + actual);
            }
        }
    }

    private static void assertUnregisteredProvider(StorageProviderResolver resolver, String providerName) {
        try {
            resolver.requireEnabled(providerName);
            throw new IllegalStateException("Storage provider " + providerName + " unexpectedly resolved");
        } catch (ExtensionResolutionException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains(providerName)) {
                throw new IllegalStateException("Missing provider diagnostic did not name " + providerName, expected);
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " expected " + expected + " but was " + actual);
        }
    }
}
