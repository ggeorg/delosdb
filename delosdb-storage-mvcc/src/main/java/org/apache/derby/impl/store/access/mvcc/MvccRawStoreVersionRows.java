/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreVersionRows

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

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

/** Physical RawStore version-row templates and projection-aware decoding. */
final class MvccRawStoreVersionRows {
    static final class FetchProjection {
        private final FormatableBitSet payloadColumns;
        private final FetchDescriptor baseDescriptor;
        private final FetchDescriptor hintDescriptor;

        private FetchProjection(
                MvccRawStoreTable.Descriptor table,
                FormatableBitSet payloadColumns) {
            this.payloadColumns = (FormatableBitSet) payloadColumns.clone();
            baseDescriptor = descriptor(
                    table,
                    MvccRawStoreFormat.versionBaseFieldCount(table.columnCount()));
            hintDescriptor = descriptor(
                    table,
                    MvccRawStoreFormat.versionHintFieldCount(table.columnCount()));
        }

        private FetchDescriptor descriptor(
                MvccRawStoreTable.Descriptor table,
                int fieldCount) {
            FormatableBitSet fields = new FormatableBitSet(fieldCount);
            for (int field = 0; field < MvccRawStoreFormat.VERSION_PAYLOAD_START; field++) {
                fields.set(field);
            }
            for (int column = 0; column < table.columnCount(); column++) {
                if (includes(column)) {
                    fields.set(MvccRawStoreFormat.VERSION_PAYLOAD_START + column);
                }
            }
            for (int field = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
                    field < fieldCount;
                    field++) {
                fields.set(field);
            }
            return new FetchDescriptor(fieldCount, fields, null);
        }

        private boolean includes(int column) {
            return column < payloadColumns.size() && payloadColumns.isSet(column);
        }

        private boolean includesPayload() {
            return payloadColumns.anySetBit(-1) >= 0;
        }

        private FetchDescriptor descriptor(int fieldCount, int columnCount) {
            if (fieldCount == MvccRawStoreFormat.versionBaseFieldCount(columnCount)) {
                return baseDescriptor;
            }
            if (fieldCount == MvccRawStoreFormat.versionHintFieldCount(columnCount)) {
                return hintDescriptor;
            }
            throw new IllegalArgumentException(
                    "Unsupported RawStore MVCC version field count: " + fieldCount);
        }
    }

    static final class Decoder {
        private final Transaction transaction;
        private final MvccRawStoreTable.Descriptor table;
        private final FetchProjection projection;
        private Object[] baseRow;
        private Object[] hintRow;

        Decoder(
                Transaction transaction,
                MvccRawStoreTable.Descriptor table,
                FetchProjection projection) {
            this.transaction = transaction;
            this.table = table;
            this.projection = projection;
        }

