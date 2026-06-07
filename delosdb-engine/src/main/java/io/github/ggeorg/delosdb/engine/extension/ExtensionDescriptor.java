package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Internal description of an extension or built-in provider known to DelosDB.
 *
 * <p>This descriptor is the internal registry shape. It is not a public SPI
 * contract and must not expose Derby Monitor classes.</p>
 */
@InternalApi
public record ExtensionDescriptor(
        ExtensionType type,
        String name,
        String version,
        ExtensionState state,
        List<String> capabilities
) {
    public ExtensionDescriptor {
        type = Objects.requireNonNull(type, "type");
        name = normalizeName(name);
        version = normalizeVersion(version);
        state = Objects.requireNonNull(state, "state");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    public static ExtensionDescriptor available(ExtensionType type, String name) {
        return new ExtensionDescriptor(type, name, BuiltInExtensions.BUILTIN_VERSION, ExtensionState.AVAILABLE, List.of());
    }

    public static ExtensionDescriptor builtIn(ExtensionType type, String name, List<String> capabilities) {
        return new ExtensionDescriptor(type, name, BuiltInExtensions.BUILTIN_VERSION, ExtensionState.ENABLED, capabilities);
    }

    public static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Extension name must not be blank");
        }
        return normalized;
    }

    private static String normalizeVersion(String version) {
        Objects.requireNonNull(version, "version");
        String normalized = version.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Extension version must not be blank");
        }
        return normalized;
    }
}
