/*

   Derby - Class org.apache.derby.impl.store.access.btree.BTreeInsertRoutingSnapshots

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

/**
 * Validated root-plus-branch routing for the experimental B-tree INSERT path.
 * RawStore pages remain authoritative; any snapshot change releases the
 * acquired page and returns {@code null} so the caller can use the inherited
 * latched-root descent.
 */
final class BTreeInsertRoutingSnapshots {
    private BTreeInsertRoutingSnapshots() {
    }

    static ControlRow search(
            BTree btree, OpenBTree openBtree, SearchParameters params)
            throws StandardException {
        BTree.RootRoutingSnapshot root = btree.insertRootRoutingSnapshot();
        if (root == null || root.rootLevel != 2) {
            return null;
        }

        long branchPageNumber = root.search(params, btree);
        if (btree.insertRootRoutingSnapshot() != root) {
            return null;
        }

        BTreeBranchRoutingSnapshots snapshots =
                btree.insertBranchRoutingSnapshots();
        BTreeBranchRoutingSnapshots.Snapshot branch =
                snapshots.get(branchPageNumber);
        if (branch == null) {
            branch = observeBranch(
                    btree, openBtree, root, snapshots, branchPageNumber);
            if (branch == null) {
                return null;
            }
        }

        long leafPageNumber = branch.route(
                params.searchKey, params.partial_key_match_op, btree);
        if (!btree.insertRoutingSnapshotsStillCurrent(
                root, snapshots, branchPageNumber, branch)) {
            return null;
        }

        ControlRow leaf = ControlRow.get(openBtree, leafPageNumber);
        if (!btree.insertRoutingSnapshotsStillCurrent(
                root, snapshots, branchPageNumber, branch)) {
            leaf.release();
            return null;
        }

        ControlRow result = leaf.search(params);
        if (btree.insertRoutingSnapshotsStillCurrent(
                root, snapshots, branchPageNumber, branch)) {
            return result;
        }
        result.release();
        return null;
    }

    private static BTreeBranchRoutingSnapshots.Snapshot observeBranch(
            BTree btree,
            OpenBTree openBtree,
            BTree.RootRoutingSnapshot root,
            BTreeBranchRoutingSnapshots snapshots,
            long branchPageNumber) throws StandardException {
        ControlRow control = ControlRow.get(openBtree, branchPageNumber);
        try {
            if (btree.insertRootRoutingSnapshot() != root
                    || !(control instanceof BranchControlRow branch)
                    || branch.getLevel() != 1) {
                return null;
            }
            return snapshots.observe(branch, openBtree, btree);
        } finally {
            control.release();
        }
    }
}
