package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.engine.extension.BuiltInExtensions;
import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.List;

/**
 * Internal registry of function providers built into the current engine.
 */
@InternalApi
public final class BuiltInFunctionProviders {
    private BuiltInFunctionProviders() {
    }

    public static FunctionProvider builtin() {
        return BuiltInFunctionProvider.INSTANCE;
    }

    public static String defaultProviderName() {
        return BuiltInExtensions.DEFAULT_FUNCTION_PROVIDER;
    }

    public static FunctionProvider defaultProvider() {
        return builtin();
    }

    public static List<FunctionProvider> all() {
        return List.of(builtin());
    }
}
