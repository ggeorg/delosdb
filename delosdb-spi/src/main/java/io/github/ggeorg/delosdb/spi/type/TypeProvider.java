package io.github.ggeorg.delosdb.spi.type;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

import java.util.List;

/**
 * Experimental DelosDB contract for SQL type provider implementations.
 *
 * <p>TypeProvider v0 is metadata-only. It establishes provider identity and
 * type catalog metadata before parser, binder, storage-format, or execution
 * hooks are exposed.</p>
 */
@ExperimentalSpi("Initial type provider contract; parser/binder/storage hooks are intentionally deferred.")
public interface TypeProvider {
    /**
     * Returns the stable provider name used by DelosDB metadata.
     */
    String name();

    /**
     * Returns the SQL types owned or described by this provider.
     */
    List<TypeDescriptor> types();

    /**
     * Describes this provider's type capabilities.
     */
    TypeCapabilities capabilities();
}
