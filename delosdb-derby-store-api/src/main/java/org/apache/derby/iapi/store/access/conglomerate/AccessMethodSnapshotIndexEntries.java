/*

   Derby - Class org.apache.derby.iapi.store.access.conglomerate.AccessMethodSnapshotIndexEntries

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.iapi.store.access.conglomerate;

import org.apache.derby.shared.common.error.StandardException;

/**
 * Optional base-table controller hook for access methods that version their
 * rows while the secondary-index entries pointing at them are not versioned.
 *
 * <p>A secondary-index scan opened with
 * {@link org.apache.derby.iapi.store.access.TransactionController#OPENMODE_SNAPSHOT_READ}
 * returns deleted-marked entries too and keeps those the reader's snapshot
 * sees. It asks the base controller it opened with
 * {@link org.apache.derby.iapi.store.access.TransactionController#OPENMODE_FOR_LOCK_ONLY},
 * so implementations must answer on such a controller, and its
 * {@code fetch} must read the version visible to the snapshot. While the
 * leaf is latched the scan asks whether the deleted mark decides an entry;
 * otherwise, after releasing the latch, it fetches the row's indexed
 * columns and keeps the entry when they equal the entry's key.
 */
public interface AccessMethodSnapshotIndexEntries {
    /**
     * Fixes the reader's snapshot when the index scan opens, so a read that
     * finds no entry still fixes it.
     */
    void beginSnapshotIndexRead() throws StandardException;

    /**
     * Whether an index entry's deleted mark, read now, decides its visibility:
     * the entry is visible exactly when it is not marked deleted. Reads no
     * pages, so the index calls it while holding the leaf latch.
     */
    boolean deletedMarkDecides();
}
