/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreStreamingScan

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * A resumable cursor that streams visible Gen2 CURRENT rows into a scan's
 * fetch groups without materializing the table. It keeps its directory
 * container open between groups and remembers the page and slot to resume
 * from. The experimental fetch modes read fewer CURRENT fields per slot and
 * load what they skipped only for rows that are returned.
 */
final class MvccRawStoreStreamingScan implements AutoCloseable {
    record Batch(
            int count,
            long visited,
            long qualified,
            boolean exhausted,
            long lastRowId,
            long lastVersionId,
            RecordHandle lastVersionHandle,
            RecordHandle lastDirectoryHandle) {
    }

    private final Transaction transaction;
    private final MvccRawStoreTable.Descriptor table;
    private final long snapshotSequence;
    private final MvccRawStoreTransactionContext context;
    private final long transactionId;
    private final MvccRawStoreVersionRows.FetchProjection projection;
    private final ContainerHandle container;
    private final Object[] currentRow;
    private final StoreDataValue[] currentValues;
    private final FetchDescriptor fetchDescriptor;
    private final FetchDescriptor historyMetadataDescriptor;
    private final boolean fastCurrentVisibilityFetch;
    private final boolean skipCurrentTagFetch;
    private final boolean lazyCurrentCreatorFetch;
    private final boolean lazyCurrentIdentityFetch;
    private final FetchDescriptor currentIdentityDescriptor;
    private final int payloadStart;
    private long resumePageNumber;
    private int resumeSlot;
    private boolean started;
    private boolean exhausted;
    private boolean closed;
    private long visibleRowId;
    private long visibleVersionId;
    private RecordHandle visibleVersionHandle;
    private RecordHandle visibleDirectoryHandle;
    private StoreDataValue[] visibleValues;
    private Page visibleCurrentPage;
    private int visibleCurrentSlot = -1;
    private boolean visibleIdentityLoaded = true;

