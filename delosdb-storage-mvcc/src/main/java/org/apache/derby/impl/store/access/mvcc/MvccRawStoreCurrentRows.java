/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreCurrentRows

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/** M2 physical current-row records. The current payload exists here exactly once. */
final class MvccRawStoreCurrentRows {
    private MvccRawStoreCurrentRows() {
    }

    static Object[] row(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long historyVersionId,
            MvccRawStoreTable.RecordHint historyHint,
            StoreDataValue[] values) throws StandardException {
        Object[] row = template(transaction, table, null);
        row[MvccRawStoreFormat.CURRENT_ROW_KIND_FIELD] =
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.CURRENT_ROW_KIND);
        row[MvccRawStoreFormat.CURRENT_ROW_FORMAT_VERSION] =
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.CURRENT_ROW_ROW_ID] = MvccRawStoreFormat.longValue(transaction, rowId);
        row[MvccRawStoreFormat.CURRENT_ROW_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, versionId);
        row[MvccRawStoreFormat.CURRENT_ROW_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, creatorTransactionId);
        row[MvccRawStoreFormat.CURRENT_ROW_BEGIN_SEQUENCE] =
                MvccRawStoreFormat.longValue(transaction, beginSequence);
        row[MvccRawStoreFormat.CURRENT_ROW_FLAGS] = MvccRawStoreFormat.intValue(transaction, flags);
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_VERSION_ID] =
                MvccRawStoreFormat.longValue(transaction, historyVersionId);
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_PAGE] =
                MvccRawStoreFormat.longValue(transaction, historyHint.pageNumber());
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_RECORD] =
                MvccRawStoreFormat.intValue(transaction, historyHint.recordId());
        if (values != null) {
            StoreDataValue[] clone = StoreValueCopySupport.cloneRow(values, true);
            System.arraycopy(clone, 0, row, MvccRawStoreFormat.CURRENT_ROW_PAYLOAD_START, clone.length);
        }
        return row;
    }

    static MvccRawStoreTable.DirectoryRecord decodeAtSlot(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        if (fieldCount != MvccRawStoreFormat.currentRowFieldCount(table.columnCount())) {
            throw new IllegalStateException(
                    "RawStore MVCC current row has unsupported field count: " + fieldCount);
        }
        Object[] row = template(transaction, table, projection);
        RecordHandle handle = page.fetchFromSlot(
                null,
                slot,
                row,
                projection == null ? null : descriptor(table, projection),
                false);
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CURRENT_ROW_KIND_FIELD)
                != MvccRawStoreFormat.CURRENT_ROW_KIND) {
            return null;
        }
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CURRENT_ROW_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            throw new IllegalStateException("RawStore MVCC current row format is unsupported");
        }
        long rowId = MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CURRENT_ROW_ROW_ID);
        long versionId = MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CURRENT_ROW_VERSION_ID);
        long creatorTransactionId = MvccRawStoreFormat.longAt(
                row, MvccRawStoreFormat.CURRENT_ROW_CREATOR_TRANSACTION_ID);
        long beginSequence = MvccRawStoreFormat.longAt(
                row, MvccRawStoreFormat.CURRENT_ROW_BEGIN_SEQUENCE);
        int flags = MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CURRENT_ROW_FLAGS);
        long historyVersionId = MvccRawStoreFormat.longAt(
                row, MvccRawStoreFormat.CURRENT_ROW_HISTORY_VERSION_ID);
        MvccRawStoreTable.RecordHint historyHint = new MvccRawStoreTable.RecordHint(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_PAGE),
                MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_RECORD));

        StoreDataValue[] values = projection != null && !projection.includesPayload()
                ? null
                : new StoreDataValue[table.columnCount()];
        if (values != null) {
            for (int column = 0; column < values.length; column++) {
                if (projection == null || projection.includes(column)) {
                    StoreDataValue value = (StoreDataValue) row[
                            MvccRawStoreFormat.CURRENT_ROW_PAYLOAD_START + column];
                    values[column] = value == null
                            ? null
                            : StoreValueCopySupport.cloneValue(value, true);
                }
            }
        }
        MvccRawStoreTable.DirectoryHeadSummary summary =
                new MvccRawStoreTable.DirectoryHeadSummary(
                        true, creatorTransactionId, beginSequence, flags);
        MvccRawStoreTable.VersionRecord current = new MvccRawStoreTable.VersionRecord(
                rowId,
                versionId,
                creatorTransactionId,
                beginSequence,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE,
                historyVersionId,
                historyHint,
                flags,
                values,
                handle);
        return new MvccRawStoreTable.DirectoryRecord(
                rowId,
                new MvccRawStoreTable.DirectoryHead(
                        versionId, MvccRawStoreTable.RecordHint.NONE, summary),
                new MvccRawStoreTable.DirectoryHead(
                        historyVersionId,
                        historyHint,
                        MvccRawStoreTable.DirectoryHeadSummary.NONE),
                current,
                handle);
    }

    private static FetchDescriptor descriptor(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection) {
        org.apache.derby.iapi.services.io.FormatableBitSet fields =
                new org.apache.derby.iapi.services.io.FormatableBitSet(
                        MvccRawStoreFormat.currentRowFieldCount(table.columnCount()));
        for (int field = MvccRawStoreFormat.CURRENT_ROW_KIND_FIELD;
                field < MvccRawStoreFormat.CURRENT_ROW_PAYLOAD_START;
                field++) {
            fields.set(field);
        }
        for (int column = 0; column < table.columnCount(); column++) {
            if (projection.includes(column)) {
                fields.set(MvccRawStoreFormat.CURRENT_ROW_PAYLOAD_START + column);
            }
        }
        return new FetchDescriptor(
                MvccRawStoreFormat.currentRowFieldCount(table.columnCount()), fields, null);
    }

    private static Object[] template(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        Object[] row = new Object[MvccRawStoreFormat.currentRowFieldCount(table.columnCount())];
        row[MvccRawStoreFormat.CURRENT_ROW_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CURRENT_ROW_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CURRENT_ROW_ROW_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_VERSION_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_BEGIN_SEQUENCE] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_FLAGS] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_VERSION_ID] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_PAGE] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[MvccRawStoreFormat.CURRENT_ROW_HISTORY_HINT_RECORD] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int column = 0; column < table.columnCount(); column++) {
            if (projection == null || projection.includes(column)) {
                row[MvccRawStoreFormat.CURRENT_ROW_PAYLOAD_START + column] =
                        MvccRawStoreFormat.nullValue(
                                transaction, table.formatId(column), table.collationId(column));
            }
        }
        return row;
    }
}