        MvccRawStoreTable.VersionRecord decodeAtSlot(Page page, int slot)
                throws StandardException {
            int fieldCount = page.fetchNumFieldsAtSlot(slot);
            int baseFieldCount = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
            int hintFieldCount = MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
            if (fieldCount != baseFieldCount && fieldCount != hintFieldCount) {
                throw new IllegalStateException(
                        "RawStore MVCC version row has unsupported field count: " + fieldCount);
            }
            boolean includeHint = fieldCount == hintFieldCount;
            Object[] row = row(includeHint);
            RecordHandle handle = page.fetchFromSlot(
                    null,
                    slot,
                    row,
                    projection == null
                            ? null
                            : projection.descriptor(fieldCount, table.columnCount()),
                    false);
            if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_KIND_FIELD)
                    != MvccRawStoreFormat.VERSION_KIND) {
                return null;
            }
            if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_FORMAT_VERSION)
                    != MvccRawStoreFormat.FORMAT_VERSION) {
                throw new IllegalStateException("RawStore MVCC version row format is unsupported");
            }
            return decode(row, table, handle, projection);
        }

        private Object[] row(boolean includeHint) throws StandardException {
            if (includeHint) {
                if (hintRow == null) {
                    hintRow = template(transaction, table, true, projection);
                }
                return hintRow;
            }
            if (baseRow == null) {
                baseRow = template(transaction, table, false, projection);
            }
            return baseRow;
        }
    }

    private MvccRawStoreVersionRows() {
    }

    static FetchProjection projection(
            MvccRawStoreTable.Descriptor table,
            FormatableBitSet payloadColumns) {
        return payloadColumns == null ? null : new FetchProjection(table, payloadColumns);
    }

    static FetchProjection metadataProjection(MvccRawStoreTable.Descriptor table) {
        return new FetchProjection(table, new FormatableBitSet(table.columnCount()));
    }

    static MvccRawStoreTable.VersionRecord decodeAtSlot(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot) throws StandardException {
        return decodeAtSlot(transaction, table, page, slot, null);
    }

    static MvccRawStoreTable.VersionRecord decodeAtSlot(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot,
            FetchProjection projection) throws StandardException {
        int fieldCount = page.fetchNumFieldsAtSlot(slot);
        int baseFieldCount = MvccRawStoreFormat.versionBaseFieldCount(table.columnCount());
        int hintFieldCount = MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
        if (fieldCount != baseFieldCount && fieldCount != hintFieldCount) {
            throw new IllegalStateException(
                    "RawStore MVCC version row has unsupported field count: " + fieldCount);
        }
        boolean includeHint = fieldCount == hintFieldCount;
        Object[] row = template(transaction, table, includeHint, projection);
        RecordHandle handle = page.fetchFromSlot(
                null,
                slot,
                row,
                projection == null
                        ? null
                        : projection.descriptor(fieldCount, table.columnCount()),
                false);
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_KIND_FIELD)
                != MvccRawStoreFormat.VERSION_KIND) {
            return null;
        }
        if (MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION) {
            throw new IllegalStateException("RawStore MVCC version row format is unsupported");
        }
        return decode(row, table, handle, projection);
    }

    static Object[] template(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            boolean includeHint) throws StandardException {
        return template(transaction, table, includeHint, null);
    }

    private static MvccRawStoreTable.VersionRecord decode(
            Object[] row,
            MvccRawStoreTable.Descriptor table,
            RecordHandle handle,
            FetchProjection projection) throws StandardException {
        StoreDataValue[] values = projection != null && !projection.includesPayload()
                ? null
                : new StoreDataValue[table.columnCount()];
        if (values != null) {
            for (int index = 0; index < values.length; index++) {
                if (projection == null || projection.includes(index)) {
                    values[index] = StoreValueCopySupport.cloneValue(
                            (StoreDataValue) row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index],
                            true);
                }
            }
        }
        boolean hasHint = row.length == MvccRawStoreFormat.versionHintFieldCount(table.columnCount());
        MvccRawStoreTable.RecordHint previousHint = hasHint
                ? new MvccRawStoreTable.RecordHint(
                        MvccRawStoreFormat.longAt(
                                row,
                                MvccRawStoreFormat.versionHintPageField(table.columnCount())),
                        MvccRawStoreFormat.intAt(
                                row,
                                MvccRawStoreFormat.versionHintRecordField(table.columnCount())))
                : MvccRawStoreTable.RecordHint.NONE;
        return new MvccRawStoreTable.VersionRecord(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_ROW_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_BEGIN_SEQUENCE),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_END_SEQUENCE),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID),
                previousHint,
                MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.VERSION_FLAGS),
                values,
                handle);
    }

    private static Object[] template(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            boolean includeHint,
            FetchProjection projection) throws StandardException {
        Object[] row = new Object[includeHint
                ? MvccRawStoreFormat.versionHintFieldCount(table.columnCount())
                : MvccRawStoreFormat.versionBaseFieldCount(table.columnCount())];
        row[MvccRawStoreFormat.VERSION_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.VERSION_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int field = MvccRawStoreFormat.VERSION_ROW_ID;
                field <= MvccRawStoreFormat.VERSION_PREVIOUS_VERSION_ID;
                field++) {
            row[field] = MvccRawStoreFormat.longValue(transaction, 0L);
        }
        row[MvccRawStoreFormat.VERSION_FLAGS] = MvccRawStoreFormat.intValue(transaction, 0);
        for (int index = 0; index < table.columnCount(); index++) {
            if (projection == null || projection.includes(index)) {
                row[MvccRawStoreFormat.VERSION_PAYLOAD_START + index] =
                        MvccRawStoreFormat.nullValue(
                                transaction,
                                table.formatId(index),
                                table.collationId(index));
            }
        }
        if (includeHint) {
            row[MvccRawStoreFormat.versionHintPageField(table.columnCount())] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
            row[MvccRawStoreFormat.versionHintRecordField(table.columnCount())] =
                    MvccRawStoreFormat.intValue(transaction, 0);
        }
        return row;
    }
}
