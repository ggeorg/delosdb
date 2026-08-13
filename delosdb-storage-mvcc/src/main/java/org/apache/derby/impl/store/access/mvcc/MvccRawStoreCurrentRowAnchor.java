/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreCurrentRowAnchor

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** Current payload stored beside the stable MVCC directory head. */
final class MvccRawStoreCurrentRowAnchor {
    private static final int MAX_PAYLOAD_LENGTH = 2048;

    record Anchor(MvccRawStoreTable.DirectoryRecord directory, StoreDataValue[] values) {
        boolean available() {
            return values != null;
        }
    }

    private MvccRawStoreCurrentRowAnchor() {
    }

    static Object[] row(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long headVersionId,
            MvccRawStoreTable.RecordHint headHint,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            StoreDataValue[] values) throws StandardException {
        if (!eligible(values)) {
            return MvccRawStoreTable.directoryRow(
                    transaction,
                    rowId,
                    headVersionId,
                    headHint,
                    creatorTransactionId,
                    beginSequence,
                    flags);
        }
        Object[] row = template(transaction, table, null, -1);
        row[MvccRawStoreFormat.DIRECTORY_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction, MvccRawStoreFormat.DIRECTORY_KIND);
        row[MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction, MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.DIRECTORY_ROW_ID] = MvccRawStoreFormat.longValue(transaction, rowId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, headVersionId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE] =
                MvccRawStoreFormat.longValue(transaction, headHint.pageNumber());
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD] =
                MvccRawStoreFormat.intValue(transaction, headHint.recordId());
        row[MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, creatorTransactionId);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE] =
                MvccRawStoreFormat.longValue(transaction, beginSequence);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS] =
                MvccRawStoreFormat.intValue(transaction, flags);
        if (values != null) {
            StoreDataValue[] clone = StoreValueCopySupport.cloneRow(values, true);
            System.arraycopy(
                    clone,
                    0,
                    row,
                    MvccRawStoreFormat.DIRECTORY_CURRENT_PAYLOAD_START,
                    clone.length);
        }
        return row;
    }

    static Anchor findByHint(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            Page page,
            MvccRawStoreVersionRows.FetchProjection projection,
            int requiredColumn) throws StandardException {
        if (page == null
                || rowLocation == null
                || !rowLocation.hasLocatorHint()
                || page.getPageNumber() != rowLocation.locatorPageId()) {
            return null;
        }
        int slot = rowLocation.locatorSlotId();
        if (slot < Page.FIRST_SLOT_NUMBER
                || slot >= page.recordCount()
                || page.isDeletedAtSlot(slot)) {
            return null;
        }
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        int anchorFieldCount = MvccRawStoreFormat.directoryCurrentRowFieldCount(
                table.columnCount());
        if (fieldCount != anchorFieldCount) {
            MvccRawStoreTable.DirectoryRecord directory =
                    MvccRawStoreTable.decodeDirectory(transaction, page, slot);
            return directory != null && directory.rowId() == rowLocation.rowId()
                    ? new Anchor(directory, null)
                    : null;
        }

        Object[] row = template(transaction, table, projection, requiredColumn);
        RecordHandle handle = page.fetchFromSlot(
                null,
                slot,
                row,
                descriptor(table, projection, requiredColumn),
                false);
        MvccRawStoreTable.DirectoryRecord directory =
                MvccRawStoreTable.decodeDirectory(row, handle, fieldCount);
        if (directory == null || directory.rowId() != rowLocation.rowId()) {
            return null;
        }
        StoreDataValue[] values = projection != null && !projection.includesPayload()
                ? null
                : new StoreDataValue[table.columnCount()];
        if (values != null) {
            for (int column = 0; column < values.length; column++) {
                if (included(projection, column, requiredColumn)) {
                    values[column] = StoreValueCopySupport.cloneValue(
                            (StoreDataValue) row[
                                    MvccRawStoreFormat.DIRECTORY_CURRENT_PAYLOAD_START + column],
                            true);
                }
            }
        }
        return new Anchor(directory, values);
    }

    private static boolean eligible(StoreDataValue[] values) throws StandardException {
        if (values == null) {
            return false;
        }
        int payloadLength = 0;
        for (StoreDataValue value : values) {
            if (value == null || org.apache.derby.iapi.store.types.StoreTypeUtil.isNull(value)) {
                continue;
            }
            int length = org.apache.derby.iapi.store.types.StoreTypeUtil.getLength(value);
            if (length < 0 || payloadLength > MAX_PAYLOAD_LENGTH - length) {
                return false;
            }
            payloadLength += length;
        }
        return true;
    }

    private static Object[] template(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection,
            int requiredColumn) throws StandardException {
        Object[] row = new Object[MvccRawStoreFormat.directoryCurrentRowFieldCount(
                table.columnCount())];
        row[MvccRawStoreFormat.DIRECTORY_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION] =
                MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_ROW_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD] =
                MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int column = 0; column < table.columnCount(); column++) {
            if (included(projection, column, requiredColumn)) {
                row[MvccRawStoreFormat.DIRECTORY_CURRENT_PAYLOAD_START + column] =
                        MvccRawStoreFormat.nullValue(
                                transaction,
                                table.formatId(column),
                                table.collationId(column));
            }
        }
        return row;
    }

    private static boolean included(
            MvccRawStoreVersionRows.FetchProjection projection,
            int column,
            int requiredColumn) {
        return column == requiredColumn || projection == null || projection.includes(column);
    }

    private static FetchDescriptor descriptor(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection,
            int requiredColumn) {
        int fieldCount = MvccRawStoreFormat.directoryCurrentRowFieldCount(table.columnCount());
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        for (int field = 0; field < MvccRawStoreFormat.DIRECTORY_CURRENT_PAYLOAD_START; field++) {
            fields.set(field);
        }
        for (int column = 0; column < table.columnCount(); column++) {
            if (included(projection, column, requiredColumn)) {
                fields.set(MvccRawStoreFormat.DIRECTORY_CURRENT_PAYLOAD_START + column);
            }
        }
        return new FetchDescriptor(fieldCount, fields, null);
    }
}
