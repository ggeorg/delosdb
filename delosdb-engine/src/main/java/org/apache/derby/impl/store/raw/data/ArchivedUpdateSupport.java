/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.io.StreamStorable;
import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Conservative single-page fast path. All eligibility and byte comparisons
 * happen before preDirty/logAndDo; false means use the existing UPDATE path.
 */
final class ArchivedUpdateSupport {
    private ArchivedUpdateSupport() {
    }

    static boolean tryUpdate(StoredPage target, int slot, Object[] row,
            FormatableBitSet columns, Page archive, int archiveSlot) throws StandardException {
        if (!(archive instanceof StoredPage source) || target == source
                || target.owner == null || source.owner == null
                || target.owner.getTransaction() != source.owner.getTransaction()
                || target.owner.isTemporaryContainer() || source.owner.isTemporaryContainer()
                || !(target.owner.getActionSet() instanceof LoggableActions)
                || !(source.owner.getActionSet() instanceof LoggableActions)
                || target.isOverflowPage() || source.isOverflowPage()
                || row == null) {
            return false;
        }
        if (!target.owner.updateOK()) {
            throw StandardException.newException(SQLState.DATA_CONTAINER_READ_ONLY);
        }
        if (target.isDeletedAtSlot(slot)) {
            throw StandardException.newException(SQLState.DATA_UPDATE_DELETED_RECORD);
        }
        if (source.isDeletedAtSlot(archiveSlot)
                || target.getHeaderAtSlot(slot).hasOverflow()
                || source.getHeaderAtSlot(archiveSlot).hasOverflow()) {
            return false;
        }
        // Preparing an ordinary update may consume a streaming value. A
        // declined optimization must leave such a value untouched for fallback.
        if (RowUtil.nextColumn(row, columns, 0) < 0) {
            return false;
        }
        for (int column = RowUtil.nextColumn(row, columns, 0); column >= 0;
                column = RowUtil.nextColumn(row, columns, column + 1)) {
            Object value = row[column];
            if (value instanceof InputStream
                    || (value instanceof StreamStorable stream && stream.returnStream() != null)) {
                return false;
            }
        }
        try {
            byte[] sourceImage = image(source, archiveSlot);
            // Reject long-column pointers as well as long-row chains. Archive
            // reclamation/overflow must not become an implicit dependency.
            if (hasOverflowField(sourceImage, source.getPageSize())
                    || hasOverflowField(image(target, slot), target.getPageSize())) {
                return false;
            }
            RecordHandle targetRecord = target.getRecordHandleAtSlot(slot);
            UpdateOperation ordinary = new UpdateOperation(target.owner.getTransaction(), target,
                    slot, targetRecord.getId(), row, columns, -1, null, -1, null);
            if (ordinary.getNextStartColumn() != -1) {
                return false;
            }
            ByteArray log = ordinary.getPreparedLog();
            byte[] bytes = Arrays.copyOfRange(log.getArray(), log.getOffset(),
                    log.getOffset() + log.getLength());
            ArrayInputStream input = new ArrayInputStream(bytes);
            target.skipRecord(input);
            int boundary = input.getPosition();
            byte[] after = Arrays.copyOf(bytes, boundary);
            if (hasOverflowField(after, target.getPageSize())) {
                return false;
            }
            byte[] before = Arrays.copyOfRange(bytes, boundary, bytes.length);
            byte[] recipe = ArchivedBeforeImage.encode(before, sourceImage);
            // Conservatively pay for the extra source identity/length header.
            // Never emit this opcode for an expansion or negligible saving.
            if (recipe.length + 64 >= before.length) {
                return false;
            }
            if (!Arrays.equals(before, ArchivedBeforeImage.decode(recipe, sourceImage))) {
                throw new IOException("Archived before-image preparation mismatch");
            }
            ArchivedUpdateOperation operation = new ArchivedUpdateOperation(target, slot,
                    source.getRecordHandleAtSlot(archiveSlot), after, recipe);
            target.preDirty();
            target.owner.getTransaction().logAndDo(operation);
            return true;
        } catch (NoSpaceOnPage | LongColumnException noSpace) {
            return false;
        } catch (IOException failure) {
            throw StandardException.newException(SQLState.DATA_UNEXPECTED_EXCEPTION, failure);
        }
    }

    private static byte[] image(StoredPage page, int slot) throws StandardException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        page.logRecord(slot, BasePage.LOG_RECORD_DEFAULT,
                page.getRecordHandleAtSlot(slot).getId(), null, out, null);
        byte[] image = out.toByteArray();
        ArchivedBeforeImage.checkLength(image.length);
        return image;
    }

    private static boolean hasOverflowField(byte[] bytes, int pageSize) throws IOException {
        ArrayInputStream in = new ArrayInputStream(bytes);
        StoredRecordHeader header = new StoredRecordHeader();
        header.read(in);
        if (header.hasOverflow()) {
            return true;
        }
        for (int field = 0; field < header.getNumberFields(); field++) {
            int status = StoredFieldHeader.readStatus(in);
            if (StoredFieldHeader.isOverflow(status)) {
                return true;
            }
            // Prepared log fields may have a fixed two- or four-byte length.
            int length = StoredFieldHeader.readFieldDataLength(in, status, pageSize < 65536 ? 2 : 4);
            if (in.skipBytes(length) != length) {
                throw new IOException("Truncated archived-update record field");
            }
        }
        if (in.available() != 0) {
            throw new IOException("Unexpected archived-update record tail");
        }
        return false;
    }
}
