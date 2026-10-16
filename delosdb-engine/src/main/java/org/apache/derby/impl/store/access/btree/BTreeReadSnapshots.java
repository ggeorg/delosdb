/*

   Derby - Class org.apache.derby.impl.store.access.btree.BTreeReadSnapshots

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
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Immutable decoded copies of one B-tree's root page, level-1 branch pages
 * and leaf pages. Inserts route through them, and keyed reads answer exact
 * keys and key prefixes from them, without latching those pages.
 *
 * <p>RawStore pages remain the authority. A page mutation invalidates its
 * copy through the page's auxiliary-object hook (see {@link ControlRow})
 * before any bytes change. Copies are built and refreshed only under the
 * page's latch. Every read revalidates the copies it used and returns null
 * when one changed, so the caller falls back to the inherited latched
 * descent.</p>
 */
final class BTreeReadSnapshots {
    private final BTree btree;

    /** Routing state of the current root branch page, or null. */
    private volatile RootRoutingSnapshot rootSnapshot;

    /** Routing state of stable level-1 branch pages, created on first use. */
    private volatile BTreeBranchRoutingSnapshots branchSnapshots;

    /** Decoded rows of stable leaf pages, created on first use. */
    private volatile ConcurrentHashMap<Long, LeafReadSnapshot> leafSnapshots;

    BTreeReadSnapshots(BTree btree) {
        this.btree = btree;
    }

    /**
     * Searches the latched root page through its copy, building or
     * refreshing the copy first.
     */
    long searchRoot(BranchControlRow root, OpenBTree openBtree, SearchParameters params)
            throws StandardException {
        long pageVersion = root.page.getPageVersion();
        RootRoutingSnapshot snapshot = rootSnapshot;
        if (snapshot == null || snapshot.pageVersion != pageVersion) {
            snapshot = RootRoutingSnapshot.fromLatchedRoot(root, openBtree, pageVersion);
            rootSnapshot = snapshot;
        }
        root.observeRootRoutingSnapshot(this);
        return snapshot.search(params, btree);
    }

    /** Descends from the root copy to the latched child the search selects, or null. */
    ControlRow searchFromRoot(OpenBTree openBtree, SearchParameters params)
            throws StandardException {
        RootRoutingSnapshot snapshot = rootSnapshot;
        if (snapshot == null) {
            return null;
        }

        ControlRow child = ControlRow.get(openBtree, snapshot.search(params, btree));
        if (rootSnapshot != snapshot) {
            child.release();
            return null;
        }

        ControlRow result = child.search(params);
        if (rootSnapshot == snapshot) {
            return result;
        }

        result.release();
        return null;
    }

    /**
     * Routes an insert through the root and branch copies of a two-level
     * tree to its latched leaf, or returns null.
     */
    ControlRow searchForInsert(OpenBTree openBtree, SearchParameters params)
            throws StandardException {
        RootRoutingSnapshot root = rootSnapshot;
        if (root == null || root.rootLevel != 2) {
            return null;
        }

        long branchPageNumber = root.search(params, btree);
        if (rootSnapshot != root) {
            return null;
        }

        BTreeBranchRoutingSnapshots branches = branchSnapshots();
        BTreeBranchRoutingSnapshots.Snapshot branch = branches.get(branchPageNumber);
        if (branch == null) {
            branch = observeBranch(openBtree, root, branchPageNumber);
            if (branch == null) {
                return null;
            }
        }

        long leafPageNumber = branch.route(params.searchKey, params.partial_key_match_op, btree);
        if (!routeStillCurrent(root, branches, branchPageNumber, branch)) {
            return null;
        }

        ControlRow leaf = ControlRow.get(openBtree, leafPageNumber);
        if (!routeStillCurrent(root, branches, branchPageNumber, branch)) {
            leaf.release();
            return null;
        }

        ControlRow result = leaf.search(params);
        if (routeStillCurrent(root, branches, branchPageNumber, branch)) {
            return result;
        }
        result.release();
        return null;
    }

    /** One more than the root copy's level, or 0 when there is no copy. */
    int treeHeight() {
        RootRoutingSnapshot snapshot = rootSnapshot;
        return snapshot == null ? 0 : snapshot.rootLevel + 1;
    }

    void invalidateRoot() {
        rootSnapshot = null;
    }

