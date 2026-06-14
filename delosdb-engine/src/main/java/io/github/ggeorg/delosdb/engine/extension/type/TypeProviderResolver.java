package io.github.ggeorg.delosdb.engine.extension.type;

import io.github.ggeorg.delosdb.engine.extension.ProviderResolver;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.type.TypeProvider;

import java.util.List;
import java.util.Optional;

/**
 * Resolver for enabled TypeProvider implementations.
 */
@InternalApi
public final class TypeProviderResolver {
    private final ProviderResolver<TypeProvider> resolver;

    TypeProviderResolver(ProviderResolver<TypeProvider> resolver) {
        this.resolver = resolver;
    }

    public Optional<TypeProvider> findEnabled(String name) {
        return resolver.findEnabled(name);
    }

    public TypeProvider requireEnabled(String name) {
        return resolver.requireEnabled(name);
    }

    public List<TypeProvider> providers() {
        return resolver.providers();
    }
}
