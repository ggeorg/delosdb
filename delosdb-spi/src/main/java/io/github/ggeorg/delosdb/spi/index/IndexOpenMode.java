package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;

/**
 * Physical index access mode requested by the DelosDB bridge.
 */
@ExperimentalSpi("Initial physical index open modes; lifecycle mapping is not yet finalized.")
public enum IndexOpenMode {
    /** Open an already-existing physical index. */
    OPEN_EXISTING,

    /** Create a new physical index. */
    CREATE,

    /** Rebuild physical index contents from base-table rows. */
    REBUILD
}
