/*

   Derby - Class org.apache.derby.impl.store.access.btree.index.B2IBaseTable

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.btree.index;

import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodDeletedIndexEntries;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.impl.store.access.btree.BTreeDeletedEntryReclaimer;
import org.apache.derby.shared.common.error.StandardException;

/**
 * What a B2I index learns from its base table. A base that keeps deleted
 * index entries while a snapshot can need them (MVCC) gives its unique
 * indexes a physical key that includes the row location, and decides which
 * deleted entries no snapshot needs any more.
 */
final class B2IBaseTable {
    private B2IBaseTable() {
    }

    /** Whether the index's base table keeps deleted index entries for snapshots. */
    static boolean keepsDeletedIndexEntries(B2I index, TransactionManager xact_manager)
            throws StandardException {
        // The base table lives in the index's segment.
        return xact_manager.findExistingConglomerateFromKey(
                new ContainerKey(
                        index.getId().getSegmentId(),
                        xact_manager.findContainerid(index.baseConglomerateId)))
                .keepsDeletedIndexEntries();
    }

    /**
     * Purges the deleted entries the base says no snapshot needs. Other bases
     * are not opened, so their locks are left alone.
     */
    static void purgeEntriesNoSnapshotNeeds(B2I index, TransactionManager xact_manager)
            throws StandardException {
        if (!keepsDeletedIndexEntries(index, xact_manager)) {
            return;
        }
        ConglomerateController base_cc = index.lockTable(
                xact_manager, 0, TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_REPEATABLE_READ);
        try {
            BTreeDeletedEntryReclaimer.reclaim(
                    xact_manager, index, (AccessMethodDeletedIndexEntries) base_cc);
        } finally {
            base_cc.close();
        }
    }
}
