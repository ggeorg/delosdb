package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Simple in-memory implementation used to establish the internal registry seam.
 *
 * <p>This is not extension discovery and not a public plugin container. It gives
 * DelosDB a concrete place to register built-in provider descriptors before a
 * later adapter bridges them into Derby Monitor services.</p>
 */
@InternalApi
public final class InMemoryExtensionRegistry implements ExtensionRegistry {
    private final Map<Key, ExtensionDescriptor> descriptors = new LinkedHashMap<>();

    @Override
    public synchronized void register(ExtensionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        descriptors.put(Key.from(descriptor.type(), descriptor.name()), descriptor);
    }

    @Override
    public synchronized Optional<ExtensionDescriptor> find(ExtensionType type, String name) {
        return Optional.ofNullable(descriptors.get(Key.from(type, name)));
    }

    @Override
    public synchronized List<ExtensionDescriptor> descriptors() {
        return List.copyOf(new ArrayList<>(descriptors.values()));
    }

    private record Key(ExtensionType type, String name) {
        private Key {
            type = Objects.requireNonNull(type, "type");
            name = ExtensionDescriptor.normalizeName(name);
        }

        private static Key from(ExtensionType type, String name) {
            return new Key(type, name);
        }
    }
}
