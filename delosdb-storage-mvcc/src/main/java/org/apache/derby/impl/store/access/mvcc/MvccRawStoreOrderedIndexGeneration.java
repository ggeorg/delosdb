/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndexGeneration

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Owns the published MVCC ordered-index generation directory and its Derby
 * B-tree conglomerate lifecycle.
 */
final class MvccRawStoreOrderedIndexGeneration {
    private static final int OVERFLOW_THRESHOLD = 100;

    private static final String BTREE_IMPLEMENTATION = "BTREE";
    private static final String PROPERTY_BASE_CONGLOMERATE_ID = "baseConglomerateId";
    private static final String PROPERTY_ROW_LOCATION_COLUMN = "rowLocationColumn";
    private static final String PROPERTY_ALLOW_DUPLICATES = "allowDuplicates";
    private static final String PROPERTY_KEY_FIELDS = "nKeyFields";
    private static final String PROPERTY_UNIQUE_COLUMNS = "nUniqueColumns";
    private static final String PROPERTY_PARENT_LINKS = "maintainParentLinks";

    private MvccRawStoreOrderedIndexGeneration() {
    }

    static void initialize(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        initialize(transactionManager, table, requireContainer(table));
    }

    static void initialize(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        initializeDirectory(transaction, table, directoryKey);
        long[] btrees = createBtrees(transactionManager, table);
        try {
            replaceDirectoryMappings(transaction, table, directoryKey, btrees);
        } catch (StandardException failure) {
            dropBtreesAfterFailure(transactionManager, btrees, failure);
            throw failure;
        }
    }

    static ContainerKey createPrivateGeneration(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        int temporaryFlag = table.temporary()
                ? TransactionController.IS_TEMPORARY
                : TransactionController.IS_DEFAULT;
        long containerId = transaction.addContainer(
                table.metadataContainer().getSegmentId(),
                0L,
                ContainerHandle.MODE_DEFAULT,
                null,
                temporaryFlag);
        if (containerId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }
        ContainerKey target = new ContainerKey(
                table.metadataContainer().getSegmentId(),
                containerId);
        initializeDirectory(transaction, table, target);
        return target;
    }

