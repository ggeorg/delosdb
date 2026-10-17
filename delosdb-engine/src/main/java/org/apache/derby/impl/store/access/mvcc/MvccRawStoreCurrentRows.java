/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreCurrentRows

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.FetchDescriptor;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * The Gen2 CURRENT row: one directory slot that carries the row's head
 * metadata and its column values inline, so a read that sees the newest
 * version needs no version-container lookup. When the newest version is not
 * visible to a snapshot, a Gen2-C1 table follows the row's previous-version
 * chain in the version container. This class owns the slot layout, its
 * encoding and decoding, and the readers that turn CURRENT rows into visible
 * rows; MvccRawStoreStreamingScan streams them for bulk scans.
 */
final class MvccRawStoreCurrentRows {
    private MvccRawStoreCurrentRows() {
    }

    record CurrentRow(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long previousVersionId,
            MvccRawStoreTable.RecordHint previousHint,
            StoreDataValue[] values,
            RecordHandle handle) {
        MvccRawStoreTable.DirectoryHead previousHead() {
            return previousVersionId == MvccRawStoreFormat.NO_PREVIOUS_VERSION
                    ? null
                    : new MvccRawStoreTable.DirectoryHead(previousVersionId, previousHint);
        }
    }

    static int currentFieldCount(MvccRawStoreTable.Descriptor table) {
        return table.gen2History()
                ? MvccRawStoreFormat.gen2C1CurrentFieldCount(table.columnCount())
                : MvccRawStoreFormat.gen2A1CurrentFieldCount(table.columnCount());
    }

    static int currentPayloadStart(MvccRawStoreTable.Descriptor table) {
        return table.gen2History()
                ? MvccRawStoreFormat.GEN2_C1_CURRENT_PAYLOAD_START
                : MvccRawStoreFormat.GEN2_A1_CURRENT_PAYLOAD_START;
    }

