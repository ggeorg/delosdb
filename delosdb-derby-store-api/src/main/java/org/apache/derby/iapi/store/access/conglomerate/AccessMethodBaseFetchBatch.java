/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodBaseFetchBatch

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional read-only access-method hook for bounded base-row batch fetch.
 *
 * <p>The SQL engine preserves logical row order. Implementations may reorder
 * only the internal physical fetch work and must populate the corresponding
 * destination slot for each supplied RowLocation.</p>
 */
public interface AccessMethodBaseFetchBatch {
    /** Whether this controller currently accepts bounded base-fetch batches. */
    boolean baseFetchBatchEnabled();

    /**
     * Fetch up to {@code count} logical base rows into corresponding destination
     * rows. {@code rowExists[i]} reports whether {@code rowLocations[i]} was
     * visible and materialized.
     */
    void fetchBaseRows(
            StoreRowLocation[] rowLocations,
            StoreDataValue[][] destRows,
            FormatableBitSet validColumns,
            boolean[] rowExists,
            int count) throws StandardException;
}
