package io.github.ggeorg.delosdb.spi.function;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.List;

/**
 * Experimental DelosDB contract for SQL function provider implementations.
 *
 * <p>FunctionProvider v0 is metadata-only. It establishes provider identity and
 * function metadata before external loading or execution hooks are exposed.</p>
 */
@ExperimentalSpi("Initial function provider contract; external loading and execution hooks are intentionally deferred.")
public interface FunctionProvider {
    /**
     * Returns the stable provider name used by DelosDB metadata.
     */
    String name();

    /**
     * Returns the SQL functions owned by this provider.
     */
    List<FunctionDescriptor> functions();

    /**
     * Describes this provider's capabilities for a specific function.
     */
    FunctionCapabilities capabilities(FunctionDescriptor descriptor);
}
