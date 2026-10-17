/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreIdentityHighWater

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Where a table's next row and version identifiers survive a restart.
 *
 * <p>A Gen1 table keeps them in the allocator row, the second slot of its
 * metadata container. Each committing transaction raises that row to cover
 * every identifier it reserved, so a restart never hands out an identifier
 * that a committed version already uses. A Gen2 table never writes the
 * allocator row: every stored identifier is in a CURRENT row, because an
 * update gives the new CURRENT image a fresh version identifier and the
 * archived predecessor keeps its older one. Its high-water mark is rebuilt
 * from the CURRENT rows the first time the runtime needs it.
 *
 * <p>The rebuild is only safe because Gen2 maintenance never removes a
 * CURRENT row, tombstones included: compress and background maintenance only
 * materialize transaction statuses. A future purge of Gen2 CURRENT rows must
 * persist the high-water mark first, or a restart could reuse the purged
 * row's identifiers.
 */
final class MvccRawStoreIdentityHighWater {
    private static final int ALLOCATOR_SLOT = Page.FIRST_SLOT_NUMBER + 1;

    record Mark(long nextRowId, long nextVersionId) {
    }

    private MvccRawStoreIdentityHighWater() {
    }

    static boolean persisted(MvccRawStoreTable.Descriptor table) {
        return !table.gen2A1();
    }

    static Mark load(Transaction transaction, MvccRawStoreTable.Descriptor table)
            throws StandardException {
        return persisted(table)
                ? read(transaction, table)
                : reconstruct(transaction, table);
    }

    static Object[] initialRow(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.ALLOCATOR_KIND),
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    static Mark read(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] allocator = template(transaction);
            page.fetchFromSlot(
                    null,
                    ALLOCATOR_SLOT,
                    allocator,
                    null,
                    false);
            long nextRowId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long nextVersionId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            validate(nextRowId, nextVersionId);
            return new Mark(nextRowId, nextVersionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void stage(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long nextRowId,
            long nextVersionId) throws StandardException {
        validate(nextRowId, nextVersionId);
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
            Object[] allocator = template(transaction);
            page.fetchFromSlot(null, ALLOCATOR_SLOT, allocator, null, false);
            long persistedRow = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long persistedVersion = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            if (nextRowId > persistedRow) {
                page.updateFieldAtSlot(
                        ALLOCATOR_SLOT,
                        MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID,
                        MvccRawStoreFormat.longValue(transaction, nextRowId),
                        null);
            }
            if (nextVersionId > persistedVersion) {
                page.updateFieldAtSlot(
                        ALLOCATOR_SLOT,
                        MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID,
                        MvccRawStoreFormat.longValue(transaction, nextVersionId),
                        null);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static Mark reconstruct(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC metadata container is absent: "
                            + table.metadataContainer());
        }

        long nextRowId = 1L;
        long nextVersionId = 1L;
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
                    MvccRawStoreCurrentRows.CurrentRow current = MvccRawStoreCurrentRows.decode(
                            transaction, table, page, slot);
                    if (current == null) {
                        continue;
                    }
                    if (current.rowId() == Long.MAX_VALUE
                            || current.versionId() == Long.MAX_VALUE) {
                        throw new IllegalStateException(
                                "RawStore MVCC Gen2-A1 row/version identity is exhausted");
                    }
                    nextRowId = Math.max(nextRowId, current.rowId() + 1L);
                    nextVersionId = Math.max(nextVersionId, current.versionId() + 1L);
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return new Mark(nextRowId, nextVersionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static Object[] template(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static void validate(long nextRowId, long nextVersionId) {
        if (nextRowId <= 0L || nextVersionId <= 0L
                || nextRowId == Long.MAX_VALUE || nextVersionId == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "RawStore MVCC logical identity allocator is invalid or exhausted: row="
                            + nextRowId + ", version=" + nextVersionId);
        }
    }
}
