package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

/**
 * Executable DelosDB provider families registered by the engine.
 */
@InternalApi
public enum ExtensionType {
    INDEX,
    COST_MODEL
}