    /** Builds or refreshes the copy of a latched leaf page. */
    void observeLeaf(LeafControlRow leaf, OpenBTree openBtree) throws StandardException {
        long pageNumber = leaf.page.getPageNumber();
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots();
        LeafReadSnapshot current = snapshots.get(pageNumber);
        long pageVersion = leaf.page.getPageVersion();
        if (current == null || current.pageVersion != pageVersion) {
            current = LeafReadSnapshot.fromLatchedLeaf(leaf, openBtree, pageVersion);
            snapshots.put(pageNumber, current);
            BTreePointReadDiagnostics.increment(
                    BTreePointReadDiagnostics.LEAF_SNAPSHOT_OBSERVATIONS);
        }
        leaf.observeLeafReadSnapshot(this);
    }

    /** An exact-key read through the root and leaf snapshots of a one-level btree, or null. */
    LeafReadSnapshotHit exact(StoreDataValue[] searchKey)
            throws StandardException {
        RootRoutingSnapshot root = rootSnapshot;
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots;
        if (root == null || root.rootLevel != 1 || snapshots == null) {
            return null;
        }

        long leafPageNumber = root.route(
                searchKey, SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH, btree);
        if (rootSnapshot != root) {
            return null;
        }
        LeafReadSnapshot leaf = snapshots.get(leafPageNumber);
        if (leaf == null) {
            return null;
        }
        StoreDataValue[] row = leaf.searchExact(searchKey, btree);
        if (row == null || rootSnapshot != root
                || snapshots.get(leafPageNumber) != leaf) {
            return null;
        }
        return new LeafReadSnapshotHit(root, leafPageNumber, leaf, row);
    }

    /**
     * The rows matching a key prefix through the root, branch and leaf
     * snapshots of a two-level btree, or null.
     */
    LeafReadSnapshotPrefixHit prefix(
            OpenBTree openBtree, StoreDataValue[] searchKey, boolean skipDeleted)
            throws StandardException {
        RootRoutingSnapshot root = rootSnapshot;
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots;
        if (root == null || root.rootLevel != 2 || snapshots == null) {
            return null;
        }

        long branchPageNumber = root.route(
                searchKey, SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH, btree);
        if (rootSnapshot != root) {
            return null;
        }

        BTreeBranchRoutingSnapshots.Snapshot branchSnapshot = null;
        BTreeBranchRoutingSnapshots branches = branchSnapshots;
        long leafPageNumber = ContainerHandle.INVALID_PAGE_NUMBER;

        if (branches != null) {
            branchSnapshot = branches.get(branchPageNumber);
            if (branchSnapshot != null) {
                leafPageNumber = branchSnapshot.route(
                        searchKey,
                        SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                        btree);
                if (rootSnapshot != root
                        || branchSnapshots != branches
                        || !branches.isCurrent(branchPageNumber, branchSnapshot)) {
                    return null;
                }
            }
        }

        if (leafPageNumber == ContainerHandle.INVALID_PAGE_NUMBER) {
            branchSnapshot = observeBranch(openBtree, root, branchPageNumber);
            if (branchSnapshot == null) {
                return null;
            }
            branches = branchSnapshots;
            leafPageNumber = branchSnapshot.route(
                    searchKey,
                    SearchParameters.POSITION_LEFT_OF_PARTIAL_KEY_MATCH,
                    btree);
        }

        if (rootSnapshot != root
                || (branchSnapshot != null
                && (branches == null
                || branchSnapshots != branches
                || !branches.isCurrent(branchPageNumber, branchSnapshot)))) {
            return null;
        }
        LeafReadSnapshot leaf = snapshots.get(leafPageNumber);
        if (leaf == null) {
            return null;
        }
        StoreDataValue[][] rows = leaf.searchExactPrefix(searchKey, btree, skipDeleted);
        if (rows == null || rootSnapshot != root
                || snapshots.get(leafPageNumber) != leaf) {
            return null;
        }
        return new LeafReadSnapshotPrefixHit(root, leafPageNumber, leaf, rows);
    }

    /** Whether the copies a hit was read from are still current. */
    boolean stillCurrent(LeafReadSnapshotHit hit) {
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots;
        return hit != null
                && rootSnapshot == hit.rootToken
                && snapshots != null
                && snapshots.get(hit.pageNumber) == hit.leaf;
    }

    void invalidateLeaf(long pageNumber) {
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots;
        if (snapshots != null && snapshots.remove(pageNumber) != null) {
            BTreePointReadDiagnostics.increment(
                    BTreePointReadDiagnostics.LEAF_SNAPSHOT_INVALIDATIONS);
        }
    }

    void invalidateBranch(long pageNumber) {
        BTreeBranchRoutingSnapshots snapshots = branchSnapshots;
        if (snapshots != null) {
            snapshots.invalidate(pageNumber);
        }
    }

