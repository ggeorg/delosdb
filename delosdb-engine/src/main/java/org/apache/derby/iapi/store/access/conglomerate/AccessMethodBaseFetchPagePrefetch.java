/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodBaseFetchPagePrefetch

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional access-method hook for bounded base-row physical prefetch.
 *
 * <p>The SQL engine may offer row locations which are already buffered by an
 * existing scan. Implementations may use the batch only to amortize internal
 * physical lookup work. They must not retain storage latches after this method
 * returns and must not change logical row order or cursor position.
 */
public interface AccessMethodBaseFetchPagePrefetch {
    /** Whether this controller currently accepts bounded base-fetch prefetch. */
    boolean baseFetchPagePrefetchEnabled();

    /**
     * Prefetch internal physical metadata for the supplied row locations.
     * A zero count clears any unconsumed state.
     */
    void prefetchBaseRows(StoreRowLocation[] rowLocations, int count) throws StandardException;
}
