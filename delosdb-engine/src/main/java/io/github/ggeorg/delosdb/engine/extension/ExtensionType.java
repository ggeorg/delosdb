package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

/**
 * Internal DelosDB extension families.
 *
 * <p>This is an engine-internal classification used by the bridge layer above
 * Derby Monitor services. It is deliberately not exported as public SPI.</p>
 */
@InternalApi
public enum ExtensionType {
    INDEX,
    STORAGE,
    FUNCTION,
    COST_MODEL,
    TYPE,
    REWRITE_RULE,
    EXTERNAL_TABLE,
    SECURITY_POLICY,
    INTERNAL
}
