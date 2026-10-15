/*

   Derby - Class org.apache.derby.impl.store.access.btree.BTreeDeletedEntryReclaimer

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.btree;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodDeletedIndexEntries;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Purges the deleted-marked entries of a btree that its base table says no
 * snapshot can still need.
 * <p>
 * The base decides from the row locations, which are copied from every leaf
 * first, so it judges all entries in one pass and no base page is read while
 * a leaf is latched. A leaf is then purged only if it has not changed since
 * it was read and no transaction has begun or ended writing the table. Each
 * leaf is purged in its own internal transaction, which keeps the latch
 * until it commits so the purges can be undone, as post-commit purging does.
 */
public final class BTreeDeletedEntryReclaimer
{
    private BTreeDeletedEntryReclaimer()
    {
    }

    /** The deleted-marked entries of one leaf, as read. */
    private record LeafCandidates(
        long pageNumber, long pageVersion, int[] slots, int firstRow)
    {
    }

    /**
     * Purges the reclaimable deleted-marked entries of the btree.
     *
     * @param xact_manager the transaction whose internal transactions purge
     * @param btree        the btree
     * @param base         the base table's judgement of deleted entries
     *
	 * @exception  StandardException  Standard exception policy.
     **/
    public static void reclaim(
    TransactionManager              xact_manager,
    BTree                           btree,
    AccessMethodDeletedIndexEntries base)
        throws StandardException
    {
        long token = base.beginDeletedEntryReclamation();
        if (token == AccessMethodDeletedIndexEntries.NO_RECLAMATION)
        {
            return;
        }

        List<LeafCandidates> leaves = new ArrayList<>();
        List<StoreRowLocation> rows = new ArrayList<>();
        TransactionManager reader = xact_manager.getInternalTransaction();
        try
        {
            OpenBTree open_btree = openForPurge(reader, btree);
            try
            {
                collect(open_btree, btree, leaves, rows);
            }
            finally
            {
                open_btree.close();
            }
        }
        finally
        {
            reader.commit();
            reader.destroy();
        }
        if (rows.isEmpty())
        {
            return;
        }

        boolean[] reclaimable = base.reclaimableRows(
            rows.toArray(new StoreRowLocation[0]), rows.size());

        for (LeafCandidates leaf : leaves)
        {
            if (!purgeLeaf(xact_manager, btree, base, token, leaf, reclaimable))
            {
                return;
            }
        }
    }

    private static OpenBTree openForPurge(
    TransactionManager  internal_xact,
    BTree               btree)
        throws StandardException
    {
        // Purging takes no row locks, so the btree needs no locking policy.
        OpenBTree open_btree = new OpenBTree();
        open_btree.init(
            (TransactionManager) null,
            internal_xact,
            (ContainerHandle) null,
            internal_xact.getRawStoreXact(),
            false,
            ContainerHandle.MODE_FORUPDATE,
            TransactionController.MODE_RECORD,
            (BTreeLockingPolicy) null,
            btree,
            null,
            null);
        return(open_btree);
    }

    /** Reads the deleted-marked entries of every leaf, left to right. */
    private static void collect(
    OpenBTree               open_btree,
    BTree                   btree,
    List<LeafCandidates>    leaves,
    List<StoreRowLocation>  rows)
        throws StandardException
    {
        StoreDataValue[] template =
            btree.createTemplate(open_btree.getRawTran());
        int rowLocationColumn = template.length - 1;
        FetchDescriptor rowLocationOnly =
            RowUtil.getFetchDescriptorConstant(rowLocationColumn);

        ControlRow root = ControlRow.get(open_btree, BTree.ROOTPAGEID);
        ControlRow leaf = root.searchLeft(open_btree);
        while (leaf != null)
        {
            Page page = leaf.page;
            List<Integer> slots = new ArrayList<>();
            int firstRow = rows.size();
            for (int slot = 1; slot < page.recordCount(); slot++)
            {
                if (page.isDeletedAtSlot(slot))
                {
                    page.fetchFromSlot(
                        (RecordHandle) null, slot, template,
                        rowLocationOnly, true);
                    rows.add((StoreRowLocation)
                        StoreValueCopySupport.cloneValue(
                            template[rowLocationColumn]));
                    slots.add(slot);
                }
            }
            if (!slots.isEmpty())
            {
                leaves.add(new LeafCandidates(
                    page.getPageNumber(), page.getPageVersion(),
                    slots.stream().mapToInt(Integer::intValue).toArray(),
                    firstRow));
            }
            long right = leaf.getrightSiblingPageNumber();
            leaf.release();
            leaf = right == ContainerHandle.INVALID_PAGE_NUMBER
                ? null : ControlRow.get(open_btree, right);
        }
    }

    /**
     * Purges one leaf's reclaimable entries if the leaf and the table are
     * unchanged since they were read. Returns false when a transaction has
     * begun or ended writing the table, which ends this reclamation.
     */
    private static boolean purgeLeaf(
    TransactionManager              xact_manager,
    BTree                           btree,
    AccessMethodDeletedIndexEntries base,
    long                            token,
    LeafCandidates                  candidates,
    boolean[]                       reclaimable)
        throws StandardException
    {
        boolean any = false;
        for (int index = 0; index < candidates.slots().length; index++)
        {
            any |= reclaimable[candidates.firstRow() + index];
        }
        if (!any)
        {
            return(true);
        }

        TransactionManager purger = xact_manager.getInternalTransaction();
        try
        {
            OpenBTree open_btree = openForPurge(purger, btree);
            try
            {
                ControlRow leaf =
                    ControlRow.get(open_btree, candidates.pageNumber());
                if (!base.unchangedSince(token))
                {
                    leaf.release();
                    return(false);
                }
                Page page = leaf.page;
                if (page.getPageVersion() != candidates.pageVersion())
                {
                    leaf.release();
                    return(true);
                }
                // Purge from the highest slot so lower slots keep their
                // numbers. The latch is kept until the commit below.
                for (int index = candidates.slots().length - 1; index >= 0; index--)
                {
                    if (reclaimable[candidates.firstRow() + index])
                    {
                        page.purgeAtSlot(candidates.slots()[index], 1, true);
                    }
                }
                // Scans positioned on this leaf must reposition.
                page.setRepositionNeeded();
                return(true);
            }
            finally
            {
                open_btree.close();
            }
        }
        finally
        {
            purger.commit();
            purger.destroy();
        }
    }
}
