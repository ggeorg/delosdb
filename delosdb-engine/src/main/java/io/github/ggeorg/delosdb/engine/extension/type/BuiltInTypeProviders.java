package io.github.ggeorg.delosdb.engine.extension.type;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.type.TypeProvider;

import java.util.List;

/**
 * Built-in TypeProvider instances known to the engine.
 */
@InternalApi
public final class BuiltInTypeProviders {
    private static final TypeProvider DERBY = new BuiltInDerbyTypeProvider();

    private BuiltInTypeProviders() {
    }

    public static TypeProvider derby() {
        return DERBY;
    }

    public static List<TypeProvider> all() {
        return List.of(DERBY);
    }
}
