/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTable

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** One table encoded entirely as ordinary RawStore rows and containers. */
final class MvccRawStoreTable {
    private static final int INSERT_FLAGS = Page.INSERT_UNDO_WITH_PURGE | Page.INSERT_OVERFLOW;
    private static final int OVERFLOW_THRESHOLD = 100;

    record Descriptor(
            ContainerKey metadataContainer,
            ContainerKey versionContainer,
            int[] formatIds,
            int[] collationIds,
            boolean temporary) {
        Descriptor {
            formatIds = formatIds.clone();
            collationIds = collationIds.clone();
        }

        int columnCount() {
            return formatIds.length;
        }
    }

    record VisibleRow(long rowId, StoreDataValue[] values, RecordHandle versionHandle) {
    }

    record PendingVersion(Descriptor table, long versionId, RecordHandle handle) {
    }

    private MvccRawStoreTable() {
    }

    static Descriptor create(
            Transaction rawTransaction,
            int segment,
            long requestedContainerId,
            StoreDataValue[] template,
            int[] suppliedCollationIds,
            Properties properties,
            int temporaryFlag) throws StandardException {
        if (template == null || template.length == 0) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "delos_mvcc RawStore tables require a non-empty row template");
        }

        int[] formatIds = new int[template.length];
        int[] collationIds = new int[template.length];
        for (int index = 0; index < template.length; index++) {
            formatIds[index] = MvccRawStoreFormat.formatId(template[index]);
            int supplied = suppliedCollationIds != null && index < suppliedCollationIds.length
                    ? suppliedCollationIds[index]
                    : 0;
            collationIds[index] = MvccRawStoreFormat.collationId(template[index], supplied);
        }

        long metadataId = rawTransaction.addContainer(
                segment,
                requestedContainerId,
                ContainerHandle.MODE_DEFAULT,
                properties,
                temporaryFlag);
        if (metadataId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }
        long versionId = rawTransaction.addContainer(
                segment,
                0L,
                ContainerHandle.MODE_DEFAULT,
                properties,
                temporaryFlag);
        if (versionId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }

        Descriptor descriptor = new Descriptor(
                new ContainerKey(segment, metadataId),
                new ContainerKey(segment, versionId),
                formatIds,
                collationIds,
                (temporaryFlag & TransactionController.IS_TEMPORARY) == TransactionController.IS_TEMPORARY);
        initializeMetadataContainer(rawTransaction, descriptor);
        initializeVersionContainer(rawTransaction, descriptor);
        return descriptor;
    }

    static Descriptor read(Transaction rawTransaction, ContainerKey metadataKey) throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                metadataKey,
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            if (page == null || page.recordCount() < 2) {
                return null;
            }
            Object[] prefix = controlPrefixTemplate(rawTransaction);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, prefix, null, false);
            if (MvccRawStoreFormat.longAt(prefix, MvccRawStoreFormat.CONTROL_MAGIC)
                    != MvccRawStoreFormat.MAGIC
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_KIND_FIELD)
                    != MvccRawStoreFormat.CONTROL_KIND
                    || MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_FORMAT_VERSION)
                    != MvccRawStoreFormat.FORMAT_VERSION) {
                return null;
            }
            int columnCount = MvccRawStoreFormat.intAt(prefix, MvccRawStoreFormat.CONTROL_COLUMN_COUNT);
            Object[] row = controlTemplate(rawTransaction, columnCount);
            page.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, row, null, false);
            int[] formatIds = new int[columnCount];
            int[] collationIds = new int[columnCount];
            for (int index = 0; index < columnCount; index++) {
                formatIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index);
                collationIds[index] = MvccRawStoreFormat.intAt(
                        row,
                        MvccRawStoreFormat.CONTROL_FIXED_FIELDS + columnCount + index);
            }
            return new Descriptor(
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_METADATA_CONTAINER)),
                    new ContainerKey(
                            metadataKey.getSegmentId(),
                            MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CONTROL_VERSION_CONTAINER)),
                    formatIds,
                    collationIds,
                    MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CONTROL_TEMPORARY) != 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static PendingVersion insert(
            Transaction rawTransaction,
            Descriptor table,
            long creatorTransactionId,
            StoreDataValue[] values,
            MvccRawStoreTransactionContext context,
            MvccRowLocation destination) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            throw new IllegalArgumentException(
                    "RawStore MVCC row width mismatch: expected " + table.columnCount());
        }
        context.beforeWrite(table);
        Allocation allocation = allocateIdentifiers(rawTransaction, table);
        Object[] versionRow = versionRow(
                rawTransaction,
                table,
                allocation.rowId(),
                allocation.versionId(),
                creatorTransactionId,
                values);
        RecordHandle versionHandle = insertRow(rawTransaction, table.versionContainer(), versionRow);
        Object[] directoryRow = new Object[] {
                MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.DIRECTORY_KIND),
                MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(rawTransaction, allocation.rowId()),
                MvccRawStoreFormat.longValue(rawTransaction, allocation.versionId())
        };
        insertRow(rawTransaction, table.metadataContainer(), directoryRow);
        PendingVersion pending = new PendingVersion(table, allocation.versionId(), versionHandle);
        context.addPending(pending);
        if (destination != null) {
            destination.set(
                    allocation.rowId(),
                    versionHandle.getPageNumber(),
                    versionHandle.getSlotNumberHint());
        }
        return pending;
    }

    static VisibleRow readVisible(
            Transaction rawTransaction,
            Descriptor table,
            long rowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        context.bind(table);
        long snapshot = context.snapshotSequence(table);
        long headVersionId = findHeadVersionId(rawTransaction, table, rowId);
        if (headVersionId == 0L) {
            return null;
        }
        VersionRecord version = findVersion(rawTransaction, table, headVersionId);
        if (version == null || !visible(version, context.transactionId(), snapshot)) {
            return null;
        }
        return new VisibleRow(rowId, version.values(), version.handle());
    }

    static List<VisibleRow> scanVisible(
            Transaction rawTransaction,
            Descriptor table,
            MvccRawStoreTransactionContext context) throws StandardException {
        context.bind(table);
        long snapshot = context.snapshotSequence(table);
        List<VisibleRow> rows = new ArrayList<>();
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return rows;
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
                    Object[] directory = directoryTemplate(rawTransaction);
                    page.fetchFromSlot(null, slot, directory, null, false);
                    if (MvccRawStoreFormat.intAt(directory, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                            != MvccRawStoreFormat.DIRECTORY_KIND) {
                        continue;
                    }
                    long rowId = MvccRawStoreFormat.longAt(directory, MvccRawStoreFormat.DIRECTORY_ROW_ID);
                    long versionId = MvccRawStoreFormat.longAt(
                            directory,
                            MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
                    VersionRecord version = findVersion(rawTransaction, table, versionId);
                    if (version != null && visible(version, context.transactionId(), snapshot)) {
                        rows.add(new VisibleRow(rowId, version.values(), version.handle()));
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
        return rows;
    }

    static long committedHighWater(Transaction rawTransaction, Descriptor table) throws StandardException {
        return allocatorField(
                rawTransaction,
                table,
                MvccRawStoreFormat.ALLOCATOR_COMMITTED_HIGH_WATER);
    }

    static boolean pendingVersionExists(
            Transaction rawTransaction,
            Descriptor table,
            PendingVersion pending,
            long creatorTransactionId) throws StandardException {
        if (!pending.table().metadataContainer().equals(table.metadataContainer())) {
            return false;
        }
        VersionRecord version = findVersion(rawTransaction, table, pending.versionId());
        return version != null
                && version.creatorTransactionId() == creatorTransactionId
                && version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE;
    }

    static void stampPendingVersions(
            Transaction rawTransaction,
            Descriptor table,
            List<PendingVersion> pending,
            long commitSequence) throws StandardException {
        for (PendingVersion version : pending) {
            if (!version.table().metadataContainer().equals(table.metadataContainer())) {
                throw new IllegalStateException("RawStore MVCC transaction crossed table boundaries");
            }
            updateVersionBegin(rawTransaction, table, version, commitSequence);
        }
        updateAllocatorField(
                rawTransaction,
                table,
                MvccRawStoreFormat.ALLOCATOR_COMMITTED_HIGH_WATER,
                commitSequence);
    }

    static void drop(Transaction rawTransaction, Descriptor table) throws StandardException {
        // Readers acquire metadata before versions. Drop follows the same order
        // so a concurrent reader cannot hold metadata while waiting on versions.
        rawTransaction.dropContainer(table.metadataContainer());
        rawTransaction.dropContainer(table.versionContainer());
    }

    private static void initializeMetadataContainer(Transaction rawTransaction, Descriptor descriptor)
            throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                descriptor.metadataContainer(),
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_FORUPDATE
                        | (descriptor.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(rawTransaction, descriptor),
                    null,
                    null,
                    (byte) INSERT_FLAGS,
                    OVERFLOW_THRESHOLD);
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER + 1,
                    allocatorRow(rawTransaction),
                    null,
                    null,
                    (byte) INSERT_FLAGS,
                    OVERFLOW_THRESHOLD);
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static void initializeVersionContainer(Transaction rawTransaction, Descriptor descriptor)
            throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                descriptor.versionContainer(),
                lockingPolicy(rawTransaction),
                ContainerHandle.MODE_FORUPDATE
                        | (descriptor.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    new Object[] {
                            MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.VERSION_CONTAINER_KIND),
                            MvccRawStoreFormat.intValue(rawTransaction, MvccRawStoreFormat.FORMAT_VERSION)
                    },
                    null,
                    null,
                    (byte) INSERT_FLAGS,
                    OVERFLOW_THRESHOLD);
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static Object[] controlRow(Transaction transaction, Descriptor descriptor) throws StandardException {
        Object[] row = controlTemplate(transaction, descriptor.columnCount());
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, MvccRawStoreFormat.MAGIC);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.CONTROL_KIND);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.metadataContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(
                transaction,
                descriptor.versionContainer().getContainerId());
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.columnCount());
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(
                transaction,
                descriptor.temporary() ? 1 : 0);
        for (int index = 0; index < descriptor.columnCount(); index++) {
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + index] = MvccRawStoreFormat.intValue(
                    transaction,
                    descriptor.formatIds()[index]);
            row[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + descriptor.columnCount() + index] =
                    MvccRawStoreFormat.intValue(transaction, descriptor.collationIds()[index]);
        }
        return row;
    }

    private static Object[] allocatorRow(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.ALLOCATOR_KIND),
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 1L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static Object[] versionRow(
            Transaction transaction,
            Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            StoreDataValue[] values) throws StandardException {
        Object[] row = versionTemplate(transaction, table);
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.VERSION_KIND);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.VERSION_ROW_ID] = MvccRawStoreFormat.longValue(transaction, rowId);
        row[MvccRawStoreFormat.VERSION_ID] = MvccRawStoreFormat.longValue(transaction, versionId);
        row[MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID] = MvccRawStoreFormat.longValue(
                transaction,
                creatorTransactionId);
        row[MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE);
        row[MvccRawStoreFormat.VERSION_END_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE);
        row[MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID] = MvccRawStoreFormat.longValue(
                transaction,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION);
        row[MvccRawStoreFormat.VERSION_FLAGS] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.LIVE_FLAGS);
        StoreDataValue[] clone = StoreValueCopySupport.cloneRow(values);
        System.arraycopy(clone, 0, row, MvccRawStoreFormat.VERSION_PAYLOAD_START, clone.length);
        return row;
    }

    private static Allocation allocateIdentifiers(Transaction transaction, Descriptor table)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            Object[] allocator = allocatorTemplate(transaction);
            int slot = Page.FIRST_SLOT_NUMBER + 1;
            page.fetchFromSlot(null, slot, allocator, null, false);
            long rowId = MvccRawStoreFormat.longAt(allocator, MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID);
            long versionId = MvccRawStoreFormat.longAt(
                    allocator,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID);
            if (rowId <= 0L || versionId <= 0L
                    || rowId == Long.MAX_VALUE || versionId == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "RawStore MVCC logical identity allocator is invalid or exhausted: row="
                                + rowId + ", version=" + versionId);
            }
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_ROW_ID,
                    MvccRawStoreFormat.longValue(transaction, rowId + 1L),
                    null);
            page.updateFieldAtSlot(
                    slot,
                    MvccRawStoreFormat.ALLOCATOR_NEXT_VERSION_ID,
                    MvccRawStoreFormat.longValue(transaction, versionId + 1L),
                    null);
            return new Allocation(rowId, versionId);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static long allocatorField(Transaction transaction, Descriptor table, int field)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC metadata container is missing: " + table.metadataContainer());
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
            page.fetchFieldFromSlot(Page.FIRST_SLOT_NUMBER + 1, field, value);
            return StoreTypeUtil.getLong(value);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void updateAllocatorField(
            Transaction transaction,
            Descriptor table,
            int field,
            long value) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            page.updateFieldAtSlot(
                    Page.FIRST_SLOT_NUMBER + 1,
                    field,
                    MvccRawStoreFormat.longValue(transaction, value),
                    null);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static RecordHandle insertRow(
            Transaction transaction,
            ContainerKey key,
            Object[] row) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            page = container.getFirstPage();
            while (page != null) {
                RecordHandle handle = page.insertAtSlot(
                        page.recordCount(),
                        row,
                        (FormatableBitSet) null,
                        null,
                        (byte) INSERT_FLAGS,
                        OVERFLOW_THRESHOLD);
                if (handle != null) {
                    return handle;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            page = container.addPage();
            RecordHandle handle = page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    row,
                    null,
                    null,
                    (byte) INSERT_FLAGS,
                    OVERFLOW_THRESHOLD);
            if (handle == null) {
                throw new IllegalStateException("RawStore MVCC row did not fit on an empty page");
            }
            return handle;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    private static long findHeadVersionId(Transaction transaction, Descriptor table, long rowId)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return 0L;
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
                    Object[] directory = directoryTemplate(transaction);
                    page.fetchFromSlot(null, slot, directory, null, false);
                    if (MvccRawStoreFormat.intAt(directory, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                            == MvccRawStoreFormat.DIRECTORY_KIND
                            && MvccRawStoreFormat.longAt(directory, MvccRawStoreFormat.DIRECTORY_ROW_ID)
                            == rowId) {
                        return MvccRawStoreFormat.longAt(
                                directory,
                                MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return 0L;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static VersionRecord findVersion(Transaction transaction, Descriptor table, long versionId)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return null;
        }
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
                    Object[] row = versionTemplate(transaction, table);
                    RecordHandle handle = page.fetchFromSlot(null, slot, row, null, false);
                    if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_KIND_FIELD)
                            == MvccRawStoreFormat.VERSION_KIND
                            && MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_ID) == versionId) {
                        return decodeVersion(row, table, handle);
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            return null;
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    private static void updateVersionBegin(
            Transaction transaction,
            Descriptor table,
            PendingVersion pending,
            long commitSequence) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
        Page page = null;
        try {
            RecordHandle handle = pending.handle();
            page = container.getPage(handle.getPageNumber());
            if (page != null) {
                int slot = page.getSlotNumber(handle);
                if (slot >= Page.FIRST_SLOT_NUMBER && !page.isDeletedAtSlot(slot)) {
                    StoreDataValue storedVersionId = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(
                            slot,
                            MvccRawStoreFormat.VERSION_ID,
                            storedVersionId);
                    if (StoreTypeUtil.getLong(storedVersionId) == pending.versionId()) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.longValue(transaction, commitSequence),
                                null);
                        return;
                    }
                }
                page.unlatch();
                page = null;
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        updateVersionBeginByLogicalId(transaction, table, pending.versionId(), commitSequence);
    }

    private static void updateVersionBeginByLogicalId(
            Transaction transaction,
            Descriptor table,
            long versionId,
            long commitSequence) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                table.versionContainer(),
                lockingPolicy(transaction),
                ContainerHandle.MODE_FORUPDATE);
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
                    StoreDataValue candidate = MvccRawStoreFormat.longValue(transaction, 0L);
                    page.fetchFieldFromSlot(slot, MvccRawStoreFormat.VERSION_ID, candidate);
                    if (StoreTypeUtil.getLong(candidate) == versionId) {
                        page.updateFieldAtSlot(
                                slot,
                                MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE,
                                MvccRawStoreFormat.longValue(transaction, commitSequence),
                                null);
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
        throw new IllegalStateException("RawStore MVCC version disappeared before commit: " + versionId);
    }


    private static LockingPolicy lockingPolicy(Transaction transaction) {
        return transaction.newLockingPolicy(
                LockingPolicy.MODE_CONTAINER,
                TransactionController.ISOLATION_SERIALIZABLE,
                true);
    }

    private static boolean visible(VersionRecord version, long transactionId, long snapshotSequence) {
        if (version.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return version.creatorTransactionId() == transactionId;
        }
        return version.beginSequence() <= snapshotSequence
                && snapshotSequence < version.endSequence();
    }

    private static VersionRecord decodeVersion(Object[] row, Descriptor table, RecordHandle handle)
            throws StandardException {
        StoreDataValue[] values = new StoreDataValue[table.columnCount()];
        for (int index = 0; index < values.length; index++) {
            values[index] = StoreValueCopySupport.cloneValue(
                    (StoreDataValue) row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index]);
        }
        return new VersionRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_END_SEQUENCE),
                values,
                handle);
    }

    private static Object[] controlPrefixTemplate(Transaction transaction) throws StandardException {
        return controlTemplate(transaction, 0);
    }

    private static Object[] controlTemplate(Transaction transaction, int columnCount) throws StandardException {
        Object[] row = new Object[MvccRawStoreFormat.CONTROL_FIXED_FIELDS + (columnCount * 2)];
        row[MvccRawStoreFormat.CONTROL_MAGIC] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_METADATA_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_VERSION_CONTAINER] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CONTROL_COLUMN_COUNT] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CONTROL_TEMPORARY] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int index = MvccRawStoreFormat.CONTROL_FIXED_FIELDS; index < row.length; index++) {
            row[index] = MvccRawStoreFormat.intValue(transaction, 0);
        }
        return row;
    }

    private static Object[] allocatorTemplate(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static Object[] directoryTemplate(Transaction transaction) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.longValue(transaction, 0L)
        };
    }

    private static Object[] versionTemplate(Transaction transaction, Descriptor table) throws StandardException {
        Object[] row = new Object[MvccRawStoreFormat.VERSION_PAYLOAD_START + table.columnCount()];
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int field = MvccRawStoreFormat.VERSION_ROW_ID;
                field <= MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID;
                field++) {
            row[field] = MvccRawStoreFormat.longValue(transaction, 0L);
        }
        row[MvccRawStoreFormat.VERSION_FLAGS] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int index = 0; index < table.columnCount(); index++) {
            row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index] = MvccRawStoreFormat.nullValue(
                    transaction,
                    table.formatIds()[index],
                    table.collationIds()[index]);
        }
        return row;
    }

    private record Allocation(long rowId, long versionId) {
    }

    private record VersionRecord(
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            StoreDataValue[] values,
            RecordHandle handle) {
    }
}
