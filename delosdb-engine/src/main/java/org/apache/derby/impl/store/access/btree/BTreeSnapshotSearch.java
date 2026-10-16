/*

   Derby - Class org.apache.derby.impl.store.access.btree.BTreeSnapshotSearch

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

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Reads that answer an exact key or key prefix from a B-tree's routing and
 * leaf snapshots without latching pages. Each search revalidates the
 * snapshots it used and returns null whenever one changed, so the caller
 * falls back to the latched path.
 */
final class BTreeSnapshotSearch {
    private BTreeSnapshotSearch() {
    }

    /** An exact-key read through the root and leaf snapshots of a one-level tree, or null. */
    static LeafReadSnapshotHit exact(BTree tree, StoreDataValue[] searchKey)
            throws StandardException {
        BTree.RootRoutingSnapshot root = tree.rootRoutingSnapshot;
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = tree.leafReadSnapshots;
        if (root == null || root.rootLevel != 1 || snapshots == null) {
            return null;
        }

        long leafPageNumber = root.route(
                searchKey, SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH, tree);
        if (tree.rootRoutingSnapshot != root) {
            return null;
        }
        LeafReadSnapshot leaf = snapshots.get(leafPageNumber);
        if (leaf == null) {
            return null;
        }
        StoreDataValue[] row = leaf.searchExact(searchKey, tree);
        if (row == null || tree.rootRoutingSnapshot != root
                || snapshots.get(leafPageNumber) != leaf) {
            return null;
        }
        return new LeafReadSnapshotHit(root, leafPageNumber, leaf, row);
    }

    /**
     * The rows matching a key prefix through the root, branch and leaf
     * snapshots of a two-level tree, or null.
     */
    static LeafReadSnapshotPrefixHit prefix(
            BTree tree, OpenBTree openBtree, StoreDataValue[] searchKey, boolean skipDeleted)
            throws StandardException {
        BTree.RootRoutingSnapshot root = tree.rootRoutingSnapshot;
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = tree.leafReadSnapshots;
        if (root == null || root.rootLevel != 2 || snapshots == null) {
            return null;
        }

        long branchPageNumber = root.route(
                searchKey, SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH, tree);
        if (tree.rootRoutingSnapshot != root) {
            return null;
        }

        BTreeBranchRoutingSnapshots.Snapshot branchSnapshot = null;
        BTreeBranchRoutingSnapshots branchSnapshots = tree.branchRoutingSnapshots;
        long leafPageNumber = ContainerHandle.INVALID_PAGE_NUMBER;

        if (branchSnapshots != null) {
            branchSnapshot = branchSnapshots.get(branchPageNumber);
            if (branchSnapshot != null) {
                leafPageNumber = branchSnapshot.route(
                        searchKey,
                        SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                        tree);
                if (tree.rootRoutingSnapshot != root
                        || tree.branchRoutingSnapshots != branchSnapshots
                        || !branchSnapshots.isCurrent(branchPageNumber, branchSnapshot)) {
                    return null;
                }
            }
        }

        if (leafPageNumber == ContainerHandle.INVALID_PAGE_NUMBER) {
            ControlRow control = ControlRow.get(openBtree, branchPageNumber);
            try {
                if (!(control instanceof BranchControlRow branch)
                        || branch.getLevel() != 1) {
                    return null;
                }
                branchSnapshot = tree.observeBranchRoutingSnapshot(branch, openBtree);
                branchSnapshots = tree.branchRoutingSnapshots;
                leafPageNumber = branchSnapshot.route(
                        searchKey,
                        SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                        tree);
            } finally {
                control.release();
            }
        }

        if (tree.rootRoutingSnapshot != root
                || (branchSnapshot != null
                && (branchSnapshots == null
                || tree.branchRoutingSnapshots != branchSnapshots
                || !branchSnapshots.isCurrent(branchPageNumber, branchSnapshot)))) {
            return null;
        }
        LeafReadSnapshot leaf = snapshots.get(leafPageNumber);
        if (leaf == null) {
            return null;
        }
        StoreDataValue[][] rows = leaf.searchExactPrefix(searchKey, tree, skipDeleted);
        if (rows == null || tree.rootRoutingSnapshot != root
                || snapshots.get(leafPageNumber) != leaf) {
            return null;
        }
        return new LeafReadSnapshotPrefixHit(root, leafPageNumber, leaf, rows);
    }
}
