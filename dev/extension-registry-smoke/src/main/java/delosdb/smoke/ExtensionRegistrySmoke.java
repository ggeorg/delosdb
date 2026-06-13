package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.engine.extension.ExtensionDescriptor;
import io.github.ggeorg.delosdb.engine.extension.ExtensionRegistry;
import io.github.ggeorg.delosdb.engine.extension.ExtensionState;
import io.github.ggeorg.delosdb.engine.extension.ExtensionType;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Verifies the first unified DelosDB extension registry surface.
 */
public final class ExtensionRegistrySmoke {
    private ExtensionRegistrySmoke() {
    }

    public static void main(String[] args) {
        ExtensionRegistry registry = BuiltInExtensions.newRegistryWithBuiltIns();

        ExtensionDescriptor index = requireDescriptor(registry, ExtensionType.INDEX, "btree");
        ExtensionDescriptor storage = requireDescriptor(registry, ExtensionType.STORAGE, "heap");
        ExtensionDescriptor function = requireDescriptor(registry, ExtensionType.FUNCTION, "builtin");

        requireEnabled(index);
        requireEnabled(storage);
        requireEnabled(function);

        requireCapability(index, "default-index-provider");
        requireCapability(storage, "default-storage-provider");
        requireCapability(function, "default-function-provider");
        requireCapability(function, "function-metadata");

        List<ExtensionDescriptor> descriptors = registry.descriptors().stream()
                .sorted(Comparator
                        .comparing((ExtensionDescriptor descriptor) -> descriptor.type().name())
                        .thenComparing(ExtensionDescriptor::name))
                .toList();

        System.out.println("DelosDB extensions:");
        for (ExtensionDescriptor descriptor : descriptors) {
            if (descriptor.type() == ExtensionType.INDEX
                    || descriptor.type() == ExtensionType.STORAGE
                    || descriptor.type() == ExtensionType.FUNCTION) {
                System.out.println("  " + describe(descriptor));
            }
        }
        System.out.println("DelosDB unified extension registry smoke test passed.");
    }

    private static ExtensionDescriptor requireDescriptor(
            ExtensionRegistry registry,
            ExtensionType type,
            String name) {
        return registry.find(type, name)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing extension descriptor: " + type + ":" + name));
    }

    private static void requireEnabled(ExtensionDescriptor descriptor) {
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new IllegalStateException(
                    "Extension is not enabled: " + descriptor.type() + ":" + descriptor.name()
                            + " state=" + descriptor.state());
        }
    }

    private static void requireCapability(ExtensionDescriptor descriptor, String capability) {
        if (!descriptor.capabilities().contains(capability)) {
            throw new IllegalStateException(
                    "Extension " + descriptor.type() + ":" + descriptor.name()
                            + " is missing capability: " + capability
                            + " capabilities=" + descriptor.capabilities());
        }
    }

    private static String describe(ExtensionDescriptor descriptor) {
        return descriptor.type().name().toLowerCase(Locale.ROOT)
                + " " + descriptor.name()
                + " state=" + descriptor.state().name().toLowerCase(Locale.ROOT)
                + " version=" + descriptor.version()
                + " capabilities=" + String.join(",", descriptor.capabilities());
    }
}
