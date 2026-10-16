/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.apache.derby.iapi.services.io.ArrayInputStream;
import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Compensation;
import org.apache.derby.iapi.store.raw.Loggable;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.Undoable;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.shared.common.error.StandardException;

/** Self-contained single-page CLR. The before image is a serialized field of
 * the compensation record, NOT optional data (FileLogger does not write CLR
 * optional data). Consequently redo never consults the live archive, including
 * after the source insertion has itself been purged by rollback.
 */
public final class ArchivedImageCompensation extends PageBasicOperation implements Compensation {
    static volatile ArchivedUpdateOperation.ApplyObserver testAfterApply;

    private int recordId;
    private byte[] before;
    private transient ArchivedUpdateOperation original;

    public ArchivedImageCompensation() {
    }

    ArchivedImageCompensation(BasePage page, int recordId, byte[] before,
            ArchivedUpdateOperation original) {
        super(page);
        this.recordId = recordId;
        this.before = before;
        this.original = original;
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_ARCHIVED_IMAGE_UNDO_V1;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        CompressedNumber.writeInt(out, recordId);
        CompressedNumber.writeInt(out, before.length);
        out.write(before);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        recordId = CompressedNumber.readInt(in);
        if (recordId < RecordHandle.FIRST_RECORD_ID) {
            throw new IOException("Invalid archived compensation record identity");
        }
        int length = CompressedNumber.readInt(in);
        ArchivedBeforeImage.checkLength(length);
        before = new byte[length];
        in.readFully(before);
    }

    @Override
    public void setUndoOp(Undoable operation) {
        if (!(operation instanceof ArchivedUpdateOperation archived)) {
            throw new IllegalArgumentException("Wrong original operation for archived-image CLR");
        }
        original = archived;
    }

    @Override
    public int group() {
        return Loggable.RAWSTORE | Loggable.COMPENSATION;
    }

    @Override
    public void doMe(Transaction transaction, LogInstant instant, LimitObjectInput ignored)
            throws StandardException, IOException {
        try {
            restore(page, recordId, instant, before);
            ArchivedUpdateOperation.ApplyObserver observer = testAfterApply;
            if (observer != null) {
                observer.applied(transaction, instant);
            }
        } finally {
            releaseResource(transaction);
        }
    }

    static void restore(BasePage target, int recordId, LogInstant instant, byte[] before)
            throws StandardException, IOException {
        int slot = target.findRecordById(recordId, 0);
        if (slot < 0) {
            throw new IOException("Archived compensation target record is absent");
        }
        ArrayInputStream input = new ArrayInputStream(before);
        target.storeRecord(instant, slot, false, input);
        if (input.available() != 0) {
            throw new IOException("Unexpected archived compensation image tail");
        }
        target.setAuxObject(null);
    }

    @Override
    public void releaseResource(Transaction transaction) {
        if (original != null) {
            original.releaseResource(transaction);
        }
        super.releaseResource(transaction);
    }

    @Override
    public void restoreMe(Transaction transaction, BasePage target, LogInstant instant,
            LimitObjectInput in) throws IOException {
        throw new IOException("A compensation record cannot itself be undone");
    }
}
