/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndex

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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RecordHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Version-aware ordered-index entries stored in an ordinary RawStore container.
 *
 * <p>The index is a row-id narrowing structure, not a second row authority.
 * Every candidate is resolved and qualified again through the authoritative
 * MVCC version chain. Index entries retain begin/end sequences so historical
 * snapshots and transaction-local writes use the same visibility rule as the
 * corresponding base version.</p>
 */
final class MvccRawStoreOrderedIndex {
    private static final int OVERFLOW_THRESHOLD = 100;

    /**
     * Use Derby's canonical long-row insertion policy. A populated page must
     * reject a row which does not fit so the caller can advance to another
     * normal page. Overflow is permitted only on an empty page, where RawStore
     * can root the complete long-row chain without mixing it with existing
     * control or data records.
     */
    private static byte insertFlags(Page page) throws StandardException {
        return (byte) (Page.INSERT_UNDO_WITH_PURGE
                | (page.recordCount() == 0
                        ? Page.INSERT_OVERFLOW
                        : Page.INSERT_DEFAULT));
    }

    private MvccRawStoreOrderedIndex() {
    }

    static void initialize(Transaction transaction, MvccRawStoreTable.Descriptor table)
            throws StandardException {
        initialize(transaction, table, requireContainer(table));
    }

