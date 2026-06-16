package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderRegistry;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.util.Locale;

/**
 * Verifies that the experimental MVCC module can be represented and resolved as
 * a DelosDB versioned-storage provider without wiring SQL execution.
 */
public final class VersionedStorageProviderRegistrySmoke {
    private VersionedStorageProviderRegistrySmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "experimental");

        ExtensionDescriptor descriptor = registry.requireDescriptor(DelosMvccStorageProvider.PROVIDER_NAME);
        requireDescriptor(descriptor);
        requireCapability(descriptor, VersionedStorageCapabilities.SNAPSHOT_VISIBILITY);
        requireCapability(descriptor, VersionedStorageCapabilities.TABLE_SCAN);
        requireCapability(descriptor, VersionedStorageCapabilities.MANUAL_CLEANUP);
        requireCapability(descriptor, VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE);

        VersionedStorageProvider resolved = registry.resolver()
                .requireEnabled(DelosMvccStorageProvider.PROVIDER_NAME);
        if (resolved != provider) {
            throw new IllegalStateException("Resolver returned a different provider instance");
        }

        if (!registry.resolver().providers().contains(provider)) {
            throw new IllegalStateException("Resolver provider list does not contain delos_mvcc");
        }

        System.out.println("versioned_storage " + descriptor.name()
                + " state=" + descriptor.state().name().toLowerCase(Locale.ROOT)
                + " version=" + descriptor.version()
                + " capabilities=" + String.join(",", descriptor.capabilities()));
        System.out.println("DelosDB VersionedStorageProvider registry smoke test passed.");
    }

    private static void requireDescriptor(ExtensionDescriptor descriptor) {
        if (descriptor.type() != ExtensionType.VERSIONED_STORAGE) {
            throw new IllegalStateException("Expected VERSIONED_STORAGE descriptor but was " + descriptor.type());
        }
        if (!DelosMvccStorageProvider.PROVIDER_NAME.equals(descriptor.name())) {
            throw new IllegalStateException("Expected delos_mvcc provider but was " + descriptor.name());
        }
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new IllegalStateException("Expected enabled provider but state was " + descriptor.state());
        }
        if (!"experimental".equals(descriptor.version())) {
            throw new IllegalStateException("Expected experimental version but was " + descriptor.version());
        }
    }

    private static void requireCapability(ExtensionDescriptor descriptor, String capability) {
        if (!descriptor.capabilities().contains(capability)) {
            throw new IllegalStateException(
                    "Descriptor " + descriptor.name() + " is missing capability " + capability
                            + ": " + descriptor.capabilities());
        }
    }
}
