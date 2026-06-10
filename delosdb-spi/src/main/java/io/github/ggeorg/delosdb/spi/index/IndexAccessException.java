package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Provider-neutral failure raised by a physical index access implementation.
 *
 * <p>Implementations must translate engine-specific failures into this type
 * rather than exposing Derby, H2, MapDB, or other backend exceptions through the
 * public DelosDB SPI.</p>
 */
@ExperimentalSpi("Initial exception boundary for physical index access implementations.")
public class IndexAccessException extends Exception {
    public IndexAccessException(String message) {
        super(message);
    }

    public IndexAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
