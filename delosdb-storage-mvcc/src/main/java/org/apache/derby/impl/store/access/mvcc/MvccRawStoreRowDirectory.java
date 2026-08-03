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
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        try {
            return find(transaction, rowLocation, container);
        } finally {
            if (container != null) {
                container.close();
            }
        }
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRowLocation rowLocation,
            ContainerHandle container) throws StandardException {
        return find(transaction, rowLocation, container, null);
    }

    static MvccRawStoreTable.DirectoryRecord find(
            Transaction transaction,
            MvccRowLocation rowLocation,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics) throws StandardException {
        MvccRawStoreTable.DirectoryRecord hinted = findByHint(
                transaction, rowLocation, container, metrics);
        if (hinted != null) {
            return hinted;
        }
        if (metrics != null) {
            metrics.directoryLogicalFallback();
        }
        return findByLogicalId(transaction, rowLocation.rowId(), container, metrics);
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
                            MvccRawStoreTable.decodeDirectory(transaction, page, slot);
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
            MvccRawStoreTable.RecordHint newHeadHint) throws StandardException {
        if (updateByHint(
                transaction,
                table,
                rowId,
                expectedHead,
                directoryLocation,
                newHeadVersionId,
                newHeadHint)) {
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
                            MvccRawStoreTable.decodeDirectory(transaction, page, slot);
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
                                    newHeadHint),
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
            MvccRowLocation rowLocation,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics) throws StandardException {
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
            int slot = rowLocation.locatorSlotId();
            if (!isDirectorySlot(page, slot)) {
                return null;
            }
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(transaction, page, slot);
            return directory != null && directory.rowId() == rowLocation.rowId()
                    ? directory
                    : null;
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static MvccRawStoreTable.DirectoryRecord findByLogicalId(
            Transaction transaction,
            long rowId,
            ContainerHandle container,
            MvccRawStoreIndexedReadMetrics metrics) throws StandardException {
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
                            MvccRawStoreTable.decodeDirectory(transaction, page, slot);
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

    private static boolean updateByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreTable.DirectoryHead expectedHead,
            MvccRowLocation directoryLocation,
            long newHeadVersionId,
            MvccRawStoreTable.RecordHint newHeadHint) throws StandardException {
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
            if (!isDirectorySlot(page, slot)) {
                return false;
            }
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(transaction, page, slot);
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
                            newHeadHint),
                    null);
            return true;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static boolean isDirectorySlot(Page page, int slot) throws StandardException {
        if (slot < Page.FIRST_SLOT_NUMBER
                || slot >= page.recordCount()
                || page.isDeletedAtSlot(slot)) {
            return false;
        }
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        return fieldCount == MvccRawStoreFormat.DIRECTORY_BASE_FIELD_COUNT
                || fieldCount == MvccRawStoreFormat.DIRECTORY_HINT_FIELD_COUNT;
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