    static boolean containerExists(
            Transaction transaction,
            ContainerKey key,
            boolean temporary)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY
                        | (temporary ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return false;
        }
        container.close();
        return true;
    }

    static List<ContainerKey> btreeContainerKeys(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        if (directoryKey == null) {
            return List.of();
        }
        long[] btrees = readBtreeConglomerates(
                transactionManager.getRawStoreXact(), table, directoryKey);
        if (btrees == null) {
            return List.of();
        }
        List<ContainerKey> containers = new ArrayList<>();
        for (long btree : btrees) {
            if (btree > 0L) {
                containers.add(new ContainerKey(
                        0,
                        transactionManager.findContainerid(btree)));
            }
        }
        return List.copyOf(containers);
    }

    static boolean hasBtreeGeneration(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        return directoryKey != null
                && readBtreeConglomerates(
                        transactionManager.getRawStoreXact(),
                        table,
                        directoryKey) != null;
    }

    static void dropGeneration(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        if (directoryKey == null) {
            return;
        }
        Transaction transaction = transactionManager.getRawStoreXact();
        long[] btrees = readBtreeConglomerates(
                transaction,
                table,
                directoryKey);
        if (btrees != null) {
            dropBtrees(transactionManager, btrees);
        }
        transaction.dropContainer(directoryKey);
    }

    static long[] createBtrees(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        long[] btrees = new long[table.columnCount()];
        Transaction transaction = transactionManager.getRawStoreXact();
        try {
            for (int column = 0; column < table.columnCount(); column++) {
                if (!MvccRawStoreOrderedIndex.indexesColumn(table, column)) {
                    continue;
                }
                Properties properties = new Properties();
                properties.setProperty(
                        PROPERTY_BASE_CONGLOMERATE_ID,
                        Long.toString(table.accessConglomerateId()));
                properties.setProperty(
                        PROPERTY_ROW_LOCATION_COLUMN,
                        Integer.toString(MvccRawStoreOrderedIndex.ROW_LOCATION_FIELD));
                properties.setProperty(PROPERTY_ALLOW_DUPLICATES, "false");
                properties.setProperty(
                        PROPERTY_KEY_FIELDS,
                        Integer.toString(MvccRawStoreOrderedIndex.INDEX_FIELD_COUNT));
                properties.setProperty(
                        PROPERTY_UNIQUE_COLUMNS,
                        Integer.toString(MvccRawStoreOrderedIndex.INDEX_FIELD_COUNT));
                properties.setProperty(PROPERTY_PARENT_LINKS, "true");
                int[] collationIds = new int[MvccRawStoreOrderedIndex.INDEX_FIELD_COUNT];
                collationIds[MvccRawStoreOrderedIndex.KEY_FIELD] =
                        table.collationIds()[column];
                btrees[column] = transactionManager.createConglomerate(
                        BTREE_IMPLEMENTATION,
                        MvccRawStoreOrderedIndex.indexRowTemplate(
                                transaction, table, column),
                        null,
                        collationIds,
                        properties,
                        table.temporary()
                                ? TransactionController.IS_TEMPORARY
                                : TransactionController.IS_DEFAULT);
            }
            return btrees;
        } catch (StandardException failure) {
            dropBtreesAfterFailure(transactionManager, btrees, failure);
            throw failure;
        }
    }

    static void dropBtrees(
            TransactionManager transactionManager,
            long[] btrees) throws StandardException {
        for (long btree : btrees) {
            if (btree != 0L) {
                transactionManager.dropConglomerate(btree);
            }
        }
    }

    static void dropBtreesAfterFailure(
            TransactionManager transactionManager,
            long[] btrees,
            Throwable failure) {
        for (int column = btrees.length - 1; column >= 0; column--) {
            if (btrees[column] == 0L) {
                continue;
            }
            try {
                transactionManager.dropConglomerate(btrees[column]);
            } catch (StandardException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    static void initializeDirectory(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                directoryKey,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index directory is absent: " + directoryKey);
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page.recordCount() == 0) {
                page.insertAtSlot(
                        Page.FIRST_SLOT_NUMBER,
                        controlRow(transaction, table),
                        null,
                        null,
                        insertFlags(page),
                        OVERFLOW_THRESHOLD);
            } else {
                validateControl(transaction, table, page);
            }
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static void replaceDirectoryMappings(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            long[] btrees) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                directoryKey,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index directory is absent: " + directoryKey);
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                int count = page.recordCount() - startSlot;
                if (count > 0) {
                    page.purgeAtSlot(startSlot, count, true);
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            page = container.getFirstPage();
            for (int column = 0; column < btrees.length; column++) {
                if (btrees[column] == 0L) {
                    continue;
                }
                Object[] mapping = mappingRow(transaction, column, btrees[column]);
                while (true) {
                    RecordHandle inserted = page.insertAtSlot(
                            page.recordCount(),
                            mapping,
                            null,
                            null,
                            insertFlags(page),
                            OVERFLOW_THRESHOLD);
                    if (inserted != null) {
                        break;
                    }
                    long pageNumber = page.getPageNumber();
                    page.unlatch();
                    page = container.getNextPage(pageNumber);
                    if (page == null) {
                        page = container.addPage();
                    }
                }
            }
            container.setEstimatedRowCount(MvccRawStoreOrderedIndex.indexedColumnCount(table), 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static long[] requireBtreeConglomerates(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        long[] btrees = readBtreeConglomerates(
                transactionManager.getRawStoreXact(),
                table,
                directoryKey);
        if (btrees == null) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index directory has no Derby B-tree generation: "
                            + directoryKey);
        }
        return btrees;
    }

    static long[] readBtreeConglomerates(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                directoryKey,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return null;
        }
        long[] btrees = new long[table.columnCount()];
        int found = 0;
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    StoreDataValue kind = MvccRawStoreFormat.intValue(transaction, 0);
                    page.fetchFieldFromSlot(
                            slot,
                            MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_KIND_FIELD,
                            kind);
                    if (StoreTypeUtil.getLong(kind)
                            != MvccRawStoreFormat.ORDERED_INDEX_BTREE_DESCRIPTOR_KIND) {
                        continue;
                    }
                    Object[] mapping = mappingTemplate(transaction);
                    page.fetchFromSlot(null, slot, mapping, null, false);
                    if (MvccRawStoreFormat.intAt(
                                    mapping,
                                    MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_FORMAT_VERSION)
                            != MvccRawStoreFormat.FORMAT_VERSION) {
                        throw new IllegalStateException(
                                "Unsupported RawStore MVCC ordered-index directory entry format");
                    }
                    int column = MvccRawStoreFormat.intAt(
                            mapping,
                            MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_COLUMN_ID);
                    long btree = MvccRawStoreFormat.longAt(
                            mapping,
                            MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_BTREE_CONGLOMERATE);
                    if (!MvccRawStoreOrderedIndex.indexesColumn(table, column)
                            || btree == 0L
                            || btrees[column] != 0L) {
                        throw new IllegalStateException(
                                "RawStore MVCC ordered-index directory entry is inconsistent");
                    }
                    btrees[column] = btree;
                    found++;
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
        if (found != MvccRawStoreOrderedIndex.indexedColumnCount(table)) {
            return null;
        }
        return btrees;
    }

    private static Object[] mappingRow(
            Transaction transaction,
            int column,
            long btreeConglomerate) throws StandardException {
        Object[] row = mappingTemplate(transaction);
        row[MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_KIND_FIELD] =
                MvccRawStoreFormat.intValue(
                        transaction,
                        MvccRawStoreFormat.ORDERED_INDEX_BTREE_DESCRIPTOR_KIND);
        row[MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_FORMAT_VERSION] =
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_COLUMN_ID] =
                MvccRawStoreFormat.intValue(transaction, column);
        row[MvccRawStoreFormat.ORDERED_INDEX_DIRECTORY_BTREE_CONGLOMERATE] =
                MvccRawStoreFormat.longValue(transaction, btreeConglomerate);
        return row;
    }

    private static Object[] mappingTemplate(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static Object[] controlRow(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(
                        transaction,
                        MvccRawStoreFormat.ORDERED_INDEX_CONTAINER_KIND),
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(
                        transaction,
                        table.metadataContainer().getContainerId()),
                MvccRawStoreFormat.intValue(transaction, table.columnCount())
        };
    }

    static void validateControl(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page firstPage) throws StandardException {
        if (firstPage == null || firstPage.recordCount() == 0) {
            throw new IllegalStateException("RawStore MVCC ordered-index control row is absent");
        }
        Object[] control = new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.intValue(transaction, 0)
        };
        firstPage.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, control, null, false);
        if (MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_KIND_FIELD)
                != MvccRawStoreFormat.ORDERED_INDEX_CONTAINER_KIND
                || MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION
                || MvccRawStoreFormat.longAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_METADATA_CONTAINER)
                != table.metadataContainer().getContainerId()
                || MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_COLUMN_COUNT)
                != table.columnCount()) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index control row is inconsistent");
        }
    }

    private static byte insertFlags(Page page) throws StandardException {
        return (byte) (Page.INSERT_UNDO_WITH_PURGE
                | (page.recordCount() == 0
                        ? Page.INSERT_OVERFLOW
                        : Page.INSERT_DEFAULT));
    }

    static ContainerKey requireContainer(MvccRawStoreTable.Descriptor table) {
        ContainerKey key = table.orderedIndexContainer();
        if (key == null) {
            throw new IllegalStateException("RawStore MVCC ordered index is not installed");
        }
        return key;
    }
}
