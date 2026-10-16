/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTransactionStatusMaterializer

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Set;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;

/** Materializes durable transaction-status visibility into Gen2 inline CURRENT rows. */
final class MvccRawStoreTransactionStatusMaterializer {
    private MvccRawStoreTransactionStatusMaterializer() {
    }

    static Result materialize(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTransactionStatuses statuses) throws StandardException {
        if (!table.gen2A1()) {
            throw new IllegalArgumentException(
                    "transaction-status materialization requires a Gen2 inline CURRENT table");
        }

        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC Gen2 current container is absent: "
                            + table.metadataContainer());
        }

        int materialized = 0;
        int unresolved = 0;
        int currentRows = 0;
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                PageResult pageResult = materializePage(
                        transaction, table, statuses, page);
                materialized += pageResult.materializedCurrents();
                unresolved += pageResult.unresolvedCurrents();
                currentRows += pageResult.currentRows();
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

        MvccRawStoreDatabaseMetadata.TransactionStatusReclamation reclamation =
                unresolved == 0
                        ? statuses.reclaimDependencies(transaction, table.metadataContainer())
                        : MvccRawStoreDatabaseMetadata.TransactionStatusReclamation.EMPTY;
        int historyRows = countHistoryRows(transaction, table);
        return new Result(
                materialized,
                unresolved,
                currentRows,
                currentRows + historyRows,
                reclamation.purgedDependencyRows(),
                reclamation.fullyReclaimedTransactionIds());
    }

    private static PageResult materializePage(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTransactionStatuses statuses,
            Page page) throws StandardException {
        int materialized = 0;
        int unresolved = 0;
        int currentRows = 0;
        int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                ? Page.FIRST_SLOT_NUMBER + 2
                : Page.FIRST_SLOT_NUMBER;
        for (int slot = startSlot; slot < page.recordCount(); slot++) {
            if (page.isDeletedAtSlot(slot)) {
                continue;
            }
            currentRows++;
            MaterializationOutcome outcome = materializeSlot(
                    transaction, table, statuses, page, slot);
            if (outcome == MaterializationOutcome.MATERIALIZED) {
                materialized++;
            } else if (outcome == MaterializationOutcome.UNRESOLVED) {
                unresolved++;
            }
        }
        return new PageResult(materialized, unresolved, currentRows);
    }

    private static MaterializationOutcome materializeSlot(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTransactionStatuses statuses,
            Page page,
            int slot) throws StandardException {
        validateCurrentRecord(transaction, table, page, slot);
        long beginSequence = longField(
                transaction, page, slot, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
        if (beginSequence != MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            if (beginSequence <= 0L) {
                throw corruption(
                        "invalid Gen2 CURRENT begin sequence",
                        page.getPageNumber() + ":" + slot + " begin=" + beginSequence);
            }
            return MaterializationOutcome.SELF_CONTAINED;
        }

        long creatorTransactionId = longField(
                transaction, page, slot,
                MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID);
        if (creatorTransactionId <= 0L) {
            throw corruption(
                    "invalid Gen2 CURRENT creator transaction",
                    page.getPageNumber() + ":" + slot + " tx=" + creatorTransactionId);
        }
        long committedSequence = statuses.committedSequence(transaction, creatorTransactionId);
        if (committedSequence <= 0L) {
            return MaterializationOutcome.UNRESOLVED;
        }

        page.updateFieldAtSlot(
                slot,
                MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE,
                MvccRawStoreFormat.longValue(transaction, committedSequence),
                null);
        return MaterializationOutcome.MATERIALIZED;
    }

    private static void validateCurrentRecord(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot) throws StandardException {
        int expected = table.gen2History()
                ? MvccRawStoreFormat.gen2C1CurrentFieldCount(table.columnCount())
                : MvccRawStoreFormat.gen2A1CurrentFieldCount(table.columnCount());
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        if (fieldCount != expected) {
            throw corruption(
                    "unsupported Gen2 CURRENT field count",
                    page.getPageNumber() + ":" + slot + " fields=" + fieldCount);
        }
        if (intField(transaction, page, slot, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                != MvccRawStoreFormat.DIRECTORY_KIND) {
            throw corruption(
                    "unexpected record kind in Gen2 CURRENT container",
                    page.getPageNumber() + ":" + slot);
        }
        if (intField(transaction, page, slot, MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            throw corruption(
                    "unsupported Gen2 CURRENT format version",
                    page.getPageNumber() + ":" + slot);
        }
    }

    private static int countHistoryRows(
            Transaction transaction, MvccRawStoreTable.Descriptor table)
            throws StandardException {
        if (!table.gen2History()) {
            return 0;
        }
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC Gen2 history container is absent: "
                            + table.versionContainer());
        }
        int rows = 0;
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (!page.isDeletedAtSlot(slot)) {
                        rows++;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return rows;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static long longField(
            Transaction transaction, Page page, int slot, int field)
            throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(slot, field, value);
        return StoreTypeUtil.getLong(value);
    }

    private static int intField(
            Transaction transaction, Page page, int slot, int field)
            throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.intValue(transaction, 0);
        page.fetchFieldFromSlot(slot, field, value);
        return Math.toIntExact(StoreTypeUtil.getLong(value));
    }

    private static IllegalStateException corruption(String reason, String detail) {
        return new IllegalStateException(
                "RawStore MVCC transaction-status materialization rejected corrupt CURRENT state: "
                        + reason + " [" + detail + ']');
    }

    private enum MaterializationOutcome {
        SELF_CONTAINED,
        MATERIALIZED,
        UNRESOLVED
    }

    private record PageResult(
            int materializedCurrents,
            int unresolvedCurrents,
            int currentRows) {
    }

    record Result(
            int materializedCurrents,
            int unresolvedCurrents,
            int remainingLogicalRows,
            int remainingVersions,
            int reclaimedStatusDependencies,
            Set<Long> fullyReclaimedTransactionIds) {
        Result {
            if (reclaimedStatusDependencies < 0) {
                throw new IllegalArgumentException(
                        "reclaimedStatusDependencies must be non-negative");
            }
            fullyReclaimedTransactionIds = Set.copyOf(fullyReclaimedTransactionIds);
        }

        boolean mutated() {
            return materializedCurrents > 0 || reclaimedStatusDependencies > 0;
        }

        boolean retryRequired() {
            return unresolvedCurrents > 0;
        }
    }
}