    /** Latches the branch page and copies it, unless the root copy changed or it is not level 1. */
    private BTreeBranchRoutingSnapshots.Snapshot observeBranch(
            OpenBTree openBtree, RootRoutingSnapshot root, long branchPageNumber)
            throws StandardException {
        ControlRow control = ControlRow.get(openBtree, branchPageNumber);
        try {
            if (rootSnapshot != root
                    || !(control instanceof BranchControlRow branch)
                    || branch.getLevel() != 1) {
                return null;
            }
            return branchSnapshots().observe(branch, openBtree, this);
        } finally {
            control.release();
        }
    }

    private boolean routeStillCurrent(
            RootRoutingSnapshot root,
            BTreeBranchRoutingSnapshots branches,
            long branchPageNumber,
            BTreeBranchRoutingSnapshots.Snapshot branch) {
        return rootSnapshot == root
                && branchSnapshots == branches
                && branches.isCurrent(branchPageNumber, branch);
    }

    private BTreeBranchRoutingSnapshots branchSnapshots() {
        BTreeBranchRoutingSnapshots snapshots = branchSnapshots;
        if (snapshots == null) {
            synchronized (this) {
                snapshots = branchSnapshots;
                if (snapshots == null) {
                    snapshots = new BTreeBranchRoutingSnapshots();
                    branchSnapshots = snapshots;
                }
            }
        }
        return snapshots;
    }

    private ConcurrentHashMap<Long, LeafReadSnapshot> leafSnapshots() {
        ConcurrentHashMap<Long, LeafReadSnapshot> snapshots = leafSnapshots;
        if (snapshots == null) {
            synchronized (this) {
                snapshots = leafSnapshots;
                if (snapshots == null) {
                    snapshots = new ConcurrentHashMap<Long, LeafReadSnapshot>();
                    leafSnapshots = snapshots;
                }
            }
        }
        return snapshots;
    }

    static final class RootRoutingSnapshot {
        private final long pageVersion;
        final int rootLevel;
        private final StoreDataValue[][] branchRows;
        private final long[] childPageIds;

        private RootRoutingSnapshot(
                long pageVersion, int rootLevel,
                StoreDataValue[][] branchRows, long[] childPageIds) {
            this.pageVersion = pageVersion;
            this.rootLevel = rootLevel;
            this.branchRows = branchRows;
            this.childPageIds = childPageIds;
        }

        private static RootRoutingSnapshot fromLatchedRoot(
                BranchControlRow root, OpenBTree openBtree, long pageVersion)
                throws StandardException {
            int slotCount = root.page.recordCount();
            StoreDataValue[][] branchRows =
                    new StoreDataValue[Math.max(0, slotCount - 1)][];
            long[] childPageIds = new long[slotCount];
            childPageIds[0] = root.getLeftChildPageno();

            for (int slot = 1; slot < slotCount; slot++) {
                StoreDataValue[] row = BranchRow.createEmptyTemplate(
                        openBtree.getRawTran(), openBtree.getConglomerate()).getRow();
                root.page.fetchFromSlot(null, slot, row, null, true);
                branchRows[slot - 1] = row;
                childPageIds[slot] = StoreTypeUtil.getLong(
                        row[openBtree.getConglomerate().nKeyFields]);
            }

            return new RootRoutingSnapshot(
                    pageVersion, root.getLevel(), branchRows, childPageIds);
        }

        long search(SearchParameters params, BTree btree)
                throws StandardException {
            int leftSlot = 0;
            int rightSlot = branchRows.length + 1;
            int leftRange = 1;
            int rightRange = branchRows.length;

            while (leftSlot != rightSlot - 1) {
                int midSlot = (leftRange + rightRange) / 2;
                int comparison = ControlRow.compareIndexRowToKey(
                        branchRows[midSlot - 1],
                        params.searchKey,
                        btree.nUniqueColumns,
                        params.partial_key_match_op,
                        btree.ascDescInfo);

                if (comparison == 0) {
                    params.resultSlot = midSlot;
                    params.resultExact = true;
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

            params.resultSlot = leftSlot;
            params.resultExact = false;
            return childPageIds[leftSlot];
        }

        private long route(
                StoreDataValue[] searchKey, int partialKeyMatchOp, BTree btree)
                throws StandardException {
            return BTreeBranchRoutingSnapshots.routeBranchRows(
                    branchRows, childPageIds, searchKey, partialKeyMatchOp, btree);
        }
    }
}
