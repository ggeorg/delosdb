package io.github.ggeorg.delosdb.spi.function;

import java.util.Objects;

/**
 * Provider-neutral capabilities for DelosDB SQL function providers.
 */
public record FunctionCapabilities(
        boolean scalar,
        boolean deterministic,
        boolean readsSqlData
) {
    public FunctionCapabilities {
        // Keep the constructor explicit so future flags can validate together.
    }

    /**
     * Capabilities for a deterministic scalar function that does not read SQL data.
     */
    public static FunctionCapabilities deterministicScalar() {
        return new FunctionCapabilities(true, true, false);
    }

    public static FunctionCapabilities require(FunctionCapabilities capabilities) {
        return Objects.requireNonNull(capabilities, "capabilities");
    }
}
