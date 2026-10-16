/*

   Derby - Class org.apache.derby.impl.store.raw.data.HeapPageReadImage

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.DataInputUtil;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.PageKey;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;

/**
 * Immutable, transient copy of one non-overflow StoredPage used only by the
 * experimental read-only heap fast path. RawStore remains the authority.
 */
final class HeapPageReadImage {
    static final int HIT = 1;
    static final int RECORD_MISSING = 0;
    static final int UNSUPPORTED = -1;

    private final PageKey pageKey;
    private final long pageVersion;
    private final byte[] pageData;
    private final int slotFieldSize;
    private final int[] recordOffsets;
    private final StoredRecordHeader[] headers;

    HeapPageReadImage(
            PageKey pageKey,
            long pageVersion,
            byte[] pageData,
            int slotFieldSize,
            int[] recordOffsets,
            StoredRecordHeader[] headers) {
        this.pageKey = pageKey;
        this.pageVersion = pageVersion;
        this.pageData = pageData;
        this.slotFieldSize = slotFieldSize;
        this.recordOffsets = recordOffsets;
        this.headers = headers;
    }

    PageKey pageKey() {
        return pageKey;
    }

    long pageVersion() {
        return pageVersion;
    }

    int bytes() {
        return pageData.length;
    }

    int fetch(RecordHandle record, Object[] row, FetchDescriptor fetchDesc) {
        int slot = findRecord(record.getId());
        if (slot < 0) {
            return RECORD_MISSING;
        }
        StoredRecordHeader header = headers[slot];
        if (header.isDeleted() || header.hasOverflow() || fetchDesc.getQualifierList() != null) {
            return UNSUPPORTED;
        }
        try {
            return decode(slot, header, row, fetchDesc) ? HIT : UNSUPPORTED;
        } catch (IOException | ClassNotFoundException ex) {
            return UNSUPPORTED;
        }
    }

    private int findRecord(int recordId) {
        for (int slot = 0; slot < headers.length; slot++) {
            if (headers[slot].getId() == recordId) {
                return slot;
            }
        }
        return -1;
    }

    private boolean decode(
            int slot,
            StoredRecordHeader header,
            Object[] row,
            FetchDescriptor fetchDesc)
            throws IOException, ClassNotFoundException {
        int startColumn = header.getFirstField();
        int maxColumn = fetchDesc.getValidColumns() == null
                ? row.length - 1 : fetchDesc.getMaxFetchColumnId();
        if (startColumn > maxColumn) {
            return true;
        }
        ArrayInputStream input = new ArrayInputStream(pageData);
        input.setPosition(recordOffsets[slot] + header.size());
        int[] validColumns = fetchDesc.getValidColumnsArray();
        int[] materialized = fetchDesc.getMaterializedColumns();
        int offset = input.getPosition();
        int highestColumn = startColumn + header.getNumberFields();
        for (int columnId = startColumn; columnId <= maxColumn; columnId++) {
            if (!requested(columnId, validColumns, materialized)) {
                if (columnId < highestColumn) {
                    offset += StoredFieldHeader.readTotalFieldLength(pageData, offset);
                }
                continue;
            }
            if (columnId >= highestColumn) {
                restoreMissing(row, columnId);
                continue;
            }
            if (!decodeField(row, columnId, input, offset)) {
                return false;
            }
            offset = input.getPosition();
        }
        return true;
    }

    private static boolean requested(int columnId, int[] validColumns, int[] materialized) {
        boolean valid = validColumns == null
                || (validColumns.length > columnId && validColumns[columnId] != 0);
        boolean alreadyMaterialized = materialized != null && materialized[columnId] != 0;
        return valid && !alreadyMaterialized;
    }

    private boolean decodeField(Object[] row, int columnId, ArrayInputStream input, int offset)
            throws IOException, ClassNotFoundException {
        int status = StoredFieldHeader.readStatus(pageData, offset);
        if (StoredFieldHeader.isOverflow(status) || StoredFieldHeader.isExtensible(status)) {
            return false;
        }
        int length = StoredFieldHeader.readFieldLengthAndSetStreamPosition(
                pageData,
                offset + StoredFieldHeader.STORED_FIELD_HEADER_STATUS_SIZE,
                status,
                slotFieldSize,
                input);
        Object column = row[columnId];
        if (!(column instanceof StoreDataValue)) {
            return false;
        }
        if (StoredFieldHeader.isNonexistent(status) || StoredFieldHeader.isNull(status)) {
            StoreTypeUtil.restoreToNull(column);
            return true;
        }
        input.setLimit(length);
        StoreTypeUtil.readExternalFromArray(column, input);
        int unread = input.clearLimit();
        if (unread != 0) {
            DataInputUtil.skipFully(input, unread);
        }
        return true;
    }

    private static void restoreMissing(Object[] row, int columnId) {
        Object column = row[columnId];
        if (column instanceof StoreDataValue) {
            StoreTypeUtil.restoreToNull(column);
        } else {
            row[columnId] = null;
        }
    }
}
