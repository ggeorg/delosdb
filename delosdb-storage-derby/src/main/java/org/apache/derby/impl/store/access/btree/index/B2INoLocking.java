/*

   Derby - Class org.apache.derby.impl.store.access.btree.index.B2INoLocking

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.store.access.btree.index;

import org.apache.derby.impl.store.access.btree.BTree;
import org.apache.derby.impl.store.access.btree.BTreeLockingPolicy;
import org.apache.derby.impl.store.access.btree.BTreeRowPosition;
import org.apache.derby.impl.store.access.btree.LeafControlRow;
import org.apache.derby.impl.store.access.btree.OpenBTree;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * B-tree locking policy used when no base-row locking is required.
 *
 * <p>All inherited operations succeed without requesting a logical row lock.
 * Structural B-tree synchronization and RawStore page latching remain owned by
 * the normal B-tree/RawStore path.</p>
 */
public class B2INoLocking implements BTreeLockingPolicy {

    public B2INoLocking(
            Transaction rawtran,
            int lock_level,
            LockingPolicy locking_policy,
            ConglomerateController base_cc,
            OpenBTree open_btree) {
    }

    protected B2INoLocking() {
    }

    public boolean lockScanCommittedDeletedRow(
            OpenBTree open_btree,
            LeafControlRow leaf,
            StoreDataValue[] template,
            FetchDescriptor lock_fetch_desc,
            int slot_no) throws StandardException {
        return true;
    }

    public boolean lockScanRow(
            OpenBTree open_btree,
            BTreeRowPosition pos,
            FetchDescriptor lock_fetch_desc,
            StoreDataValue[] lock_template,
            StoreRowLocation lock_row_loc,
            boolean previous_key_lock,
            boolean forUpdate,
            int lock_operation) throws StandardException {
        return true;
    }

    /**
     * No-locking scans may consume an immutable prefix-candidate snapshot.
     * There is no row-lock acquisition or release protocol which requires the
     * physical leaf latch to remain held.
     */
    @Override
    public boolean supportsUnlatchedPrefixSnapshotRead() {
        return true;
    }

    public void unlockScanRecordAfterRead(
            BTreeRowPosition pos,
            boolean forUpdate) throws StandardException {
    }

    public boolean lockNonScanPreviousRow(
            LeafControlRow current_leaf,
            int current_slot,
            FetchDescriptor lock_fetch_desc,
            StoreDataValue[] lock_template,
            StoreRowLocation lock_row_loc,
            OpenBTree open_btree,
            int lock_operation,
            int lock_duration) throws StandardException {
        return true;
    }

    public boolean lockNonScanRow(
            BTree btree,
            LeafControlRow current_leaf,
            LeafControlRow aux_leaf,
            StoreDataValue[] current_row,
            int lock_operation) throws StandardException {
        return true;
    }

    public boolean lockNonScanRowOnPage(
            LeafControlRow current_leaf,
            int current_slot,
            FetchDescriptor lock_fetch_desc,
            StoreDataValue[] lock_template,
            StoreRowLocation lock_row_loc,
            int lock_operation) throws StandardException {
        return true;
    }
}
