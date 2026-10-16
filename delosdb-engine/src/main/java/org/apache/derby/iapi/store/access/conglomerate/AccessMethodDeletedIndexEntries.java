/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodDeletedIndexEntries

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional base-table controller hook for access methods whose snapshots can
 * still need secondary-index entries that a committed change marked deleted.
 *
 * <p>Such entries cannot be purged when the deleting transaction commits. A
 * secondary index purges them when its base table says no snapshot can need
 * them any more: {@link #beginDeletedEntryReclamation()} first, then
 * {@link #reclaimableRows} for the rows behind the deleted entries, and
 * {@link #unchangedSince} under the leaf latch before each purge.
 */
public interface AccessMethodDeletedIndexEntries {
    /** Token meaning entries cannot be judged now. */
    long NO_RECLAMATION = -1L;

    /**
     * Starts judging deleted entries of an index on this table. Returns a
     * token, or {@link #NO_RECLAMATION} while another transaction is writing
     * the table, since it may have marked entries it can still roll back.
     */
    long beginDeletedEntryReclamation() throws StandardException;

    /**
     * For each row location taken from a deleted-marked index entry, whether
     * no snapshot can still need that entry.
     */
    boolean[] reclaimableRows(StoreRowLocation[] rowLocations, int count)
            throws StandardException;

    /**
     * Whether no transaction has begun or ended writing the table since the
     * token was issued. Reads no pages, so the index calls it while holding
     * the leaf latch.
     */
    boolean unchangedSince(long token);
}
