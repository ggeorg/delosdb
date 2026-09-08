/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derby.impl.store.raw.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Arrays;

import org.apache.derby.iapi.services.io.CompressedNumber;
import org.apache.derby.iapi.services.io.LimitObjectInput;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.raw.Compensation;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.raw.Undoable;
import org.apache.derby.iapi.store.raw.data.RawContainerHandle;
import org.apache.derby.iapi.store.raw.log.LogInstant;
import org.apache.derby.iapi.store.raw.xact.RawTransaction;
import org.apache.derby.iapi.util.ByteArray;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** A single-page replacement whose forward log shares old bytes with an
 * earlier same-transaction archive insertion. The optional data contains the
 * normal after image followed by a bounded before-image recipe. Rollback
 * resolves the recipe BEFORE logging a self-contained compensation record.
 * Redo (including compensation redo) never dereferences the archive.
 */
public final class ArchivedUpdateOperation extends PageBasicOperation implements Undoable {
    @FunctionalInterface
    interface ApplyObserver {
        void applied(Transaction transaction, LogInstant instant) throws StandardException;
    }

    static volatile ApplyObserver testAfterApply;

    private int slot;
    private int recordId;
    private ContainerKey archiveContainer;
    private long archivePage;
    private int archiveRecordId;
    private int afterLength;
    private int recipeLength;
    private transient ByteArray prepared;

    public ArchivedUpdateOperation() {
    }

    ArchivedUpdateOperation(BasePage page, int slot, RecordHandle archive,
            byte[] after, byte[] recipe) {
        super(page);
        this.slot = slot;
        this.recordId = page.getRecordHandleAtSlot(slot).getId();
        this.archiveContainer = archive.getContainerId();
        this.archivePage = archive.getPageNumber();
        this.archiveRecordId = archive.getId();
        this.afterLength = after.length;
        this.recipeLength = recipe.length;
        byte[] bytes = Arrays.copyOf(after, after.length + recipe.length);
        System.arraycopy(recipe, 0, bytes, after.length, recipe.length);
        this.prepared = new ByteArray(bytes);
    }

    @Override
    public int getTypeFormatId() {
        return StoredFormatIds.LOGOP_ARCHIVED_UPDATE_V1;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal(out);
        CompressedNumber.writeInt(out, slot);
        CompressedNumber.writeInt(out, recordId);
        archiveContainer.writeExternal(out);
        CompressedNumber.writeLong(out, archivePage);
        CompressedNumber.writeInt(out, archiveRecordId);
        CompressedNumber.writeInt(out, afterLength);
        CompressedNumber.writeInt(out, recipeLength);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        super.readExternal(in);
        slot = CompressedNumber.readInt(in);
        recordId = CompressedNumber.readInt(in);
        archiveContainer = ContainerKey.read(in);
        archivePage = CompressedNumber.readLong(in);
        archiveRecordId = CompressedNumber.readInt(in);
        afterLength = CompressedNumber.readInt(in);
        recipeLength = CompressedNumber.readInt(in);
        ArchivedBeforeImage.checkLength(afterLength);
        if (recipeLength < 8 || recipeLength > ArchivedBeforeImage.MAX_IMAGE_BYTES * 2) {
            throw new IOException("Invalid archived update recipe size");
        }
        if (recordId < RecordHandle.FIRST_RECORD_ID || archiveRecordId < RecordHandle.FIRST_RECORD_ID) {
            throw new IOException("Invalid archived update record identity");
        }
    }

    @Override
    public ByteArray getPreparedLog() {
        return prepared;
    }

    @Override
    public void doMe(Transaction transaction, LogInstant instant, LimitObjectInput in)
            throws StandardException, IOException {
        // Only this target page participates in redo. History is an ordinary
        // earlier InsertOperation with its own page version and redo decision.
        if (in.available() != afterLength + recipeLength) {
            throw new IOException("Archived update optional-data length mismatch");
        }
        page.storeRecord(instant, slot, false, in);
        if (in.available() != recipeLength) {
            throw new IOException("Archived update after-image length mismatch");
        }
        ApplyObserver observer = testAfterApply;
        if (observer != null) {
            observer.applied(transaction, instant);
        }
    }

    @Override
    public Compensation generateUndo(Transaction transaction, LimitObjectInput in)
            throws StandardException, IOException {
        // Resolve and release the source before latching the target. Reverse
        // undo reaches this operation before its earlier archive insertion.
        byte[] before = readBeforeImage(transaction, in);
        BasePage target = findpage(transaction);
        if (target == null) {
            throw new IOException("Archived update target was dropped before undo");
        }
        target.preDirty();
        return new ArchivedImageCompensation(target, recordId, before, this);
    }

    private byte[] readBeforeImage(Transaction transaction, LimitObjectInput in)
            throws StandardException, IOException {
        if (in.skipBytes(afterLength) != afterLength) {
            throw new IOException("Truncated archived update after image");
        }
        byte[] recipe = new byte[recipeLength];
        in.readFully(recipe);
        if (in.available() != 0) {
            throw new IOException("Unexpected archived update trailing data");
        }
        RawContainerHandle container = ((RawTransaction) transaction)
                .openDroppedContainer(archiveContainer, null);
        if (container == null) {
            throw new IOException("Archived undo source container is absent");
        }
        BasePage source = null;
        try {
            if (container.getContainerStatus() == RawContainerHandle.COMMITTED_DROP) {
                throw new IOException("Archived undo source container was dropped");
            }
            source = (BasePage) container.getAnyPage(archivePage);
            if (source == null) {
                throw new IOException("Archived undo source page is absent");
            }
            int sourceSlot = source.findRecordById(archiveRecordId, 0);
            if (sourceSlot < 0 || source.isDeletedAtSlot(sourceSlot)) {
                throw new IOException("Archived undo source record is absent");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            source.logRecord(sourceSlot, BasePage.LOG_RECORD_DEFAULT,
                    archiveRecordId, null, bytes, null);
            return ArchivedBeforeImage.decode(recipe, bytes.toByteArray());
        } finally {
            if (source != null) {
                source.unlatch();
            }
            container.close();
        }
    }

    @Override
    public void reclaimPrepareLocks(Transaction transaction, LockingPolicy policy)
            throws StandardException {
        ContainerHandle handle = transaction.openContainer(getPageId().getContainerId(), policy,
                ContainerHandle.MODE_FORUPDATE | ContainerHandle.MODE_OPEN_FOR_LOCK_ONLY
                        | ContainerHandle.MODE_LOCK_NOWAIT);
        if (handle != null) {
            handle.close();
        }
        if (!policy.lockRecordForWrite(transaction, new RecordId(getPageId(), recordId), false, false)) {
            throw StandardException.newException(SQLState.DEADLOCK,
                    "Could not reclaim archived-update row lock");
        }
        releaseResource(transaction);
    }

    @Override
    public void restoreMe(Transaction transaction, BasePage target, LogInstant instant,
            LimitObjectInput in) throws StandardException, IOException {
        byte[] before = readBeforeImage(transaction, in);
        ArchivedImageCompensation.restore(target, recordId, instant, before);
    }
}