    static void initialize(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC ordered-index container is absent: " + key);
        }
        Page page = null;
        try {
            page = container.getFirstPage();
            page.insertAtSlot(
                    Page.FIRST_SLOT_NUMBER,
                    controlRow(transaction, table),
                    null,
                    null,
                    insertFlags(page),
                    OVERFLOW_THRESHOLD);
            container.setEstimatedRowCount(0L, 0);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    static ContainerKey createPrivateGeneration(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        int temporaryFlag = table.temporary()
                ? TransactionController.IS_TEMPORARY
                : TransactionController.IS_DEFAULT;
        long containerId = transaction.addContainer(
                table.metadataContainer().getSegmentId(),
                0L,
                ContainerHandle.MODE_DEFAULT,
                null,
                temporaryFlag);
        if (containerId < 0L) {
            throw StandardException.newException(SQLState.HEAP_CANT_CREATE_CONTAINER);
        }
        ContainerKey target = new ContainerKey(
                table.metadataContainer().getSegmentId(),
                containerId);
        initialize(transaction, table, target);
        return target;
    }

    static boolean containerExists(Transaction transaction, ContainerKey key)
            throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            return false;
        }
        container.close();
        return true;
    }

    static void insertVersion(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            StoreDataValue[] values) throws StandardException {
        if (values == null) {
            return;
        }
        List<IndexEntry> entries = readEntriesForUpdate(transaction, table, key);
        addVersionEntries(
                entries,
                table,
                rowId,
                versionId,
                creatorTransactionId,
                beginSequence,
                endSequence,
                values);
        rewriteSorted(transaction, table, key, entries);
    }

    static void rebuild(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key,
            List<VersionInput> versions) throws StandardException {
        List<IndexEntry> entries = new ArrayList<>();
        for (VersionInput version : versions) {
            if (version.values() == null) {
                continue;
            }
            addVersionEntries(
                    entries,
                    table,
                    version.rowId(),
                    version.versionId(),
                    version.creatorTransactionId(),
                    version.beginSequence(),
                    version.endSequence(),
                    version.values());
        }
        rewriteSorted(transaction, table, key, entries);
    }

    static void stampVersionBegin(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long commitSequence,
            int expectedEntryCount) throws StandardException {
        int updated = updateSequence(
                transaction,
                table,
                rowId,
                versionId,
                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_BEGIN_SEQUENCE,
                MvccRawStoreFormat.UNCOMMITTED_SEQUENCE,
                commitSequence);
        if (updated != expectedEntryCount) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index begin stamping mismatch for row "
                            + rowId + ", version " + versionId
                            + ": expected " + expectedEntryCount + " entries, found " + updated);
        }
    }

    static void stampVersionEnd(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long commitSequence) throws StandardException {
        int updated = updateSequence(
                transaction,
                table,
                rowId,
                versionId,
                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_END_SEQUENCE,
                MvccRawStoreFormat.CURRENT_END_SEQUENCE,
                commitSequence);
        if (updated != table.columnCount()) {
            throw new IllegalStateException(
                    "RawStore MVCC ordered-index end stamping mismatch for row "
                            + rowId + ", version " + versionId
                            + ": expected " + table.columnCount() + " entries, found " + updated);
        }
    }

    static void assertUnique(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] previousValues,
            StoreDataValue[] values,
            long currentRowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            throw new IllegalArgumentException("RawStore MVCC unique-key row width mismatch");
        }

        // The transaction-duration shared schema lock protects constraint
        // metadata. Read the control row without a physical update lock so
        // unrelated constrained writers do not serialize on one record.
        List<MvccRawStoreTable.UniqueConstraint> constraints =
                MvccRawStoreTable.refreshUniqueConstraints(transaction, table, false);
        if (constraints.isEmpty()) {
            return;
        }
        context.lockUniqueKeys(table, constraints, previousValues, values);
        ContainerKey indexKey = context.orderedIndexForWrite(table);
        List<IndexEntry> entries = readEntriesForUpdate(transaction, table, indexKey);
        long committedSequence = context.currentCommittedSequence();

        for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
            int[] columns = constraint.columns();
            if (constraint.duplicateNullsAllowed() && containsNull(values, columns)) {
                continue;
            }
            int firstColumn = columns[0];
            StoreDataValue firstValue = values[firstColumn];
            LinkedHashSet<Long> candidates = new LinkedHashSet<>();
            for (IndexEntry entry : entries) {
                if (entry.columnId() != firstColumn
                        || !visibleAt(entry, context, committedSequence)
                        || StoreTypeUtil.compare(entry.key(), firstValue, true) != 0) {
                    continue;
                }
                candidates.add(entry.rowId());
            }
            for (long candidateRowId : candidates) {
                if (candidateRowId == currentRowId) {
                    continue;
                }
                MvccRawStoreTable.VisibleRow candidate = MvccRawStoreTable.readVisibleAt(
                        transaction,
                        table,
                        candidateRowId,
                        committedSequence,
                        context);
                if (candidate != null && sameKey(values, candidate.values(), columns)) {
                    throw StandardException.newException(
                            SQLState.LANG_DUPLICATE_KEY_CONSTRAINT,
                            constraint.displayName(),
                            "RAWSTORE_MVCC_" + table.metadataContainer().getContainerId());
                }
            }
        }
    }

    static void lockUniqueKeysForDelete(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] previousValues,
            MvccRawStoreTransactionContext context) throws StandardException {
        List<MvccRawStoreTable.UniqueConstraint> constraints =
                MvccRawStoreTable.refreshUniqueConstraints(transaction, table, false);
        if (!constraints.isEmpty()) {
            context.lockUniqueKeys(table, constraints, previousValues);
        }
    }

    static void assertConstraintCanBeAdded(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.UniqueConstraint constraint,
            MvccRawStoreTransactionContext context) throws StandardException {
        long committedSequence = context.currentCommittedSequence();
        List<MvccRawStoreTable.VisibleRow> rows = MvccRawStoreTable.scanVisibleAt(
                transaction,
                table,
                committedSequence,
                context);
        int[] columns = constraint.columns();
        for (int leftIndex = 0; leftIndex < rows.size(); leftIndex++) {
            StoreDataValue[] left = rows.get(leftIndex).values();
            if (constraint.duplicateNullsAllowed() && containsNull(left, columns)) {
                continue;
            }
            for (int rightIndex = leftIndex + 1; rightIndex < rows.size(); rightIndex++) {
                StoreDataValue[] right = rows.get(rightIndex).values();
                if (constraint.duplicateNullsAllowed() && containsNull(right, columns)) {
                    continue;
                }
                if (sameKey(left, right, columns)) {
                    throw StandardException.newException(
                            SQLState.LANG_DUPLICATE_KEY_CONSTRAINT,
                            constraint.displayName(),
                            "RAWSTORE_MVCC_" + table.metadataContainer().getContainerId());
                }
            }
        }
    }

    private static boolean containsNull(StoreDataValue[] values, int[] columns)
            throws StandardException {
        for (int column : columns) {
            if (StoreTypeUtil.isNull(values[column])) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameKey(
            StoreDataValue[] left,
            StoreDataValue[] right,
            int[] columns) throws StandardException {
        for (int column : columns) {
            if (StoreTypeUtil.compare(left[column], right[column], true) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return an answered candidate set, or {@link Optional#empty()} when the
     * qualifiers cannot be represented by the single-column ordered index.
     */
    static Optional<List<Long>> rowIdsFor(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key,
            Qualifier[][] qualifiers,
            MvccRawStoreTransactionContext context) throws StandardException {
        return rowIdsForAt(
                transaction,
                table,
                key,
                qualifiers,
                context.snapshotSequence(),
                context);
    }

    static Optional<List<Long>> rowIdsForAt(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key,
            Qualifier[][] qualifiers,
            long snapshotSequence,
            MvccRawStoreTransactionContext context) throws StandardException {
        Optional<IndexPredicate> predicate = IndexPredicate.from(qualifiers);
        if (predicate.isEmpty()) {
            return Optional.empty();
        }

        if (key == null) {
            return Optional.empty();
        }
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            // READ_UNCOMMITTED control-row discovery can transiently observe a
            // writer's not-yet-published private generation. The logical row
            // directory remains authoritative, so an unavailable generation
            // means "fall back to the base version chain", never query failure.
            return Optional.empty();
        }

        LinkedHashSet<Long> distinctRowIds = new LinkedHashSet<>();
        boolean finished = false;
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null && !finished) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    IndexEntry entry = decodeEntry(transaction, table, page, slot);
                    if (entry == null) {
                        continue;
                    }
                    ScanDecision decision = predicate.get().decide(entry.columnId(), entry.key());
                    if (decision == ScanDecision.FINISH) {
                        finished = true;
                        break;
                    }
                    if (decision == ScanDecision.MATCH
                            && visibleAt(entry, context, snapshotSequence)) {
                        distinctRowIds.add(entry.rowId());
                    }
                }
                if (!finished) {
                    long pageNumber = page.getPageNumber();
                    page.unlatch();
                    page = container.getNextPage(pageNumber);
                }
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        return Optional.of(List.copyOf(distinctRowIds));
    }

    private static void addVersionEntries(
            List<IndexEntry> entries,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            StoreDataValue[] values) throws StandardException {
        if (values.length != table.columnCount()) {
            throw new IllegalArgumentException("RawStore MVCC ordered-index value count mismatch");
        }
        for (int column = 0; column < table.columnCount(); column++) {
            entries.add(new IndexEntry(
                    column,
                    StoreValueCopySupport.cloneValue(values[column]),
                    rowId,
                    versionId,
                    creatorTransactionId,
                    beginSequence,
                    endSequence));
        }
    }

    private static List<IndexEntry> readEntriesForUpdate(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key) throws StandardException {
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC ordered-index container is absent: " + key);
        }
        try {
            return readEntries(transaction, table, container);
        } finally {
            container.close();
        }
    }

    private static List<IndexEntry> readEntries(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerHandle container) throws StandardException {
        List<IndexEntry> entries = new ArrayList<>();
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    IndexEntry entry = decodeEntry(transaction, table, page, slot);
                    if (entry != null) {
                        entries.add(entry);
                    }
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
        return entries;
    }

    private static void rewriteSorted(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerKey key,
            List<IndexEntry> entries) throws StandardException {
        sortEntries(entries);
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC ordered-index container is absent: " + key);
        }
        try {
            purgeEntries(transaction, table, container);
            appendEntries(transaction, table, container, entries);
            container.setEstimatedRowCount(entries.size(), 0);
        } finally {
            container.close();
        }
    }

    private static void purgeEntries(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerHandle container) throws StandardException {
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                int count = page.recordCount() - startSlot;
                if (count > 0) {
                    page.purgeAtSlot(startSlot, count, true);
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static void appendEntries(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            ContainerHandle container,
            List<IndexEntry> entries) throws StandardException {
        Page page = container.getFirstPage();
        try {
            validateControl(transaction, table, page);
            for (IndexEntry entry : entries) {
                Object[] row = entryRow(transaction, table, entry);
                while (true) {
                    RecordHandle handle = page.insertAtSlot(
                            page.recordCount(),
                            row,
                            null,
                            null,
                            insertFlags(page),
                            OVERFLOW_THRESHOLD);
                    if (handle != null) {
                        break;
                    }
                    long pageNumber = page.getPageNumber();
                    page.unlatch();
                    page = container.getNextPage(pageNumber);
                    if (page == null) {
                        page = container.addPage();
                    }
                }
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
        }
    }

    private static void sortEntries(List<IndexEntry> entries) throws StandardException {
        try {
            entries.sort((left, right) -> {
                try {
                    int columnComparison = Integer.compare(left.columnId(), right.columnId());
                    if (columnComparison != 0) {
                        return columnComparison;
                    }
                    int keyComparison = StoreTypeUtil.compare(left.key(), right.key(), true);
                    if (keyComparison != 0) {
                        return keyComparison;
                    }
                    int rowComparison = Long.compare(left.rowId(), right.rowId());
                    return rowComparison != 0
                            ? rowComparison
                            : Long.compare(left.versionId(), right.versionId());
                } catch (StandardException failure) {
                    throw new OrderedIndexComparisonFailure(failure);
                }
            });
        } catch (OrderedIndexComparisonFailure failure) {
            throw (StandardException) failure.getCause();
        }
    }

    private static int updateSequence(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            int field,
            long expectedCurrentValue,
            long newValue) throws StandardException {
        ContainerKey key = requireContainer(table);
        ContainerHandle container = transaction.openContainer(
                key,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_FORUPDATE);
        if (container == null) {
            throw new IllegalStateException("RawStore MVCC ordered-index container is absent: " + key);
        }
        int updated = 0;
        Page page = null;
        try {
            page = container.getFirstPage();
            validateControl(transaction, table, page);
            while (page != null) {
                int startSlot = page.getPageNumber() == ContainerHandle.FIRST_PAGE_NUMBER
                        ? Page.FIRST_SLOT_NUMBER + 1
                        : Page.FIRST_SLOT_NUMBER;
                for (int slot = startSlot; slot < page.recordCount(); slot++) {
                    if (page.isDeletedAtSlot(slot)) {
                        continue;
                    }
                    EntryIdentity identity = decodeIdentity(transaction, page, slot);
                    if (identity == null
                            || identity.rowId() != rowId
                            || identity.versionId() != versionId) {
                        continue;
                    }
                    long current = longField(transaction, page, slot, field);
                    if (current != expectedCurrentValue) {
                        throw new IllegalStateException(
                                "RawStore MVCC ordered-index entry has unexpected sequence for row "
                                        + rowId + ", version " + versionId + ": " + current);
                    }
                    page.updateFieldAtSlot(
                            slot,
                            field,
                            MvccRawStoreFormat.longValue(transaction, newValue),
                            null);
                    updated++;
                }
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
        return updated;
    }

    private static Object[] controlRow(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        return new Object[] {
                MvccRawStoreFormat.intValue(
                        transaction,
                        MvccRawStoreFormat.ORDERED_INDEX_CONTAINER_KIND),
                MvccRawStoreFormat.intValue(transaction, MvccRawStoreFormat.FORMAT_VERSION),
                MvccRawStoreFormat.longValue(
                        transaction,
                        table.metadataContainer().getContainerId()),
                MvccRawStoreFormat.intValue(transaction, table.columnCount())
        };
    }

    private static Object[] entryRow(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            IndexEntry entry) throws StandardException {
        return entryRow(
                transaction,
                table,
                entry.columnId(),
                entry.key(),
                entry.rowId(),
                entry.versionId(),
                entry.creatorTransactionId(),
                entry.beginSequence(),
                entry.endSequence());
    }

    private static Object[] entryRow(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            int columnId,
            StoreDataValue key,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence) throws StandardException {
        Object[] row = entryTemplate(transaction, table, columnId);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KIND_FIELD] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KIND);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(
                transaction,
                MvccRawStoreFormat.FORMAT_VERSION);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_COLUMN_ID] = MvccRawStoreFormat.intValue(
                transaction,
                columnId);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KEY] = StoreValueCopySupport.cloneValue(key);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_ROW_ID] = MvccRawStoreFormat.longValue(
                transaction,
                rowId);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_VERSION_ID] = MvccRawStoreFormat.longValue(
                transaction,
                versionId);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_CREATOR_TRANSACTION_ID] =
                MvccRawStoreFormat.longValue(transaction, creatorTransactionId);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_BEGIN_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                beginSequence);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_END_SEQUENCE] = MvccRawStoreFormat.longValue(
                transaction,
                endSequence);
        return row;
    }

    private static Object[] entryTemplate(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            int columnId) throws StandardException {
        Object[] row = new Object[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_FIELD_COUNT];
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KIND_FIELD] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_FORMAT_VERSION] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_COLUMN_ID] = MvccRawStoreFormat.intValue(transaction, 0);
        row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KEY] = MvccRawStoreFormat.nullValue(
                transaction,
                table.formatIds()[columnId],
                table.collationIds()[columnId]);
        for (int field = MvccRawStoreFormat.ORDERED_INDEX_ENTRY_ROW_ID;
                field <= MvccRawStoreFormat.ORDERED_INDEX_ENTRY_END_SEQUENCE;
                field++) {
            row[field] = MvccRawStoreFormat.longValue(transaction, 0L);
        }
        return row;
    }

    private static IndexEntry decodeEntry(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page page,
            int slot) throws StandardException {
        EntryIdentity identity = decodeIdentity(transaction, page, slot);
        if (identity == null) {
            return null;
        }
        int columnId = identity.columnId();
        if (columnId < 0 || columnId >= table.columnCount()) {
            throw new IllegalStateException("RawStore MVCC ordered-index column is invalid: " + columnId);
        }
        if (page.fetchNumFieldsAtSlot(slot) != MvccRawStoreFormat.ORDERED_INDEX_ENTRY_FIELD_COUNT) {
            throw new IllegalStateException("RawStore MVCC ordered-index entry has an invalid field count");
        }
        Object[] row = entryTemplate(transaction, table, columnId);
        page.fetchFromSlot(null, slot, row, null, false);
        return new IndexEntry(
                columnId,
                StoreValueCopySupport.cloneValue(
                        (StoreDataValue) row[MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KEY]),
                identity.rowId(),
                identity.versionId(),
                MvccRawStoreFormat.longAt(
                        row,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_CREATOR_TRANSACTION_ID),
                MvccRawStoreFormat.longAt(
                        row,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_BEGIN_SEQUENCE),
                MvccRawStoreFormat.longAt(
                        row,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_END_SEQUENCE));
    }

    private static EntryIdentity decodeIdentity(Transaction transaction, Page page, int slot)
            throws StandardException {
        int kind = intField(
                transaction,
                page,
                slot,
                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KIND_FIELD);
        if (kind != MvccRawStoreFormat.ORDERED_INDEX_ENTRY_KIND) {
            return null;
        }
        int formatVersion = intField(
                transaction,
                page,
                slot,
                MvccRawStoreFormat.ORDERED_INDEX_ENTRY_FORMAT_VERSION);
        if (formatVersion != MvccRawStoreFormat.FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported RawStore MVCC ordered-index entry format");
        }
        return new EntryIdentity(
                intField(
                        transaction,
                        page,
                        slot,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_COLUMN_ID),
                longField(
                        transaction,
                        page,
                        slot,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_ROW_ID),
                longField(
                        transaction,
                        page,
                        slot,
                        MvccRawStoreFormat.ORDERED_INDEX_ENTRY_VERSION_ID));
    }

    private static int intField(Transaction transaction, Page page, int slot, int field)
            throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.intValue(transaction, 0);
        page.fetchFieldFromSlot(slot, field, value);
        return Math.toIntExact(StoreTypeUtil.getLong(value));
    }

    private static long longField(Transaction transaction, Page page, int slot, int field)
            throws StandardException {
        StoreDataValue value = MvccRawStoreFormat.longValue(transaction, 0L);
        page.fetchFieldFromSlot(slot, field, value);
        return StoreTypeUtil.getLong(value);
    }

    private static void validateControl(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            Page firstPage) throws StandardException {
        if (firstPage == null || firstPage.recordCount() == 0) {
            throw new IllegalStateException("RawStore MVCC ordered-index control row is absent");
        }
        Object[] control = new Object[] {
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.intValue(transaction, 0),
                MvccRawStoreFormat.longValue(transaction, 0L),
                MvccRawStoreFormat.intValue(transaction, 0)
        };
        firstPage.fetchFromSlot(null, Page.FIRST_SLOT_NUMBER, control, null, false);
        if (MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_KIND_FIELD)
                != MvccRawStoreFormat.ORDERED_INDEX_CONTAINER_KIND
                || MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_FORMAT_VERSION)
                != MvccRawStoreFormat.FORMAT_VERSION
                || MvccRawStoreFormat.longAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_METADATA_CONTAINER)
                != table.metadataContainer().getContainerId()
                || MvccRawStoreFormat.intAt(
                    control,
                    MvccRawStoreFormat.ORDERED_INDEX_CONTROL_COLUMN_COUNT)
                != table.columnCount()) {
            throw new IllegalStateException("RawStore MVCC ordered-index control row is inconsistent");
        }
    }

    private static ContainerKey requireContainer(MvccRawStoreTable.Descriptor table) {
        ContainerKey key = table.orderedIndexContainer();
        if (key == null) {
            throw new IllegalStateException("RawStore MVCC ordered index is not installed");
        }
        return key;
    }

    private static boolean visibleAt(
            IndexEntry entry,
            MvccRawStoreTransactionContext context,
            long snapshotSequence) {
        if (entry.creatorTransactionId() != context.transactionId()
                && context.isTransactionActive(entry.creatorTransactionId())) {
            return false;
        }
        if (entry.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE) {
            return entry.creatorTransactionId() == context.transactionId();
        }
        return entry.beginSequence() <= snapshotSequence
                && snapshotSequence < entry.endSequence();
    }

    private sealed interface IndexPredicate permits EqualityPredicate, RangePredicate {
        ScanDecision decide(int columnId, StoreDataValue key) throws StandardException;

        static Optional<IndexPredicate> from(Qualifier[][] qualifiers) throws StandardException {
            Optional<IndexPredicate> equality = equality(qualifiers);
            return equality.isPresent() ? equality : range(qualifiers);
        }

        private static Optional<IndexPredicate> equality(Qualifier[][] qualifiers)
                throws StandardException {
            if (qualifiers == null || qualifiers.length == 0) {
                return Optional.empty();
            }
            for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
                Qualifier[] andTerm = qualifiers[andTermIndex];
                if (andTerm == null || andTerm.length == 0) {
                    continue;
                }
                if (andTermIndex > 0 && andTerm.length != 1) {
                    return Optional.empty();
                }
                for (Qualifier qualifier : andTerm) {
                    if (qualifier == null
                            || qualifier.getColumnId() < 0
                            || qualifier.getOperator() != StoreOrderable.ORDER_OP_EQUALS
                            || qualifier.negateCompareResult()) {
                        continue;
                    }
                    StoreDataValue orderable = qualifier.getOrderable();
                    if (orderable == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new EqualityPredicate(
                            qualifier.getColumnId(),
                            StoreValueCopySupport.cloneValue(orderable)));
                }
            }
            return Optional.empty();
        }

        private static Optional<IndexPredicate> range(Qualifier[][] qualifiers)
                throws StandardException {
            if (qualifiers == null || qualifiers.length == 0) {
                return Optional.empty();
            }
            int column = -1;
            StoreDataValue lower = null;
            boolean lowerInclusive = true;
            StoreDataValue upper = null;
            boolean upperInclusive = true;
            boolean sawBound = false;

            for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
                Qualifier[] andTerm = qualifiers[andTermIndex];
                if (andTerm == null || andTerm.length == 0) {
                    return Optional.empty();
                }
                if (andTermIndex > 0 && andTerm.length != 1) {
                    return Optional.empty();
                }
                for (Qualifier qualifier : andTerm) {
                    if (qualifier == null || qualifier.getColumnId() < 0) {
                        return Optional.empty();
                    }
                    int operator = normalizedRangeOperator(
                            qualifier.getOperator(),
                            qualifier.negateCompareResult());
                    if (operator == Integer.MIN_VALUE) {
                        return Optional.empty();
                    }
                    if (column == -1) {
                        column = qualifier.getColumnId();
                    } else if (column != qualifier.getColumnId()) {
                        return Optional.empty();
                    }
                    StoreDataValue orderable = qualifier.getOrderable();
                    if (orderable == null) {
                        return Optional.empty();
                    }
                    StoreDataValue bound = StoreValueCopySupport.cloneValue(orderable);
                    switch (operator) {
                        case StoreOrderable.ORDER_OP_GREATERTHAN -> {
                            BoundChoice choice = chooseLower(lower, lowerInclusive, bound, false);
                            lower = choice.value();
                            lowerInclusive = choice.inclusive();
                            sawBound = true;
                        }
                        case StoreOrderable.ORDER_OP_GREATEROREQUALS -> {
                            BoundChoice choice = chooseLower(lower, lowerInclusive, bound, true);
                            lower = choice.value();
                            lowerInclusive = choice.inclusive();
                            sawBound = true;
                        }
                        case StoreOrderable.ORDER_OP_LESSTHAN -> {
                            BoundChoice choice = chooseUpper(upper, upperInclusive, bound, false);
                            upper = choice.value();
                            upperInclusive = choice.inclusive();
                            sawBound = true;
                        }
                        case StoreOrderable.ORDER_OP_LESSOREQUALS -> {
                            BoundChoice choice = chooseUpper(upper, upperInclusive, bound, true);
                            upper = choice.value();
                            upperInclusive = choice.inclusive();
                            sawBound = true;
                        }
                        default -> {
                            return Optional.empty();
                        }
                    }
                }
            }
            return !sawBound || column < 0
                    ? Optional.empty()
                    : Optional.of(new RangePredicate(
                            column,
                            lower,
                            lowerInclusive,
                            upper,
                            upperInclusive));
        }

        private static int normalizedRangeOperator(int operator, boolean negated) {
            if (!negated) {
                return operator;
            }
            return switch (operator) {
                case StoreOrderable.ORDER_OP_LESSTHAN -> StoreOrderable.ORDER_OP_GREATEROREQUALS;
                case StoreOrderable.ORDER_OP_LESSOREQUALS -> StoreOrderable.ORDER_OP_GREATERTHAN;
                case StoreOrderable.ORDER_OP_GREATERTHAN -> StoreOrderable.ORDER_OP_LESSOREQUALS;
                case StoreOrderable.ORDER_OP_GREATEROREQUALS -> StoreOrderable.ORDER_OP_LESSTHAN;
                default -> Integer.MIN_VALUE;
            };
        }

        private static BoundChoice chooseLower(
                StoreDataValue current,
                boolean currentInclusive,
                StoreDataValue candidate,
                boolean candidateInclusive) throws StandardException {
            if (current == null) {
                return new BoundChoice(candidate, candidateInclusive);
            }
            int comparison = StoreTypeUtil.compare(candidate, current, true);
            if (comparison > 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
                return new BoundChoice(candidate, candidateInclusive);
            }
            return new BoundChoice(current, currentInclusive);
        }

        private static BoundChoice chooseUpper(
                StoreDataValue current,
                boolean currentInclusive,
                StoreDataValue candidate,
                boolean candidateInclusive) throws StandardException {
            if (current == null) {
                return new BoundChoice(candidate, candidateInclusive);
            }
            int comparison = StoreTypeUtil.compare(candidate, current, true);
            if (comparison < 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
                return new BoundChoice(candidate, candidateInclusive);
            }
            return new BoundChoice(current, currentInclusive);
        }
    }

    private record EqualityPredicate(int columnId, StoreDataValue value) implements IndexPredicate {
        @Override
        public ScanDecision decide(int candidateColumnId, StoreDataValue key) throws StandardException {
            if (candidateColumnId < columnId) {
                return ScanDecision.SKIP;
            }
            if (candidateColumnId > columnId) {
                return ScanDecision.FINISH;
            }
            int comparison = StoreTypeUtil.compare(key, value, true);
            if (comparison < 0) {
                return ScanDecision.SKIP;
            }
            return comparison == 0 ? ScanDecision.MATCH : ScanDecision.FINISH;
        }
    }

    private record RangePredicate(
            int columnId,
            StoreDataValue lower,
            boolean lowerInclusive,
            StoreDataValue upper,
            boolean upperInclusive) implements IndexPredicate {
        @Override
        public ScanDecision decide(int candidateColumnId, StoreDataValue key) throws StandardException {
            if (candidateColumnId < columnId) {
                return ScanDecision.SKIP;
            }
            if (candidateColumnId > columnId) {
                return ScanDecision.FINISH;
            }
            if (lower != null) {
                int comparison = StoreTypeUtil.compare(key, lower, true);
                if (comparison < 0 || (comparison == 0 && !lowerInclusive)) {
                    return ScanDecision.SKIP;
                }
            }
            if (upper != null) {
                int comparison = StoreTypeUtil.compare(key, upper, true);
                if (comparison > 0 || (comparison == 0 && !upperInclusive)) {
                    return ScanDecision.FINISH;
                }
            }
            return ScanDecision.MATCH;
        }
    }

    private record BoundChoice(StoreDataValue value, boolean inclusive) {
    }

    private record EntryIdentity(int columnId, long rowId, long versionId) {
    }

    private record IndexEntry(
            int columnId,
            StoreDataValue key,
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence) {
    }

    record VersionInput(
            long rowId,
            long versionId,
            long creatorTransactionId,
            long beginSequence,
            long endSequence,
            StoreDataValue[] values) {
        VersionInput {
            values = values == null ? null : values.clone();
        }
    }

    private enum ScanDecision {
        SKIP,
        MATCH,
        FINISH
    }

    private static final class OrderedIndexComparisonFailure extends RuntimeException {
        OrderedIndexComparisonFailure(StandardException cause) {
            super(cause);
        }
    }
}
