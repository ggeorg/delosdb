package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

/**
 * Raised when an internal DelosDB extension descriptor cannot be resolved to an
 * enabled implementation adapter.
 *
 * <p>This exception is part of the internal bridge layer above Derby Monitor.
 * It is deliberately not a public SPI exception.</p>
 */
@InternalApi
public final class ExtensionResolutionException extends IllegalStateException {
    public ExtensionResolutionException(String message) {
        super(message);
    }
}
