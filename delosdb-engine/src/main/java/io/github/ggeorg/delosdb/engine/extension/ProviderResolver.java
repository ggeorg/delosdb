package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared internal resolver for DelosDB provider families.
 */
@InternalApi
public final class ProviderResolver<P> {
    private final String providerKind;
    private final ExtensionType extensionType;
    private final ExtensionRegistry registry;
    private final Map<String, P> providersByName;

    public ProviderResolver(
            String providerKind,
            ExtensionType extensionType,
            ExtensionRegistry registry,
            List<P> providers,
            Function<P, String> providerName) {
        this.providerKind = requireText(providerKind, "providerKind");
        this.extensionType = Objects.requireNonNull(extensionType, "extensionType");
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(providerName, "providerName");
        Map<String, P> providersByName = new LinkedHashMap<>();
        for (P provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String name = ExtensionDescriptor.normalizeName(providerName.apply(provider));
            P previous = providersByName.put(name, provider);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate " + providerKind + ": " + name);
            }
        }
        this.providersByName = Map.copyOf(providersByName);
    }

    public Optional<P> findEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        return registry.find(extensionType, normalizedName)
                .filter(descriptor -> descriptor.state() == ExtensionState.ENABLED)
                .flatMap(descriptor -> Optional.ofNullable(providersByName.get(descriptor.name())));
    }

    public P requireEnabled(String name) {
        String normalizedName = ExtensionDescriptor.normalizeName(name);
        ExtensionDescriptor descriptor = registry.find(extensionType, normalizedName)
                .orElseThrow(() -> new ExtensionResolutionException(
                        providerKindLabel() + " is not registered: " + normalizedName));
        if (descriptor.state() != ExtensionState.ENABLED) {
            throw new ExtensionResolutionException(
                    providerKindLabel() + " is not enabled: " + normalizedName + " (state=" + descriptor.state() + ")");
        }
        P provider = providersByName.get(descriptor.name());
        if (provider == null) {
            throw new ExtensionResolutionException(
                    providerKindLabel() + " descriptor has no implementation adapter: " + normalizedName);
        }
        return provider;
    }

    public List<P> providers() {
        return List.copyOf(providersByName.values());
    }

    private String providerKindLabel() {
        if (providerKind.isEmpty()) {
            return "Provider";
        }
        return Character.toUpperCase(providerKind.charAt(0)) + providerKind.substring(1);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }
}