    MvccRawStoreStreamingScan(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long snapshotSequence,
            MvccRawStoreVersionRows.FetchProjection projection,
            MvccRawStoreTransactionContext context) throws StandardException {
        this.transaction = transaction;
        this.table = table;
        this.snapshotSequence = snapshotSequence;
        this.context = context;
        this.transactionId = context.transactionId();
        this.projection = projection;
        MvccRawStoreVersionRows.FetchProjection currentProjection =
                table.projectedCurrentRead() ? projection : null;
        fastCurrentVisibilityFetch = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_FAST_CURRENT_VISIBILITY_FETCH_ENABLED_PROPERTY);
        skipCurrentTagFetch = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_SKIP_CURRENT_TAG_FETCH_ENABLED_PROPERTY);
        lazyCurrentCreatorFetch = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_LAZY_CURRENT_CREATOR_FETCH_ENABLED_PROPERTY);
        lazyCurrentIdentityFetch = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_LAZY_CURRENT_IDENTITY_FETCH_ENABLED_PROPERTY);
        currentRow = MvccRawStoreCurrentRows.template(transaction, table, currentProjection);
        fetchDescriptor = fastCurrentVisibilityFetch
                ? fastCurrentVisibilityDescriptor(
                        table, projection, skipCurrentTagFetch, lazyCurrentCreatorFetch,
                        lazyCurrentIdentityFetch)
                : currentProjection == null
                        ? null
                        : currentProjection.currentDescriptor(table);
        historyMetadataDescriptor = fastCurrentVisibilityFetch && table.gen2History()
                ? historyMetadataDescriptor(table, lazyCurrentIdentityFetch)
                : null;
        currentIdentityDescriptor = lazyCurrentIdentityFetch
                ? currentIdentityDescriptor(table)
                : null;
        payloadStart = MvccRawStoreCurrentRows.currentPayloadStart(table);
        currentValues = new StoreDataValue[table.columnCount()];
        refreshCurrentValues();
        container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        exhausted = container == null;
    }

    Batch fetchGroup(
            StoreDataValue[][] rowArray,
            StoreRowLocation[] rowlocArray,
            FormatableBitSet scanColumnList,
            Qualifier[][] qualifiers) throws StandardException {
        if (exhausted || rowArray.length == 0) {
            return emptyResult();
        }
        int count = 0;
        long visited = 0L;
        long qualified = 0L;
        Page page = resumePage();
        try {
            while (page != null) {
                while (resumeSlot < page.recordCount()) {
                    int slot = resumeSlot++;
                    if (page.isDeletedAtSlot(slot) || !decodeVisible(page, slot)) {
                        continue;
                    }
                    visited++;
                    if (qualifiers != null && qualifiers.length != 0
                            && !RowUtil.qualifyRow(visibleValues, qualifiers)) {
                        continue;
                    }
                    qualified++;
                    StoreValueCopySupport.copyRow(
                            visibleValues, rowArray[count], scanColumnList);
                    copyLocation(rowlocArray, count);
                    count++;
                    if (count == rowArray.length) {
                        resumePageNumber = page.getPageNumber();
                        return result(count, visited, qualified, false);
                    }
                }
                ensureVisibleIdentityBeforeUnlatch(page);
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = null;
                page = container.getNextPage(pageNumber);
                if (page != null) {
                    resumePageNumber = page.getPageNumber();
                    resumeSlot = startSlot(page);
                }
            }
            exhausted = true;
            resumePageNumber = 0L;
            return result(count, visited, qualified, true);
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private Page resumePage() throws StandardException {
        if (!started) {
            started = true;
            Page page = container.getFirstPage();
            if (page != null) {
                resumePageNumber = page.getPageNumber();
                resumeSlot = startSlot(page);
            }
            return page;
        }
        return resumePageNumber == 0L ? null : container.getPage(resumePageNumber);
    }

    private boolean decodeVisible(Page page, int slot) throws StandardException {
        if (page.fetchNumFieldsAtSlot(slot) != MvccRawStoreCurrentRows.currentFieldCount(table)) {
            return false;
        }
        RecordHandle currentHandle = page.fetchFromSlot(
                null, slot, currentRow, fetchDescriptor, false);
        if (!validCurrentRow()) {
            return false;
        }
        refreshCurrentValues();
        long begin = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
        long creator = currentCreator(page, slot, begin);
        int flags = MvccRawStoreFormat.intAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS);
        if (context.currentVisibleTo(creator, begin, snapshotSequence)) {
            if ((flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
                return false;
            }
            setVisibleCurrent(page, slot, currentHandle);
            return true;
        }
        if (historyMetadataDescriptor != null) {
            page.fetchFromSlot(
                    currentHandle, slot, currentRow, historyMetadataDescriptor, false);
        }
        long rowId = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_ROW_ID);
        return decodeHistory(rowId, currentHandle);
    }

    private static FetchDescriptor fastCurrentVisibilityDescriptor(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection,
            boolean skipCurrentTagFetch,
            boolean lazyCurrentCreatorFetch,
            boolean lazyCurrentIdentityFetch) {
        int fieldCount = MvccRawStoreCurrentRows.currentFieldCount(table);
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        if (!skipCurrentTagFetch) {
            fields.set(MvccRawStoreFormat.DIRECTORY_KIND_FIELD);
            fields.set(MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION);
        }
        if (!lazyCurrentIdentityFetch) {
            fields.set(MvccRawStoreFormat.DIRECTORY_ROW_ID);
            fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        }
        if (!lazyCurrentCreatorFetch) {
            fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID);
        }
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS);
        int payloadStart = MvccRawStoreCurrentRows.currentPayloadStart(table);
        for (int column = 0; column < table.columnCount(); column++) {
            if (projection == null || projection.includes(column)) {
                fields.set(payloadStart + column);
            }
        }
        return new FetchDescriptor(fieldCount, fields, null);
    }

    private long currentCreator(Page page, int slot, long begin) throws StandardException {
        if (!lazyCurrentCreatorFetch) {
            return MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID);
        }
        return MvccRawStoreCurrentRows.currentCreator(page, slot, currentRow, begin);
    }

    private static FetchDescriptor historyMetadataDescriptor(
            MvccRawStoreTable.Descriptor table, boolean includeRowId) {
        int fieldCount = MvccRawStoreCurrentRows.currentFieldCount(table);
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        if (includeRowId) {
            fields.set(MvccRawStoreFormat.DIRECTORY_ROW_ID);
        }
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD);
        fields.set(MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID);
        return new FetchDescriptor(fieldCount, fields, null);
    }

    private static FetchDescriptor currentIdentityDescriptor(MvccRawStoreTable.Descriptor table) {
        int fieldCount = MvccRawStoreCurrentRows.currentFieldCount(table);
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        fields.set(MvccRawStoreFormat.DIRECTORY_ROW_ID);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        return new FetchDescriptor(fieldCount, fields, null);
    }

    private boolean decodeHistory(long rowId, RecordHandle currentHandle)
            throws StandardException {
        if (!table.gen2History()) {
            return false;
        }
        MvccRawStoreTable.DirectoryHead previous = MvccRawStoreCurrentRows.previousHead(currentRow);
        if (previous == null) {
            return false;
        }
        MvccRawStoreTable.VersionRecord history = MvccRawStoreVersionReader.findVisible(
                transaction,
                table,
                rowId,
                previous,
                transactionId,
                snapshotSequence,
                projection);
        if (history == null || history.tombstone()) {
            return false;
        }
        setVisible(
                rowId,
                history.versionId(),
                history.handle(),
                currentHandle,
                history.values());
        return true;
    }

    private boolean validCurrentRow() throws StandardException {
        return skipCurrentTagFetch || MvccRawStoreCurrentRows.validCurrentRow(currentRow);
    }

    private void refreshCurrentValues() {
        for (int index = 0; index < currentValues.length; index++) {
            currentValues[index] = (StoreDataValue) currentRow[payloadStart + index];
        }
    }

    private void setVisibleCurrent(Page page, int slot, RecordHandle currentHandle)
            throws StandardException {
        visibleVersionHandle = currentHandle;
        visibleDirectoryHandle = currentHandle;
        visibleValues = currentValues;
        if (!lazyCurrentIdentityFetch) {
            visibleRowId = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_ROW_ID);
            visibleVersionId = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
            visibleIdentityLoaded = true;
            visibleCurrentPage = null;
            visibleCurrentSlot = -1;
            return;
        }
        visibleIdentityLoaded = false;
        visibleCurrentPage = page;
        visibleCurrentSlot = slot;
    }

    private void ensureVisibleIdentity() throws StandardException {
        if (visibleIdentityLoaded) {
            return;
        }
        if (visibleCurrentPage == null || visibleCurrentSlot < 0) {
            throw new IllegalStateException("Gen2 streaming CURRENT identity source unavailable");
        }
        visibleCurrentPage.fetchFromSlot(
                visibleVersionHandle, visibleCurrentSlot, currentRow,
                currentIdentityDescriptor, false);
        visibleRowId = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_ROW_ID);
        visibleVersionId = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        visibleIdentityLoaded = true;
        visibleCurrentPage = null;
        visibleCurrentSlot = -1;
    }

    private void ensureVisibleIdentityBeforeUnlatch(Page page) throws StandardException {
        if (!visibleIdentityLoaded && visibleCurrentPage == page) {
            ensureVisibleIdentity();
        }
    }

    private void setVisible(
            long rowId,
            long versionId,
            RecordHandle versionHandle,
            RecordHandle directoryHandle,
            StoreDataValue[] values) {
        visibleRowId = rowId;
        visibleVersionId = versionId;
        visibleVersionHandle = versionHandle;
        visibleDirectoryHandle = directoryHandle;
        visibleValues = values;
        visibleIdentityLoaded = true;
        visibleCurrentPage = null;
        visibleCurrentSlot = -1;
    }

    private void copyLocation(StoreRowLocation[] rowlocArray, int index)
            throws StandardException {
        if (rowlocArray == null) {
            return;
        }
        ensureVisibleIdentity();
        if (rowlocArray[index] == null) {
            rowlocArray[index] = new MvccRowLocation();
        }
        MvccRowLocation location = MvccRowLocation.from(rowlocArray[index]);
        location.set(
                visibleRowId,
                visibleDirectoryHandle.getPageNumber(),
                visibleDirectoryHandle.getSlotNumberHint());
        location.setWriteVersion(visibleVersionId);
    }

    private Batch result(
            int count, long visited, long qualified, boolean done)
            throws StandardException {
        if (count != 0) {
            ensureVisibleIdentity();
        }
        return new Batch(
                count,
                visited,
                qualified,
                done,
                count == 0 ? 0L : visibleRowId,
                count == 0 ? 0L : visibleVersionId,
                count == 0 ? null : visibleVersionHandle,
                count == 0 ? null : visibleDirectoryHandle);
    }

    private Batch emptyResult() {
        return new Batch(0, 0L, 0L, exhausted, 0L, 0L, null, null);
    }

    private static int startSlot(Page page) {
        return page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                ? Page.FIRST_SLOT_NUMBER + 2
                : Page.FIRST_SLOT_NUMBER;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (container != null) {
                container.close();
            }
        }
    }
}
