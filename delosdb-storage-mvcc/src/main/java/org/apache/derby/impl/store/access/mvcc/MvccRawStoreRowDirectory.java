/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreRowDirectory

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Physical addressing and mutation of the stable-row directory. */
final class MvccRawStoreRowDirectory {
    private MvccRawStoreRowDirectory() {
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation) throws StandardException {
        return find(transaction, table, rowLocation, (MvccRawStoreVersionRows.FetchProjection) null);
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        try {
            return find(transaction, table, rowLocation, container, null, projection);
        } finally {
            if (container != null) {
                container.close();
            }
        }
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle container) throws StandardException {
        return find(transaction, table, rowLocation, container, null, null);
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics) throws StandardException {
        return find(transaction, table, rowLocation, container, metrics, null);
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        MvccRawStoreTable.DirectoryRecord hinted = findByHint(
                transaction, table, rowLocation, container, metrics, projection);
        if (hinted != null) {
            return hinted;
        }
        if (metrics != null) {
            metrics.directoryLogicalFallback();
        }
        return findByLogicalId(
                transaction, table, rowLocation.rowId(), container, metrics, projection);
    }

    static Map<Long, MvccRowLocation> locations(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        Map<Long, MvccRowLocation> locations = new LinkedHashMap<>();
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return Map.of();
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreTable.decodeDirectory(
                                    transaction, table, page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(table));
                    if (directory != null) {
                        locations.put(
                                directory.rowId(),
                                location(directory.rowId(), directory.handle()));
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
        return Map.copyOf(locations);
    }

    static MvccRowLocation requireLocation(
            Map<Long, MvccRowLocation> locations,
            long rowId) {
        MvccRowLocation location = locations.get(rowId);
        if (location == null) {
            throw new IllegalStateException(
                    "RawStore MVCC directory location is absent for logical row " + rowId);
        }
        return location;
    }

    static MvccRowLocation location(long rowId, RecordHandle handle) {
        if (handle == null) {
            return new MvccRowLocation(rowId);
        }
        return new MvccRowLocation(
                rowId,
                handle.getPageNumber(),
                handle.getSlotNumberHint());
    }

    static void updateHead(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead expectedHead,
            MvccRowLocation directoryLocation,
            long newHeadVersionId,
            MvccRawStoreTable.RecordHint newHeadHint,
            long creatorTransactionId,
            long beginSequence,
            int flags) throws StandardException {
        if (updateByHint(
                transaction,
                table,
                rowId,
                expectedHead,
                directoryLocation,
                newHeadVersionId,
                newHeadHint,
                creatorTransactionId,
                beginSequence,
                flags)) {
            return;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreTable.decodeDirectory(
                                    transaction, table, page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(table));
                    if (directory == null || directory.rowId() != rowId) {
                        continue;
                    }
                    validateExpectedHead(rowId, expectedHead, directory.head());
                    page.updateAtSlot(
                            slot,
                            MvccRawStoreTable.directoryRow(
                                    transaction,
                                    rowId,
                                    newHeadVersionId,
                                    newHeadHint,
                                    creatorTransactionId,
                                    beginSequence,
                                    flags),
                            null);
                    return;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
        throw new IllegalStateException(
                "RawStore MVCC directory entry disappeared for logical row " + rowId);
    }

    private static MvccRawStoreTable.DirectoryRecord findByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (container == null || rowLocation == null || !rowLocation.hasLocatorHint()) {
            return null;
        }
        Page page = null;
        try {
            page = container.getPage(rowLocation.locatorPageId());
            if (page != null && metrics != null) {
                metrics.directoryPageAcquired();
            }
            if (page == null) {
                return null;
            }
            return findByHint(transaction, table, rowLocation, page, projection);
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    static MvccRawStoreTable.DirectoryRecord findByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            Page page,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (page == null
                || rowLocation == null
                || !rowLocation.hasLocatorHint()
                || page.getPageNumber() != rowLocation.locatorPageId()) {
            return null;
        }
        int slot = rowLocation.locatorSlotId();
        if (!isDirectorySlot(transaction, page, slot)) {
            return null;
        }
        MvccRawStoreTable.DirectoryRecord directory =
                MvccRawStoreTable.decodeDirectory(
                        transaction, table, page, slot, projection);
        return directory != null && directory.rowId() == rowLocation.rowId()
                ? directory
                : null;
    }

    private static MvccRawStoreTable.DirectoryRecord findByLogicalId(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        if (container == null) {
            return new MvccRawStoreTable.DirectoryRecord(
                    rowId, MvccRawStoreTable.DirectoryHead.NONE, null);
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page != null && metrics != null) {
                metrics.directoryPageAcquired();
            }
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreTable.decodeDirectory(
                                    transaction, table, page, slot, projection);
                    if (directory != null && directory.rowId() == rowId) {
                        return directory;
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
                if (page != null && metrics != null) {
                    metrics.directoryPageAcquired();
                }
            }
            return new MvccRawStoreTable.DirectoryRecord(
                    rowId, MvccRawStoreTable.DirectoryHead.NONE, null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    static RecordHandle updateCurrent(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead expectedHead,
            MvccRowLocation location,
            Object[] currentRow) throws StandardException {
        RecordHandle updated = updateCurrentByHint(
                transaction, table, rowId, expectedHead, location, currentRow);
        if (updated != null) {
            return updated;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreTable.decodeDirectory(
                                    transaction, table, page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(table));
                    if (directory == null || directory.rowId() != rowId) {
                        continue;
                    }
                    if (!directory.currentRow()) {
                        throw new IllegalStateException(
                                "RawStore MVCC M2 current-row update targeted a legacy directory: "
                                        + rowId);
                    }
                    validateExpectedHead(rowId, expectedHead, directory.head());
                    return page.updateAtSlot(slot, currentRow, null);
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
        throw new IllegalStateException(
                "RawStore MVCC current row disappeared for logical row " + rowId);
    }

    private static RecordHandle updateCurrentByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead expectedHead,
            MvccRowLocation location,
            Object[] currentRow) throws StandardException {
        if (location == null || !location.hasLocatorHint()) {
            return null;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            return null;
        }
        Page page = null;
        try {
            page = container.getPage(location.locatorPageId());
            if (page == null) {
                return null;
            }
            int slot = location.locatorSlotId();
            if (!isDirectorySlot(transaction, page, slot)) {
                return null;
            }
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(
                            transaction, table, page, slot,
                            MvccRawStoreVersionRows.metadataProjection(table));
            if (directory == null || directory.rowId() != rowId || !directory.currentRow()) {
                return null;
            }
            validateExpectedHead(rowId, expectedHead, directory.head());
            return page.updateAtSlot(slot, currentRow, null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void stampCommittedHead(
            Transaction transaction,
            MvccRawStoreTable.PendingVersion pending,
            long commitSequence) throws StandardException {
        if (stampCommittedHeadByHint(transaction, pending, commitSequence)) {
            return;
        }
        ContainerHandle container = transaction.openContainer(
                pending.table().metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreTable.decodeDirectory(
                                    transaction, pending.table(), page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(pending.table()));
                    if (directory != null && directory.rowId() == pending.rowId()) {
                        stampCommittedHeadAtSlot(
                                transaction,
                                page,
                                slot,
                                directory,
                                pending,
                                commitSequence);
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
            if (container != null) {
                container.close();
            }
        }
        throw new IllegalStateException(
                "RawStore MVCC directory entry disappeared before commit for logical row "
                        + pending.rowId());
    }

    private static boolean stampCommittedHeadByHint(
            Transaction transaction,
            MvccRawStoreTable.PendingVersion pending,
            long commitSequence) throws StandardException {
        MvccRowLocation location = pending.directoryLocation();
        if (location == null || !location.hasLocatorHint()) {
            return false;
        }
        ContainerHandle container = transaction.openContainer(
                pending.table().metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            return false;
        }
        Page page = null;
        try {
            page = container.getPage(location.locatorPageId());
            if (page == null) {
                return false;
            }
            int slot = location.locatorSlotId();
            if (!isDirectorySlot(transaction, page, slot)) {
                return false;
            }
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(
                                    transaction, pending.table(), page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(pending.table()));
            if (directory == null || directory.rowId() != pending.rowId()) {
                return false;
            }
            stampCommittedHeadAtSlot(
                    transaction,
                    page,
                    slot,
                    directory,
                    pending,
                    commitSequence);
            return true;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void stampCommittedHeadAtSlot(
            Transaction transaction,
            Page page,
            int slot,
            MvccRawStoreTable.DirectoryRecord directory,
            MvccRawStoreTable.PendingVersion pending,
            long commitSequence) throws StandardException {
        MvccRawStoreTable.DirectoryHead head = directory.head();
        if (head.versionId() != pending.versionId()) {
            // Multiple same-transaction rewrites can leave older pending
            // identities in transaction-local bookkeeping. Only the physical
            // current head is stamped.
            return;
        }
        MvccRawStoreTable.DirectoryHeadSummary summary = head.summary();
        if (pending.currentRecord()) {
            if (!directory.currentRow()) {
                throw new IllegalStateException(
                        "RawStore MVCC M2 pending current row resolved to a legacy directory: "
                                + pending.rowId());
            }
            if (!summary.available()
                    || summary.creatorTransactionId() != pending.creatorTransactionId()
                    || summary.beginSequence() != MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                    || summary.flags() != pending.flags()) {
                throw new IllegalStateException(
                        "RawStore MVCC M2 current row changed before commit: "
                                + pending.rowId() + ':' + pending.versionId());
            }
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.CURRENT_ROW_BEGIN_SEQUENCE,
                    MvccRawStoreFormat.longValue(transaction, commitSequence),
                    null);
            return;
        }
        if (summary.available()
                && summary.creatorTransactionId() == pending.creatorTransactionId()
                && summary.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                && summary.flags() == pending.flags()) {
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE,
                    MvccRawStoreFormat.longValue(transaction, commitSequence),
                    null);
            return;
        }
        page.updateAtSlot(
                slot,
                MvccRawStoreTable.directoryRow(
                        transaction,
                        pending.rowId(),
                        pending.versionId(),
                        head.hint(),
                        pending.creatorTransactionId(),
                        commitSequence,
                        pending.flags()),
                null);
    }

    private static boolean updateByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead expectedHead,
            MvccRowLocation directoryLocation,
            long newHeadVersionId,
            MvccRawStoreTable.RecordHint newHeadHint,
            long creatorTransactionId,
            long beginSequence,
            int flags) throws StandardException {
        if (directoryLocation == null || !directoryLocation.hasLocatorHint()) {
            return false;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            return false;
        }
        Page page = null;
        try {
            page = container.getPage(directoryLocation.locatorPageId());
            if (page == null) {
                return false;
            }
            int slot = directoryLocation.locatorSlotId();
            if (!isDirectorySlot(transaction, page, slot)) {
                return false;
            }
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(
                                    transaction, table, page, slot,
                                    MvccRawStoreVersionRows.metadataProjection(table));
            if (directory == null || directory.rowId() != rowId) {
                return false;
            }
            validateExpectedHead(rowId, expectedHead, directory.head());
            page.updateAtSlot(
                    slot,
                    MvccRawStoreTable.directoryRow(
                            transaction,
                            rowId,
                            newHeadVersionId,
                            newHeadHint,
                            creatorTransactionId,
                            beginSequence,
                            flags),
                    null);
            return true;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static boolean isDirectorySlot(
            Transaction transaction,
            Page page,
            int slot) throws StandardException {
        if (slot < Page.FIRST_SLOT_NUMBER
                || slot >= page.recordCount()
                || page.isDeletedAtSlot(slot)) {
            return false;
        }
        StoreDataValue kind = MvccRawStoreFormat.intValue(transaction, 0);
        page.fetchFieldFromSlot(slot, MvccRawStoreFormat.DIRECTORY_KIND_FIELD, kind);
        long value = StoreTypeUtil.getLong(kind);
        return value == MvccRawStoreFormat.DIRECTORY_KIND
                || value == MvccRawStoreFormat.CURRENT_ROW_KIND;
    }

    private static void validateExpectedHead(
            long rowId,
            MvccRawStoreTable.DirectoryHead expected,
            MvccRawStoreTable.DirectoryHead actual) throws StandardException {
        if (!actual.equals(expected)) {
            throw StandardException.newException(
                    SQLState.DEADLOCK,
                    "RawStore MVCC directory head changed for logical row " + rowId);
        }
    }
}
