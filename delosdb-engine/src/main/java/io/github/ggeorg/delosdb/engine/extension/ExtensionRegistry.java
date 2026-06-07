package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

import java.util.List;
import java.util.Optional;

/**
 * Internal registry for DelosDB extension descriptors.
 *
 * <p>This is the first DelosDB-side layer above Derby's Monitor/module system.
 * It is intentionally internal and does not perform discovery, catalog writes,
 * or provider loading yet.</p>
 */
@InternalApi
public interface ExtensionRegistry {
    void register(ExtensionDescriptor descriptor);

    Optional<ExtensionDescriptor> find(ExtensionType type, String name);

    List<ExtensionDescriptor> descriptors();
}
