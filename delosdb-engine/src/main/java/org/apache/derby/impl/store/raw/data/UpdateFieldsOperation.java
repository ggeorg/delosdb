/*

   Derby - Class org.apache.derby.impl.store.raw.data.UpdateFieldsOperation

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.DynamicByteArrayOutputStream;
import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;
import org.apache.derby.shared.common.sanity.SanityManager;

/**
 * One physical RawStore operation which updates the same field on multiple
 * records of one page.
 *
 * <p>The optional log data contains, for every target record, the after image
 * followed by the before image of the field.  The operation is deliberately
 * physical: it is intended for fixed-location page work which does not require
 * logical undo.</p>
 *
 * <PRE>
 * @derby.formatId LOGOP_UPDATE_FIELDS
 * @derby.purpose update one field on multiple records of one page
 * @derby.diskLayout
 *     PhysicalPageOperation super
 *     count(CompressedInt)
 *     fieldId(CompressedInt)
 *     slots(CompressedInt[count])
 *     recordIds(CompressedInt[count])
 *     OptionalData repeated count times:
 *         after image of field
 *         before image of field
 * @derby.endFormat
 * </PRE>
 */
public final class UpdateFieldsOperation extends PhysicalPageOperation {
    private int count;
    private int fieldId;
    private int[] slots;
    private int[] recordIds;

    private transient ByteArray preparedLog;

    UpdateFieldsOperation(
            RawTransaction transaction,
            BasePage page,
            int[] slots,
            int[] recordIds,
            int fieldId,
            Object newValue) throws StandardException {
        super(page);
        if (slots == null || recordIds == null || slots.length == 0
                || slots.length != recordIds.length) {
            throw new IllegalArgumentException(
                    "slots and recordIds must be non-empty and have equal length");
        }
        this.count = slots.length;
        this.fieldId = fieldId;
        this.slots = slots.clone();
        this.recordIds = recordIds.clone();

        try {
            writeOptionalDataToBuffer(transaction, newValue);
        } catch (IOException failure) {
            throw StandardException.newException(
                    SQLState.DATA_UNEXPECTED_EXCEPTION, failure);
        }
    }

    /** No-arg constructor required by Formatable. */
    public UpdateFieldsOperation() {
        super();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        CompressedNumber.writeInt(out, count);
        CompressedNumber.writeInt(out, fieldId);
        for (int index = 0; index < count; index++) {
            CompressedNumber.writeInt(out, slots[index]);
            CompressedNumber.writeInt(out, recordIds[index]);
        }
    }

    @Override
    public void readExternal(ObjectInput in)
            throws IOException, ClassNotFoundException {
        super.readExternal(in);
        count = CompressedNumber.readInt(in);
        fieldId = CompressedNumber.readInt(in);
        slots = new int[count];
        recordIds = new int[count];
        for (int index = 0; index < count; index++) {
            slots[index] = CompressedNumber.readInt(in);
            recordIds[index] = CompressedNumber.readInt(in);
        }
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_UPDATE_FIELDS;
    }

    @Override
    public void doMe(
            Transaction transaction,
            LogInstant instant,
            LimitObjectInput in) throws StandardException, IOException {
        for (int index = 0; index < count; index++) {
            page.storeField(instant, slots[index], fieldId, in);
            page.skipField(in);
        }
    }

    @Override
    public void undoMe(
            Transaction transaction,
            BasePage undoPage,
            LogInstant clrInstant,
            LimitObjectInput in) throws StandardException, IOException {
        for (int index = 0; index < count; index++) {
            int slot = undoPage.findRecordById(
                    recordIds[index], Page.FIRST_SLOT_NUMBER);
            if (SanityManager.DEBUG) {
                D_RawPageSanityAssertions.assertRecordFound(
                        recordIds[index], undoPage, slot);
            }
            undoPage.skipField(in);
            undoPage.storeField(clrInstant, slot, fieldId, in);
        }
        undoPage.setAuxObject(null);
    }

    @Override
    public void restoreMe(
            Transaction transaction,
            BasePage undoPage,
            LogInstant clrInstant,
            LimitObjectInput in) throws StandardException, IOException {
        undoMe(transaction, undoPage, clrInstant, in);
    }

    @Override
    public ByteArray getPreparedLog() {
        return preparedLog;
    }

    private void writeOptionalDataToBuffer(
            RawTransaction transaction,
            Object newValue) throws StandardException, IOException {
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(page != null);
            SanityManager.ASSERT(newValue != null);
        }

        DynamicByteArrayOutputStream logBuffer = transaction.getLogBuffer();
        int optionalDataStart = logBuffer.getPosition();
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(
                    optionalDataStart == 0,
                    "Buffer for writing optional data should start at position 0");
        }

        for (int index = 0; index < count; index++) {
            page.logColumn(
                    slots[index],
                    fieldId,
                    newValue,
                    logBuffer,
                    100);
            page.logField(slots[index], fieldId, logBuffer);
        }

        int optionalDataLength = logBuffer.getPosition() - optionalDataStart;
        if (SanityManager.DEBUG) {
            SanityManager.ASSERT(
                    optionalDataLength == logBuffer.getUsed(),
                    "wrong optional data length");
        }

        logBuffer.setPosition(optionalDataStart);
        preparedLog = new ByteArray(
                logBuffer.getByteArray(),
                optionalDataStart,
                optionalDataLength);
    }
}
