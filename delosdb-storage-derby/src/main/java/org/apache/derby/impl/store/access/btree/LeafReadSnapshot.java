/*

   Derby - Class org.apache.derby.impl.store.access.btree.LeafReadSnapshot

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

import org.apache.derby.shared.common.error.StandardException;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;

/**
 * Immutable decoded leaf-page state for exact-key read experiments.
 *
 * <p>The RawStore page remains authoritative. Instances are constructed while
 * the physical page is exclusively latched and are discarded before any page
 * mutation through {@link ControlRow#pageAboutToChange()}.</p>
 */
final class LeafReadSnapshot {
    final long pageVersion;
    private final StoreDataValue[][] rows;
    private final boolean[] deleted;
    private final boolean leftmost;
    private final boolean rightmost;

    private LeafReadSnapshot(
            long pageVersion, StoreDataValue[][] rows, boolean[] deleted,
            boolean leftmost, boolean rightmost) {
        this.pageVersion = pageVersion;
        this.rows = rows;
        this.deleted = deleted;
        this.leftmost = leftmost;
        this.rightmost = rightmost;
    }

    static LeafReadSnapshot fromLatchedLeaf(
            LeafControlRow leaf, OpenBTree openBtree, long pageVersion)
            throws StandardException {
        int count = Math.max(0, leaf.page.recordCount() - 1);
        StoreDataValue[][] rows = new StoreDataValue[count][];
        boolean[] deleted = new boolean[count];
        for (int slot = 1; slot <= count; slot++) {
            StoreDataValue[] row = leaf.getRowTemplate(openBtree);
            leaf.page.fetchFromSlot(null, slot, row, null, true);
            rows[slot - 1] = StoreValueCopySupport.cloneRow(row, true);
            deleted[slot - 1] = leaf.page.isDeletedAtSlot(slot);
        }
        return new LeafReadSnapshot(
                pageVersion, rows, deleted,
                leaf.getleftSiblingPageNumber() == org.apache.derby.iapi.store.raw.ContainerHandle.INVALID_PAGE_NUMBER,
                leaf.getrightSiblingPageNumber() == org.apache.derby.iapi.store.raw.ContainerHandle.INVALID_PAGE_NUMBER);
    }

    StoreDataValue[] searchExact(StoreDataValue[] searchKey, BTree btree)
            throws StandardException {
        int low = 0;
        int high = rows.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int comparison = ControlRow.compareIndexRowToKey(
                    rows[mid], searchKey, btree.nUniqueColumns,
                    SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                    btree.ascDescInfo);
            if (comparison == 0) {
                return deleted[mid] ? null : rows[mid];
            }
            if (comparison < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return null;
    }

    StoreDataValue[][] searchExactPrefix(StoreDataValue[] searchKey, BTree btree)
            throws StandardException {
        if (searchKey == null || searchKey.length == 0 || rows.length == 0) {
            return null;
        }
        int low = 0;
        int high = rows.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            int comparison = ControlRow.compareIndexRowToKey(
                    rows[mid], searchKey, btree.nUniqueColumns,
                    SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                    btree.ascDescInfo);
            if (comparison < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        int first = low;
        if (first >= rows.length || ControlRow.compareIndexRowToKey(
                rows[first], searchKey, btree.nUniqueColumns,
                SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                btree.ascDescInfo) != 0) {
            return null;
        }
        int last = first;
        while (last + 1 < rows.length && ControlRow.compareIndexRowToKey(
                rows[last + 1], searchKey, btree.nUniqueColumns,
                SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                btree.ascDescInfo) == 0) {
            last++;
        }

        // A matching prefix at either physical edge may continue on the
        // adjacent leaf. Fall back rather than return a truncated candidate
        // set. This deliberately sacrifices some hits for correctness.
        if ((first == 0 && !leftmost) || (last == rows.length - 1 && !rightmost)) {
            return null;
        }

        int live = 0;
        for (int index = first; index <= last; index++) {
            if (!deleted[index]) {
                live++;
            }
        }
        if (live == 0) {
            return null;
        }
        StoreDataValue[][] matches = new StoreDataValue[live][];
        int output = 0;
        for (int index = first; index <= last; index++) {
            if (!deleted[index]) {
                matches[output++] = rows[index];
            }
        }
        return matches;
    }
}

/** Identity tokens needed to validate one optimistic leaf read. */
final class LeafReadSnapshotHit {
    final Object rootToken;
    final long pageNumber;
    final LeafReadSnapshot leaf;
    final StoreDataValue[] row;

    LeafReadSnapshotHit(
            Object rootToken, long pageNumber, LeafReadSnapshot leaf,
            StoreDataValue[] row) {
        this.rootToken = rootToken;
        this.pageNumber = pageNumber;
        this.leaf = leaf;
        this.row = row;
    }
}


/** Identity tokens needed to validate one optimistic exact-prefix leaf read. */
final class LeafReadSnapshotPrefixHit {
    final Object rootToken;
    final long pageNumber;
    final LeafReadSnapshot leaf;
    final StoreDataValue[][] rows;

    LeafReadSnapshotPrefixHit(
            Object rootToken, long pageNumber, LeafReadSnapshot leaf,
            StoreDataValue[][] rows) {
        this.rootToken = rootToken;
        this.pageNumber = pageNumber;
        this.leaf = leaf;
        this.rows = rows;
    }
}
