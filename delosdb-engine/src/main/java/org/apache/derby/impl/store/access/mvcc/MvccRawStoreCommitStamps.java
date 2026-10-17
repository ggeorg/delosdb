/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreCommitStamps

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Writes a committing transaction's commit sequence into its pending versions:
 * the begin of each new version record, the directory head that points at it,
 * and the end of the version it replaced. Until then those versions carry
 * UNCOMMITTED_SEQUENCE and are visible only to their creator.
 */
final class MvccRawStoreCommitStamps {
    private MvccRawStoreCommitStamps() {
    }

    static void stamp(
            Transaction rawTransaction,
            List<MvccRawStoreTable.PendingVersion> pending,
            long commitSequence) throws StandardException {
        if (Boolean.getBoolean(MvccRawStoreFormat.GEN2_COMMIT_STAMP_BATCH_ENABLED_PROPERTY)
                && freshInlineInsertBatch(pending)) {
            MvccRawStoreRowDirectory.stampCommittedHeadsByHint(
                    rawTransaction,
                    pending,
                    commitSequence);
            return;
        }
        List<MvccRawStoreTable.PendingVersion> ordered = new ArrayList<>(pending);
        ordered.sort(Comparator
                .comparingLong((MvccRawStoreTable.PendingVersion version) ->
                        version.table().metadataContainer().getSegmentId())
                .thenComparingLong(version ->
                        version.table().metadataContainer().getContainerId())
                .thenComparingLong(MvccRawStoreTable.PendingVersion::versionId));
        for (MvccRawStoreTable.PendingVersion version : ordered) {
            if (!version.inlineCurrent()) {
                stampVersion(
                        rawTransaction,
                        version.table(),
                        version.rowId(),
                        version.versionId(),
                        MvccRawStoreTable.RecordHint.of(version.handle()),
                        (page, slot) -> setBegin(rawTransaction, page, slot, commitSequence),
                        "RawStore MVCC version disappeared before commit: ");
            }
            MvccRawStoreRowDirectory.stampCommittedHead(
                    rawTransaction,
                    version,
                    commitSequence);
            if (version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
                long previousVersionId = version.previousVersionId();
                stampVersion(
                        rawTransaction,
                        version.table(),
                        version.rowId(),
                        previousVersionId,
                        version.previousHint(),
                        (page, slot) -> setEnd(
                                rawTransaction, page, slot, previousVersionId, commitSequence),
                        "RawStore MVCC predecessor version disappeared before commit: ");
            }
        }
    }

    // A batch of new inline CURRENT rows with no predecessors. Only such a
    // batch may commit through the transaction-status table or the batched
    // head stamp, because no version record or predecessor needs stamping.
    static boolean freshInlineInsertBatch(List<MvccRawStoreTable.PendingVersion> pending) {
        if (pending.isEmpty()) {
            return false;
        }
        for (MvccRawStoreTable.PendingVersion version : pending) {
            MvccRowLocation location = version.directoryLocation();
            if (!version.inlineCurrent()
                    || version.previousVersionId() != MvccRawStoreFormat.NO_PREVIOUS_VERSION
                    || location == null
                    || !location.hasLocatorHint()) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    private interface SlotStamp {
        void apply(Page page, int slot) throws StandardException;
    }

    // The hint finds the version record directly; a record that has moved is
    // found by scanning the version container for its logical identity.
    private static void stampVersion(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            MvccRawStoreTable.RecordHint hint,
            SlotStamp stamp,
            String missingMessage) throws StandardException {
        if (stampByHint(transaction, table, rowId, versionId, hint, stamp)) {
            return;
        }
        stampByLogicalId(transaction, table, rowId, versionId, stamp, missingMessage);
    }

    private static boolean stampByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            MvccRawStoreTable.RecordHint hint,
            SlotStamp stamp) throws StandardException {
        if (!hint.valid()) {
            return false;
        }
        ContainerHandle container = openVersions(transaction, table);
        if (container == null) {
            return false;
        }
        Page page = null;
        try {
            page = container.getPage(hint.pageNumber());
            if (page == null) {
                return false;
            }
            RecordHandle handle = page.getRecordHandle(hint.recordId());
            if (handle == null) {
                return false;
            }
            int slot = page.getSlotNumber(handle);
            if (page.isDeletedAtSlot(slot)
                    || !isVersion(transaction, page, slot, rowId, versionId)) {
                return false;
            }
            stamp.apply(page, slot);
            return true;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void stampByLogicalId(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            SlotStamp stamp,
            String missingMessage) throws StandardException {
        ContainerHandle container = openVersions(transaction, table);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    StoreDataValue candidateRow = MvccRawStoreFormat.longValue(transaction, 0L);
                    StoreDataValue candidateVersion = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ROW_ID, candidateRow);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, candidateVersion);
                    if (StoreTypeUtil.getLong(candidateRow) == rowId
                            && StoreTypeUtil.getLong(candidateVersion) == versionId) {
                        stamp.apply(page, slot);
                        return;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        throw new IllegalStateException(missingMessage + versionId);
    }

    private static ContainerHandle openVersions(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        return transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
    }

    private static boolean isVersion(
            Transaction transaction,
            Page page,
            int slot,
            long rowId,
            long versionId) throws StandardException {
        StoreDataValue kind = MvccRawStoreFormat.intValue(transaction, 0);
        StoreDataValue storedRowId = MvccRawStoreFormat.longValue(transaction, 0L);
        StoreDataValue storedVersionId = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_KIND_FIELD, kind);
        page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ROW_ID, storedRowId);
        page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, storedVersionId);
        return StoreTypeUtil.getLong(kind) == MvccRawStoreFormat.VERSION_KIND
                && StoreTypeUtil.getLong(storedRowId) == rowId
                && StoreTypeUtil.getLong(storedVersionId) == versionId;
    }

    private static void setBegin(
            Transaction transaction,
            Page page,
            int slot,
            long commitSequence) throws StandardException {
        page.updateFieldAtSlot(
                slot,
                MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE,
                MvccRawStoreFormat.longValue(transaction, commitSequence),
                null);
    }

    private static void setEnd(
            Transaction transaction,
            Page page,
            int slot,
            long versionId,
            long commitSequence) throws StandardException {
        StoreDataValue currentEnd = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(
                slot,
                MvccRawStoreFormat.VERSION_END_SEQUENCE,
                currentEnd);
        if (StoreTypeUtil.getLong(currentEnd) != MvccRawStoreFormat.CURRENT_END_SEQUENCE) {
            throw new IllegalStateException(
                    "RawStore MVCC predecessor already has an end sequence: " + versionId);
        }
        page.updateFieldAtSlot(
                slot,
                MvccRawStoreFormat.VERSION_END_SEQUENCE,
                MvccRawStoreFormat.longValue(transaction, commitSequence),
                null);
    }
}