    // The head of the history chain recorded in a Gen2-C1 CURRENT row that
    // was fetched with the history fields, or null when the row has none.
    static MvccRawStoreTable.DirectoryHead previousHead(Object[] currentRow)
            throws StandardException {
        long previousVersionId = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID);
        if (previousVersionId == MvccRawStoreFormat.NO_PREVIOUS_VERSION) {
            return null;
        }
        return new MvccRawStoreTable.DirectoryHead(
                previousVersionId,
                new MvccRawStoreTable.RecordHint(
                        MvccRawStoreFormat.longAt(
                                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE),
                        MvccRawStoreFormat.intAt(
                                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD)));
    }

    // A history version stands in for the CURRENT row it belongs to: the row
    // location still names the directory slot.
    private static MvccRawStoreTable.VisibleRow historyRow(
            long rowId,
            MvccRawStoreTable.VersionRecord history,
            RecordHandle directoryHandle) {
        if (history == null || history.tombstone()) {
            return null;
        }
        return new MvccRawStoreTable.VisibleRow(
                rowId,
                history.versionId(),
                history.values(),
                history.handle(),
                MvccRawStoreRowDirectory.location(rowId, directoryHandle));
    }

    private static FetchDescriptor currentPayloadDescriptor(
            MvccRawStoreTable.Descriptor table, MvccRawStoreVersionRows.FetchProjection projection) {
        int fieldCount = currentFieldCount(table);
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        fields.set(MvccRawStoreFormat.DIRECTORY_KIND_FIELD);
        fields.set(MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION);
        fields.set(MvccRawStoreFormat.DIRECTORY_ROW_ID);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS);
        int payloadStart = currentPayloadStart(table);
        for (int column = 0; column < table.columnCount(); column++) {
            if (projection == null || projection.includes(column)) {
                fields.set(payloadStart + column);
            }
        }
        return new FetchDescriptor(fieldCount, fields, null);
    }

    private static FetchDescriptor currentHistoryDescriptor(MvccRawStoreTable.Descriptor table) {
        int fieldCount = currentFieldCount(table);
        FormatableBitSet fields = new FormatableBitSet(fieldCount);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE);
        fields.set(MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD);
        fields.set(MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID);
        return new FetchDescriptor(fieldCount, fields, null);
    }

    static boolean validCurrentRow(Object[] row) throws StandardException {
        return MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_KIND_FIELD)
                        == MvccRawStoreFormat.DIRECTORY_KIND
                && MvccRawStoreFormat.intAt(
                        row, MvccRawStoreFormat.DIRECTORY_FORMAT_VERSION)
                        == MvccRawStoreFormat.FORMAT_VERSION;
    }

    static long currentCreator(
            Page page, int slot, Object[] currentRow, long beginSequence)
            throws StandardException {
        if (beginSequence != MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return 0L;
        }
        page.fetchFieldFromSlot(
                slot,
                MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID,
                (StoreDataValue) currentRow[
                        MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID]);
        return MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID);
    }

    private static StoreDataValue[] currentValues(
            MvccRawStoreTable.Descriptor table,
            Object[] currentRow,
            int payloadStart,
            MvccRawStoreVersionRows.FetchProjection projection)
            throws StandardException {
        if (projection != null && !projection.includesPayload()) {
            return null;
        }
        StoreDataValue[] values = new StoreDataValue[table.columnCount()];
        for (int index = 0; index < values.length; index++) {
            if ((projection == null || projection.includes(index))
                    && currentRow[payloadStart + index] != null) {
                values[index] = StoreValueCopySupport.cloneValue(
                        (StoreDataValue) currentRow[payloadStart + index], true);
            }
        }
        return values;
    }

    private static MvccRawStoreTable.VisibleRow currentVisibleRow(
            MvccRawStoreTable.Descriptor table,
            Object[] currentRow,
            int payloadStart,
            MvccRawStoreVersionRows.FetchProjection projection,
            long rowId,
            RecordHandle currentHandle) throws StandardException {
        int flags = MvccRawStoreFormat.intAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS);
        if ((flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
            return null;
        }
        long versionId = MvccRawStoreFormat.longAt(
                currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID);
        return new MvccRawStoreTable.VisibleRow(
                rowId,
                versionId,
                currentValues(table, currentRow, payloadStart, projection),
                currentHandle,
                MvccRawStoreRowDirectory.location(rowId, currentHandle));
    }

    static final class MaterializedCurrentScanDecoder {
        private final Transaction transaction;
        private final MvccRawStoreTable.Descriptor table;
        private final long snapshotSequence;
        private final MvccRawStoreTransactionContext context;
        private final long transactionId;
        private final MvccRawStoreVersionRows.FetchProjection projection;
        private final MvccRawStoreVersionRows.FetchProjection currentProjection;
        private final Object[] currentRow;
        private final FetchDescriptor currentDescriptor;
        private final FetchDescriptor historyDescriptor;
        private final int payloadStart;

        MaterializedCurrentScanDecoder(
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
            this.currentProjection = table.projectedCurrentRead() ? projection : null;
            this.currentRow = template(transaction, table, currentProjection);
            this.currentDescriptor = currentPayloadDescriptor(table, currentProjection);
            this.historyDescriptor = table.gen2History()
                    ? currentHistoryDescriptor(table)
                    : null;
            this.payloadStart = currentPayloadStart(table);
        }

        MvccRawStoreTable.VisibleRow decode(Page page, int slot) throws StandardException {
            if (page.fetchNumFieldsAtSlot(slot) != currentFieldCount(table)) {
                return null;
            }
            RecordHandle currentHandle = page.fetchFromSlot(
                    null, slot, currentRow, currentDescriptor, false);
            if (!validCurrentRow(currentRow)) {
                return null;
            }
            long rowId = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_ROW_ID);
            long begin = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
            long creator = currentCreator(page, slot, currentRow, begin);
            if (context.currentVisibleTo(creator, begin, snapshotSequence)) {
                return currentVisibleRow(
                        table, currentRow, payloadStart, currentProjection, rowId, currentHandle);
            }
            return historyVisible(page, slot, rowId, currentHandle);
        }

        private MvccRawStoreTable.VisibleRow historyVisible(
                Page page, int slot, long rowId, RecordHandle currentHandle)
                throws StandardException {
            if (historyDescriptor == null) {
                return null;
            }
            page.fetchFromSlot(currentHandle, slot, currentRow, historyDescriptor, false);
            MvccRawStoreTable.DirectoryHead previous = previousHead(currentRow);
            if (previous == null) {
                return null;
            }
            return historyRow(
                    rowId,
                    MvccRawStoreVersionReader.findVisible(
                            transaction,
                            table,
                            rowId,
                            previous,
                            transactionId,
                            snapshotSequence,
                            projection),
                    currentHandle);
        }
    }

    static final class CurrentBaseFetchDecoder {
        private final Transaction transaction;
        private final MvccRawStoreTable.Descriptor table;
        private final MvccRawStoreVersionRows.FetchProjection projection;
        private final Object[] currentRow;
        private final FetchDescriptor currentDescriptor;
        private final FetchDescriptor historyDescriptor;
        private final int payloadStart;

        CurrentBaseFetchDecoder(
                Transaction transaction,
                MvccRawStoreTable.Descriptor table,
                MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
            this.transaction = transaction;
            this.table = table;
            this.projection = projection;
            this.currentRow = template(transaction, table, projection);
            this.currentDescriptor = currentPayloadDescriptor(table, projection);
            this.historyDescriptor = table.gen2History()
                    ? currentHistoryDescriptor(table)
                    : null;
            this.payloadStart = currentPayloadStart(table);
        }

        MvccRawStoreTable.VisibleRow readVisibleAt(
                MvccRowLocation rowLocation,
                long snapshotSequence,
                MvccRawStoreTransactionContext context,
                ContainerHandle container,
                MvccRawStoreVersionReader versionReader) throws StandardException {
            if (container == null || rowLocation == null) {
                return null;
            }
            Page page = null;
            try {
                if (rowLocation.hasLocatorHint()) {
                    page = container.getPage(rowLocation.locatorPageId());
                    if (page != null) {
                        LookupResult hinted = decodeCandidate(
                                page,
                                rowLocation.locatorSlotId(),
                                rowLocation.rowId(),
                                snapshotSequence,
                                context,
                                versionReader);
                        if (hinted.matched()) {
                            return hinted.visible();
                        }
                        page.unlatch();
                        page = null;
                    }
                }
                page = container.getFirstPage();
                while (page != null) {
                    int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                            ? Page.FIRST_SLOT_NUMBER + 2
                            : Page.FIRST_SLOT_NUMBER;
                    for (int slot = startSlot; slot < page.recordCount(); slot++) {
                        if (page.isDeletedAtSlot(slot)) {
                            continue;
                        }
                        LookupResult candidate = decodeCandidate(
                                page,
                                slot,
                                rowLocation.rowId(),
                                snapshotSequence,
                                context,
                                versionReader);
                        if (candidate.matched()) {
                            return candidate.visible();
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
            }
        }

        private LookupResult decodeCandidate(
                Page page,
                int slot,
                long expectedRowId,
                long snapshotSequence,
                MvccRawStoreTransactionContext context,
                MvccRawStoreVersionReader versionReader) throws StandardException {
            if (page.fetchNumFieldsAtSlot(slot) != currentFieldCount(table)) {
                return LookupResult.NOT_MATCHED;
            }
            RecordHandle currentHandle = page.fetchFromSlot(
                    null, slot, currentRow, currentDescriptor, false);
            if (!validCurrentRow(currentRow)) {
                return LookupResult.NOT_MATCHED;
            }
            long rowId = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_ROW_ID);
            if (rowId != expectedRowId) {
                return LookupResult.NOT_MATCHED;
            }
            long beginSequence = MvccRawStoreFormat.longAt(
                    currentRow, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE);
            long creatorTransactionId = currentCreator(page, slot, currentRow, beginSequence);
            if (context.currentVisibleTo(
                    creatorTransactionId,
                    beginSequence,
                    snapshotSequence)) {
                return new LookupResult(
                        true,
                        currentVisibleRow(
                                table,
                                currentRow,
                                payloadStart,
                                projection,
                                rowId,
                                currentHandle));
            }
            return new LookupResult(
                    true,
                    historyVisible(
                            page,
                            slot,
                            rowId,
                            currentHandle,
                            snapshotSequence,
                            context.transactionId(),
                            versionReader));
        }

        private MvccRawStoreTable.VisibleRow historyVisible(
                Page page,
                int slot,
                long rowId,
                RecordHandle currentHandle,
                long snapshotSequence,
                long transactionId,
                MvccRawStoreVersionReader versionReader) throws StandardException {
            if (historyDescriptor == null) {
                return null;
            }
            page.fetchFromSlot(currentHandle, slot, currentRow, historyDescriptor, false);
            MvccRawStoreTable.DirectoryHead previous = previousHead(currentRow);
            if (previous == null) {
                return null;
            }
            return historyRow(
                    rowId,
                    versionReader.findVisible(
                            rowId,
                            previous,
                            transactionId,
                            snapshotSequence,
                            projection),
                    currentHandle);
        }

        private record LookupResult(boolean matched, MvccRawStoreTable.VisibleRow visible) {
            private static final LookupResult NOT_MATCHED = new LookupResult(false, null);
        }
    }

    static Object[] encode(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            StoreDataValue[] values) throws StandardException {
        return encode(
                transaction,
                table,
                rowId,
                versionId,
                creatorTransactionId,
                beginSequence,
                flags,
                MvccRawStoreFormat.NO_PREVIOUS_VERSION,
                MvccRawStoreTable.RecordHint.NONE,
                values);
    }

    static Object[] encode(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            int flags,
            long previousVersionId,
            MvccRawStoreTable.RecordHint previousHint,
            StoreDataValue[] values) throws StandardException {
        int fieldCount = currentFieldCount(table);
        int payloadStart = currentPayloadStart(table);
        Object[] row = new Object[fieldCount];
        Object[] head = MvccRawStoreTable.directoryRow(
                transaction,
                rowId,
                versionId,
                table.gen2History() ? previousHint : MvccRawStoreTable.RecordHint.NONE,
                creatorTransactionId,
                beginSequence,
                flags);
        System.arraycopy(head, 0, row, 0, head.length);
        if (table.gen2History()) {
            row[MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID] =
                    MvccRawStoreFormat.longValue(transaction, previousVersionId);
        }
        StoreDataValue[] copy = StoreValueCopySupport.cloneRow(values);
        System.arraycopy(copy, 0, row, payloadStart, copy.length);
        return row;
    }

    static Object[] template(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        int fieldCount = currentFieldCount(table);
        int payloadStart = currentPayloadStart(table);
        Object[] row = new Object[fieldCount];
        Object[] head = MvccRawStoreTable.directoryTemplate(
                transaction, MvccRawStoreFormat.DIRECTORY_HEAD_SUMMARY_FIELD_COUNT);
        System.arraycopy(head, 0, row, 0, head.length);
        if (table.gen2History()) {
            row[MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID] =
                    MvccRawStoreFormat.longValue(transaction, 0L);
        }
        for (int index = 0; index < table.columnCount(); index++) {
            if (projection == null || projection.includes(index)) {
                row[payloadStart + index] =
                        MvccRawStoreFormat.nullValue(
                                transaction, table.formatId(index), table.collationId(index));
            }
        }
        return row;
    }

    static CurrentRow decode(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot) throws StandardException {
        return decode(transaction, table, page, slot, null);
    }

    static CurrentRow decode(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        int expected = currentFieldCount(table);
        if (page.fetchNumFieldsAtSlot(slot) != expected) {
            return null;
        }
        MvccRawStoreVersionRows.FetchProjection currentProjection =
                table.projectedCurrentRead() ? projection : null;
        Object[] row = template(transaction, table, currentProjection);
        FetchDescriptor descriptor = currentProjection == null
                ? null
                : currentProjection.currentDescriptor(table);
        RecordHandle handle = page.fetchFromSlot(null, slot, row, descriptor, false);
        if (!validCurrentRow(row)) {
            return null;
        }
        StoreDataValue[] values = currentValues(
                table, row, currentPayloadStart(table), currentProjection);
        long previousVersionId = table.gen2History()
                ? MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.GEN2_C1_PREVIOUS_VERSION_ID)
                : MvccRawStoreFormat.NO_PREVIOUS_VERSION;
        MvccRawStoreTable.RecordHint previousHint = table.gen2History()
                ? new MvccRawStoreTable.RecordHint(
                        MvccRawStoreFormat.longAt(
                                row, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_PAGE),
                        MvccRawStoreFormat.intAt(
                                row, MvccRawStoreFormat.DIRECTORY_HEAD_HINT_RECORD))
                : MvccRawStoreTable.RecordHint.NONE;
        return new CurrentRow(
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_ROW_ID),
                MvccRawStoreFormat.longAt(row, MvccRawStoreFormat.DIRECTORY_HEAD_VERSION_ID),
                MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.DIRECTORY_HEAD_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(
                        row, MvccRawStoreFormat.DIRECTORY_HEAD_BEGIN_SEQUENCE),
                MvccRawStoreFormat.intAt(row, MvccRawStoreFormat.DIRECTORY_HEAD_FLAGS),
                previousVersionId,
                previousHint,
                values,
                handle);
    }

    static CurrentRow readByHandle(
            Transaction transaction, MvccRawStoreTable.Descriptor table, RecordHandle handle)
            throws StandardException {
        if (handle == null) {
            return null;
        }
        ContainerHandle container = transaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        Page page = null;
        try {
            page = container == null ? null : container.getPage(handle.getPageNumber());
            if (page == null) {
                return null;
            }
            int slot;
            try {
                slot = page.getSlotNumber(handle);
            } catch (StandardException failure) {
                if (SQLState.RAWSTORE_RECORD_VANISHED.equals(failure.getMessageId())) {
                    return null;
                }
                throw failure;
            }
            return decode(transaction, table, page, slot);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            if (container != null) {
                container.close();
            }
        }
    }

    static CurrentRow find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle suppliedContainer) throws StandardException {
        return find(transaction, table, rowLocation, suppliedContainer, null);
    }

    static CurrentRow find(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            ContainerHandle suppliedContainer,
            MvccRawStoreVersionRows.FetchProjection projection) throws StandardException {
        ContainerHandle container = suppliedContainer;
        boolean closeContainer = false;
        if (container == null) {
            container = transaction.openContainer(
                    table.metadataContainer(),
                    MvccRawStorePhysicalLocking.rowLevel(transaction),
                    ContainerHandle.MODE_READONLY);
            closeContainer = true;
        }
        Page page = null;
        try {
            if (container == null || rowLocation == null) {
                return null;
            }
            if (rowLocation.hasLocatorHint()) {
                page = container.getPage(rowLocation.locatorPageId());
                if (page != null) {
                    CurrentRow hinted = decode(
                            transaction, table, page, rowLocation.locatorSlotId(), projection);
                    if (hinted != null && hinted.rowId() == rowLocation.rowId()) {
                        return hinted;
                    }
                    page.unlatch();
                    page = null;
                }
            }
            page = container.getFirstPage();
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 2
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    CurrentRow current = decode(
                            transaction, table, page, slot, projection);
                    if (current != null && current.rowId() == rowLocation.rowId()) {
                        return current;
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
            if (closeContainer && container != null) {
                container.close();
            }
        }
    }

    static MvccRawStoreTable.VisibleRow readVisibleAt(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRowLocation rowLocation,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            MvccRawStoreTransactionContext context,
            ContainerHandle suppliedContainer) throws StandardException {
        CurrentRow current = find(
                transaction, table, rowLocation, suppliedContainer, projection);
        return visible(
                transaction, table, current, projection, snapshotSequence, context);
    }

    static MvccRawStoreTable.VisibleRow readVisibleFromDirectory(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.DirectoryRecord directory,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            MvccRawStoreTransactionContext context,
            ContainerHandle suppliedContainer) throws StandardException {
        if (directory == null || directory.handle() == null) {
            return null;
        }
        MvccRowLocation location = MvccRawStoreRowDirectory.location(
                directory.rowId(), directory.handle());
        return readVisibleAt(
                transaction,
                table,
                location,
                projection,
                snapshotSequence,
                context,
                suppliedContainer);
    }

    private static MvccRawStoreTable.VisibleRow visible(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            CurrentRow current,
            MvccRawStoreVersionRows.FetchProjection projection,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (current == null) {
            return null;
        }
        if (context.currentVisibleTo(
                current.creatorTransactionId(),
                current.beginSequence(),
                snapshotSequence)) {
            if ((current.flags() & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0) {
                return null;
            }
            return new MvccRawStoreTable.VisibleRow(
                    current.rowId(),
                    current.versionId(),
                    current.values(),
                    current.handle(),
                    MvccRawStoreRowDirectory.location(current.rowId(), current.handle()));
        }
        MvccRawStoreTable.DirectoryHead previous = current.previousHead();
        if (!table.gen2History() || previous == null) {
            return null;
        }
        return historyRow(
                current.rowId(),
                MvccRawStoreVersionReader.findVisible(
                        transaction,
                        table,
                        current.rowId(),
                        previous,
                        context.transactionId(),
                        snapshotSequence,
                        projection),
                current.handle());
    }
}
