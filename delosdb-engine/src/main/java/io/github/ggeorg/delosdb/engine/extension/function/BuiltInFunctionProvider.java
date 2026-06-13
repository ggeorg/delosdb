package io.github.ggeorg.delosdb.engine.extension.function;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.function.FunctionCapabilities;
import io.github.ggeorg.delosdb.spi.function.FunctionDescriptor;
import io.github.ggeorg.delosdb.spi.function.FunctionProvider;

import java.util.List;
import java.util.Objects;

/**
 * Built-in DelosDB function provider.
 *
 * <p>FunctionProvider v0 is metadata-only. The descriptor records the stable
 * provider/function identity used by later execution/catalog work.</p>
 */
@InternalApi
final class BuiltInFunctionProvider implements FunctionProvider {
    static final BuiltInFunctionProvider INSTANCE = new BuiltInFunctionProvider();

    private static final List<FunctionDescriptor> FUNCTIONS = List.of(
            FunctionDescriptor.of(
                    BuiltInFunctionProviders.defaultProviderName(),
                    "SYS",
                    "DELOS_VERSION",
                    "VARCHAR(32)",
                    List.of())
    );

    private BuiltInFunctionProvider() {
    }

    @Override
    public String name() {
        return BuiltInFunctionProviders.defaultProviderName();
    }

    @Override
    public List<FunctionDescriptor> functions() {
        return FUNCTIONS;
    }

    @Override
    public FunctionCapabilities capabilities(FunctionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return FunctionCapabilities.deterministicScalar();
    }
}
