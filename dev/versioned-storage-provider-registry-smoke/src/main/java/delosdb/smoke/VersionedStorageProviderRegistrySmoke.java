package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderDiscovery;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderRegistry;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;

import java.util.List;

/** Smoke proof for experimental VersionedStorageProvider discovery and registry visibility. */
public final class VersionedStorageProviderRegistrySmoke {
    private VersionedStorageProviderRegistrySmoke() {
    }

    public static void main(String[] args) {
        List<VersionedStorageProvider> discovered = VersionedStorageProviderDiscovery.discover(
                Thread.currentThread().getContextClassLoader());
        assertTrue(discovered.stream().anyMatch(provider -> "delos_mvcc".equals(provider.name())),
                "ServiceLoader must discover delos_mvcc provider");

        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.discovered(
                Thread.currentThread().getContextClassLoader());
        VersionedStorageProvider provider = registry.resolver().requireEnabled("delos_mvcc");
        assertEquals("delos_mvcc", provider.name(), "resolver must return delos_mvcc provider");

        ExtensionDescriptor descriptor = registry.requireDescriptor("delos_mvcc");
        assertEquals(ExtensionType.VERSIONED_STORAGE, descriptor.type(), "descriptor type");
        assertEquals(ExtensionState.ENABLED, descriptor.state(), "descriptor state");
        assertTrue(descriptor.capabilities().contains(VersionedStorageCapabilities.SNAPSHOT_VISIBILITY),
                "descriptor must expose snapshot visibility capability");
        assertTrue(descriptor.capabilities().contains(VersionedStorageCapabilities.TABLE_SCAN),
                "descriptor must expose table-scan capability");
        assertTrue(descriptor.capabilities().contains(VersionedStorageCapabilities.MANUAL_CLEANUP),
                "descriptor must expose manual-cleanup capability");
        assertTrue(descriptor.capabilities().contains(VersionedStorageCapabilities.PROVIDER_OWNED_INDEXES),
                "descriptor must expose provider-owned-indexes capability");
        assertTrue(descriptor.capabilities().contains(VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE),
                "descriptor must expose in-memory prototype capability");

        assertTrue(registry.providers().size() == 1, "registry should contain one discovered provider in this smoke");
        assertTrue(registry.descriptors().descriptors().stream()
                        .anyMatch(item -> item.type() == ExtensionType.VERSIONED_STORAGE
                                && "delos_mvcc".equals(item.name())),
                "registry descriptors must include delos_mvcc");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
