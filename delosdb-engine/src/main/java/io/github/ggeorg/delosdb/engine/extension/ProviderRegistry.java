package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Shared internal registry for DelosDB provider families.
 *
 * <p>This class keeps provider-family registration logic in one place while
 * the typed registries remain small product-facing adapters for index,
 * storage, function, and future provider kinds.</p>
 */
@InternalApi
public final class ProviderRegistry<P> {
    private final String providerKind;
    private final ExtensionType extensionType;
    private final Function<P, String> providerName;
    private final ProviderDescriptorFactory<P> descriptorFactory;
    private final InMemoryExtensionRegistry descriptors = new InMemoryExtensionRegistry();
    private final Map<String, P> providersByName = new LinkedHashMap<>();

    public ProviderRegistry(
            String providerKind,
            ExtensionType extensionType,
            Function<P, String> providerName,
            ProviderDescriptorFactory<P> descriptorFactory) {
        this.providerKind = requireText(providerKind, "providerKind");
        this.extensionType = Objects.requireNonNull(extensionType, "extensionType");
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.descriptorFactory = Objects.requireNonNull(descriptorFactory, "descriptorFactory");
    }

    public synchronized void registerEnabled(P provider, String version) {
        register(provider, version, false);
    }

    public synchronized void registerEnabled(P provider) {
        registerEnabled(provider, "manual");
    }

    public synchronized void registerBuiltIn(P provider, boolean defaultProvider) {
        register(provider, BuiltInExtensions.BUILTIN_VERSION, defaultProvider);
    }

    public synchronized ProviderResolver<P> resolver() {
        return new ProviderResolver<>(
                providerKind,
                extensionType,
                descriptors,
                new ArrayList<>(providersByName.values()),
                providerName);
    }

    public synchronized ExtensionRegistry descriptors() {
        return descriptors;
    }

    public synchronized List<P> providers() {
        return List.copyOf(providersByName.values());
    }

    private void register(P provider, String version, boolean defaultProvider) {
        Objects.requireNonNull(provider, "provider");
        String name = ExtensionDescriptor.normalizeName(providerName.apply(provider));
        if (providersByName.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate " + providerKind + ": " + name);
        }
        descriptors.register(descriptorFactory.create(provider, version, defaultProvider));
        providersByName.put(name, provider);
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    @FunctionalInterface
    public interface ProviderDescriptorFactory<P> {
        ExtensionDescriptor create(P provider, String version, boolean defaultProvider);
    }
}
