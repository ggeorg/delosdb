/*

   Derby - Class org.apache.derby.impl.store.access.btree.BTreeBranchRoutingSnapshots

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
package org.apache.derby.impl.store.access.btree;

import java.util.concurrent.ConcurrentHashMap;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Transient immutable routing snapshots for stable level-1 B-tree branches.
 * RawStore branch pages remain authoritative and invalidate entries before
 * mutation through the existing control-row auxiliary-object lifecycle.
 */
final class BTreeBranchRoutingSnapshots {
    private static final String PROPERTY =
            "delosdb.experimental.btreePrefixBranchSnapshot";

    private final ConcurrentHashMap<Long, Snapshot> snapshots =
            new ConcurrentHashMap<>();

    static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    Snapshot get(long pageNumber) {
        return snapshots.get(pageNumber);
    }

    boolean isCurrent(long pageNumber, Snapshot snapshot) {
        return snapshots.get(pageNumber) == snapshot;
    }

    Snapshot observe(BranchControlRow branch, OpenBTree openBtree, BTree owner)
            throws StandardException {
        long pageNumber = branch.page.getPageNumber();
        Snapshot current = snapshots.get(pageNumber);
        long pageVersion = branch.page.getPageVersion();
        if (current == null || current.pageVersion != pageVersion) {
            current = Snapshot.fromLatchedBranch(branch, openBtree, pageVersion);
            snapshots.put(pageNumber, current);
        }
        branch.observeBranchRoutingSnapshot(owner);
        return current;
    }

    void invalidate(long pageNumber) {
        snapshots.remove(pageNumber);
    }

    static long routeBranchRows(
            StoreDataValue[][] branchRows,
            long[] childPageIds,
            StoreDataValue[] searchKey,
            int partialKeyMatchOp,
            BTree btree) throws StandardException {
        int leftSlot = 0;
        int rightSlot = branchRows.length + 1;
        int leftRange = 1;
        int rightRange = branchRows.length;

        while (leftSlot != rightSlot - 1) {
            int midSlot = (leftRange + rightRange) / 2;
            int comparison = ControlRow.compareIndexRowToKey(
                    branchRows[midSlot - 1], searchKey,
                    btree.nUniqueColumns, partialKeyMatchOp,
                    btree.ascDescInfo);
            if (comparison == 0) {
                return childPageIds[midSlot];
            }
            if (comparison > 0) {
                rightSlot = midSlot;
                rightRange = midSlot - 1;
            } else {
                leftSlot = midSlot;
                leftRange = midSlot + 1;
            }
        }
        return childPageIds[leftSlot];
    }

    static final class Snapshot {
        private final long pageVersion;
        private final StoreDataValue[][] branchRows;
        private final long[] childPageIds;

        private Snapshot(
                long pageVersion,
                StoreDataValue[][] branchRows,
                long[] childPageIds) {
            this.pageVersion = pageVersion;
            this.branchRows = branchRows;
            this.childPageIds = childPageIds;
        }

        private static Snapshot fromLatchedBranch(
                BranchControlRow branch, OpenBTree openBtree, long pageVersion)
                throws StandardException {
            int slotCount = branch.page.recordCount();
            StoreDataValue[][] branchRows =
                    new StoreDataValue[Math.max(0, slotCount - 1)][];
            long[] childPageIds = new long[slotCount];
            childPageIds[0] = branch.getLeftChildPageno();

            for (int slot = 1; slot < slotCount; slot++) {
                StoreDataValue[] row = BranchRow.createEmptyTemplate(
                        openBtree.getRawTran(),
                        openBtree.getConglomerate()).getRow();
                branch.page.fetchFromSlot(null, slot, row, null, true);
                branchRows[slot - 1] = row;
                childPageIds[slot] = StoreTypeUtil.getLong(
                        row[openBtree.getConglomerate().nKeyFields]);
            }

            return new Snapshot(pageVersion, branchRows, childPageIds);
        }

        long route(
                StoreDataValue[] searchKey, int partialKeyMatchOp, BTree btree)
                throws StandardException {
            return routeBranchRows(
                    branchRows, childPageIds, searchKey, partialKeyMatchOp, btree);
        }
    }
}
