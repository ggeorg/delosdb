package io.github.ggeorg.delosdb.engine.extension;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;

/**
 * Internal lifecycle states for extensions known to DelosDB.
 *
 * <p>The initial registry is intentionally in-memory only. Catalog-backed
 * lifecycle state will be introduced only after the bridge contract is stable.</p>
 */
@InternalApi
public enum ExtensionState {
    AVAILABLE,
    INSTALLED,
    ENABLED,
    DISABLED,
    MISSING,
    INCOMPATIBLE,
    UNTRUSTED
}
