/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreOrderedIndex

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.services.io.StoredFormatIds;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Version-aware candidate indexes backed by Derby's existing RawStore B-tree.
 *
 * <p>The published ordered-index container is a small generation directory.
 * Its inherited Derby B-trees retain the on-disk per-orderable-column layout,
 * but normal version maintenance populates only first columns used to narrow
 * native unique-key probes. Other predicates fall back to SQL indexes or the
 * authoritative MVCC scan.</p>
 */
final class MvccRawStoreOrderedIndex {
    static final int KEY_FIELD = 0;
    private static final int ROW_ID_FIELD = 1;
    private static final int VERSION_ID_FIELD = 2;
    static final int ROW_LOCATION_FIELD = 3;
    static final int INDEX_FIELD_COUNT = 4;

    private MvccRawStoreOrderedIndex() {
    }


    static void insertVersion(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            long rowId,
            long versionId,
            MvccRowLocation rowLocation,
            StoreDataValue[] previousValues,
            StoreDataValue[] values,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (values == null) {
            return;
        }
        if (values.length != table.columnCount()
                || (previousValues != null && previousValues.length != table.columnCount())) {
            throw new IllegalArgumentException(
                    "RawStore MVCC ordered-index value count mismatch");
        }
        ContainerKey directoryKey = null;
        long[] btrees = null;
        for (int column = 0; column < table.columnCount(); column++) {
            if (!maintainsColumn(table, column)
                    || indexedValueUnchanged(previousValues, values, column)) {
                continue;
            }
            if (btrees == null) {
                directoryKey = context.orderedIndexForWrite(table);
                btrees = MvccRawStoreOrderedIndexGeneration.requireBtreeConglomerates(
                        transactionManager, table, directoryKey);
            }
            if (MvccRawStoreOrderedIndexGeneration.isDisabled(btrees[column])) {
                continue;
            }
            try {
                insertEntry(
                        transactionManager,
                        btrees[column],
                        indexRow(
                                transactionManager.getRawStoreXact(),
                                table,
                                column,
                                values[column],
                                rowId,
                                versionId,
                                rowLocation));
            } catch (StandardException failure) {
                if (!isOversizedBtreeKey(failure)) {
                    throw failure;
                }
                context.beforeSchemaChange(table);
                MvccRawStoreOrderedIndexGeneration.disableBtree(
                        transactionManager,
                        table,
                        directoryKey,
                        btrees,
                        column);
            }
        }
        // Pre-index compatibility tables have no published generation at all.
        // Preserve their first-write transactional upgrade even when the
        // current mutation has no maintained candidate key to insert.
        if (btrees == null && table.orderedIndexContainer() == null) {
            context.orderedIndexForWrite(table);
        }
    }

    static void rebuild(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            List<VersionInput> versions) throws StandardException {
        Transaction transaction = transactionManager.getRawStoreXact();
        long[] oldBtrees = MvccRawStoreOrderedIndexGeneration.readBtreeConglomerates(
                transaction,
                table,
                directoryKey);
        long[] newBtrees = MvccRawStoreOrderedIndexGeneration.createBtrees(
                transactionManager, table);
        try {
            for (VersionInput version : versions) {
                if (version.values() == null) {
                    continue;
                }
                insertVersionIntoBtrees(
                        transactionManager,
                        transaction,
                        table,
                        newBtrees,
                        version);
            }
            MvccRawStoreOrderedIndexGeneration.replaceDirectoryMappings(
                    transaction, table, directoryKey, newBtrees);
            if (oldBtrees != null) {
                MvccRawStoreOrderedIndexGeneration.dropBtrees(
                        transactionManager, oldBtrees);
            }
        } catch (StandardException failure) {
            MvccRawStoreOrderedIndexGeneration.dropBtreesAfterFailure(
                    transactionManager, newBtrees, failure);
            throw failure;
        }
    }

    static void assertUnique(
            Transaction transaction, MvccRawStoreTable.Descriptor table,
            StoreDataValue[] previousValues, StoreDataValue[] values,
            long currentRowId, MvccRawStoreTransactionContext context) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            throw new IllegalArgumentException("RawStore MVCC unique-key row width mismatch");
        }
        List<MvccRawStoreTable.UniqueConstraint> constraints =
                MvccRawStoreTableMetadata.refreshUniqueConstraints(transaction, table, false);
        if (constraints.isEmpty()) {
            return;
        }
        // An unchanged native unique key cannot create a duplicate. Keep the
        // metadata refresh but avoid locking, candidate scans, and version reads.
        if (previousValues != null) {
            boolean uniqueKeyChanged = false;
            for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
                if (!sameKey(previousValues, values, constraint.columns())) {
                    uniqueKeyChanged = true;
                    break;
                }
            }
            if (!uniqueKeyChanged) {
                return;
            }
        }
        context.lockUniqueKeys(table, constraints, previousValues, values);
        ContainerKey directoryKey = context.orderedIndexForWrite(table);
        long committedSequence = context.currentCommittedSequence();

        for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
            int[] columns = constraint.columns();
            if (constraint.duplicateNullsAllowed() && containsNull(values, columns)) {
                continue;
            }
            int firstColumn = columns[0];
            MvccRawStoreVersionRows.FetchProjection projection = constraintProjection(table, columns);
            Optional<List<Candidate>> candidates = candidatesForKey(
                    context.transactionManager(), table, directoryKey, firstColumn, values[firstColumn]);
            if (candidates.isPresent()) {
                for (Candidate candidateEntry : candidates.get()) {
                    long candidateRowId = candidateEntry.rowId();
                    if (candidateRowId == currentRowId) {
                        continue;
                    }
                    MvccRawStoreTable.DirectoryRecord directory =
                            MvccRawStoreRowDirectory.findCurrent(
                                    transaction,
                                    table,
                                    candidateEntry.rowLocation(),
                                    projection);
                    MvccRawStoreTable.DirectoryHeadSummary summary = directory.head().summary();
                    MvccRawStoreTable.VisibleRow candidate;
                    if (directory.rowBearing()
                            && summary.available()
                            && summary.visibleTo(context.transactionId(), committedSequence)) {
                        candidate = summary.tombstone()
                                ? null
                                : new MvccRawStoreTable.VisibleRow(
                                        candidateRowId,
                                        directory.head().versionId(),
                                        directory.currentValues(),
                                        null,
                                        MvccRawStoreRowDirectory.location(
                                                candidateRowId, directory.handle()));
                    } else {
                        MvccRawStoreTable.VersionRecord visible =
                                MvccRawStoreVersionReader.findVisible(
                                        transaction,
                                        table,
                                        candidateRowId,
                                        directory.head(),
                                        context.transactionId(),
                                        committedSequence,
                                        projection);
                        candidate = visible == null || visible.tombstone()
                                ? null
                                : new MvccRawStoreTable.VisibleRow(
                                        candidateRowId,
                                        visible.versionId(),
                                        visible.values(),
                                        visible.handle(),
                                        MvccRawStoreRowDirectory.location(
                                                candidateRowId, directory.handle()));
                    }
                    rejectDuplicate(table, constraint, values, columns, currentRowId, candidate);
                }
            } else {
                for (MvccRawStoreTable.VisibleRow candidate : MvccRawStoreTable.scanVisibleAt(
                        transaction, table, committedSequence, projection, context)) {
                    rejectDuplicate(table, constraint, values, columns, currentRowId, candidate);
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
                MvccRawStoreTableMetadata.refreshUniqueConstraints(transaction, table, false);
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
        int[] columns = constraint.columns();
        List<MvccRawStoreTable.VisibleRow> rows = MvccRawStoreTable.scanVisibleAt(
                transaction,
                table,
                committedSequence,
                constraintProjection(table, columns),
                context);
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

    static Optional<List<Candidate>> candidatesForAt(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            Qualifier[][] qualifiers,
            MvccRawStoreTransactionContext context,
            MvccConglomerate.MvccDynamicCompiledOpenConglomInfo compiledInfo)
            throws StandardException {
        Optional<MvccRawStoreOrderedIndexPredicate.Predicate> optionalPredicate =
                MvccRawStoreOrderedIndexPredicate.from(qualifiers);
        if (optionalPredicate.isEmpty()) {
            return Optional.empty();
        }
        MvccRawStoreOrderedIndexPredicate.Predicate predicate = optionalPredicate.get();
        if (!maintainsColumn(table, predicate.columnId()) || directoryKey == null) {
            return Optional.empty();
        }
        long btree = context.orderedIndexBtreeForRead(
                table,
                directoryKey,
                predicate.columnId());
        if (btree == 0L || MvccRawStoreOrderedIndexGeneration.isDisabled(btree)) {
            return Optional.empty();
        }
        return Optional.of(scanCandidates(
                transactionManager,
                table,
                btree,
                predicate,
                compiledInfo));
    }


    private static Optional<List<Candidate>> candidatesForKey(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            int column,
            StoreDataValue key) throws StandardException {
        long[] btrees = MvccRawStoreOrderedIndexGeneration.requireBtreeConglomerates(
                transactionManager, table, directoryKey);
        if (MvccRawStoreOrderedIndexGeneration.isDisabled(btrees[column])) {
            return Optional.empty();
        }
        MvccRawStoreOrderedIndexPredicate.Predicate predicate =
                MvccRawStoreOrderedIndexPredicate.equality(column, key);
        return Optional.of(scanCandidates(
                transactionManager,
                table,
                btrees[column],
                predicate));
    }

    private static List<Candidate> scanCandidates(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            long btreeConglomerate,
            MvccRawStoreOrderedIndexPredicate.Predicate predicate) throws StandardException {
        return scanCandidates(
                transactionManager,
                table,
                btreeConglomerate,
                predicate,
                null);
    }

    private static List<Candidate> scanCandidates(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            long btreeConglomerate,
            MvccRawStoreOrderedIndexPredicate.Predicate predicate,
            MvccConglomerate.MvccDynamicCompiledOpenConglomInfo compiledInfo)
            throws StandardException {
        StoreDataValue[] startKey = predicate.lowerBound() == null
                ? null
                : new StoreDataValue[] {StoreValueCopySupport.cloneValue(predicate.lowerBound())};
        StoreDataValue[] stopKey = predicate.upperBound() == null
                ? null
                : new StoreDataValue[] {StoreValueCopySupport.cloneValue(predicate.upperBound())};
        int startOperator = startKey == null
                ? ScanController.NA
                : (predicate.lowerInclusive() ? ScanController.GE : ScanController.GT);
        int stopOperator = stopKey == null
                ? ScanController.NA
                : (predicate.upperInclusive() ? ScanController.GT : ScanController.GE);
        LinkedHashMap<Long, Candidate> candidates = new LinkedHashMap<>();
        ScanController scan;
        if (compiledInfo == null) {
            scan = transactionManager.openScan(
                    btreeConglomerate,
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_READ_UNCOMMITTED,
                    null,
                    startKey,
                    startOperator,
                    null,
                    stopKey,
                    stopOperator);
        } else {
            compiledInfo.prepareOrderedIndex(transactionManager, btreeConglomerate);
            scan = transactionManager.openCompiledScan(
                    false,
                    0,
                    TransactionController.MODE_RECORD,
                    TransactionController.ISOLATION_READ_UNCOMMITTED,
                    null,
                    startKey,
                    startOperator,
                    null,
                    stopKey,
                    stopOperator,
                    compiledInfo.orderedIndexStaticInfo(),
                    compiledInfo.orderedIndexDynamicInfo());
        }
        try {
            StoreDataValue[] row = indexRowTemplate(
                    transactionManager.getRawStoreXact(),
                    table,
                    predicate.columnId());
            while (scan.fetchNext(row)) {
                long rowId = StoreTypeUtil.getLong(row[ROW_ID_FIELD]);
                long versionId = StoreTypeUtil.getLong(row[VERSION_ID_FIELD]);
                MvccRowLocation rowLocation = MvccRowLocation.from(
                        row[ROW_LOCATION_FIELD]);
                if (rowLocation.rowId() != rowId) {
                    throw new IllegalStateException(
                            "RawStore MVCC B-tree candidate row-location identity mismatch: row="
                                    + rowId + ", location=" + rowLocation);
                }
                candidates.putIfAbsent(
                        rowId,
                        new Candidate(
                                predicate.columnId(),
                                StoreValueCopySupport.cloneValue(row[KEY_FIELD], true),
                                rowId,
                                versionId,
                                rowLocation));
            }
        } finally {
            scan.close();
        }
        return List.copyOf(candidates.values());
    }


    private static void insertVersionIntoBtrees(
            TransactionManager transactionManager,
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            long[] btrees,
            VersionInput version) throws StandardException {
        StoreDataValue[] values = version.values();
        if (values.length != table.columnCount()) {
            throw new IllegalArgumentException(
                    "RawStore MVCC ordered-index value count mismatch");
        }
        for (int column = 0; column < table.columnCount(); column++) {
            if (!maintainsColumn(table, column)
                    || MvccRawStoreOrderedIndexGeneration.isDisabled(btrees[column])) {
                continue;
            }
            try {
                insertEntry(
                        transactionManager,
                        btrees[column],
                        indexRow(
                                transaction,
                                table,
                                column,
                                values[column],
                                version.rowId(),
                                version.versionId(),
                                version.rowLocation()),
                        true);
            } catch (StandardException failure) {
                if (!isOversizedBtreeKey(failure)) {
                    throw failure;
                }
                MvccRawStoreOrderedIndexGeneration.disableBtree(
                        transactionManager,
                        table,
                        null,
                        btrees,
                        column);
            }
        }
    }

    private static void insertEntry(
            TransactionManager transactionManager,
            long btreeConglomerate,
            StoreDataValue[] row) throws StandardException {
        insertEntry(transactionManager, btreeConglomerate, row, false);
    }

    private static void insertEntry(
            TransactionManager transactionManager,
            long btreeConglomerate,
            StoreDataValue[] row,
            boolean baseRowAlreadyLocked) throws StandardException {
        int openMode = TransactionController.OPENMODE_FORUPDATE
                | (baseRowAlreadyLocked
                        ? TransactionController.OPENMODE_BASEROW_INSERT_LOCKED
                        : 0);
        ConglomerateController controller = transactionManager.openConglomerate(
                btreeConglomerate,
                false,
                openMode,
                TransactionController.MODE_RECORD,
                TransactionController.ISOLATION_REPEATABLE_READ);
        try {
            int status = controller.insert(row);
            if (status != 0) {
                throw new IllegalStateException(
                        "RawStore MVCC B-tree rejected a distinct version entry: " + status);
            }
        } finally {
            controller.close();
        }
    }


    private static StoreDataValue[] indexRow(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            int column,
            StoreDataValue key,
            long rowId,
            long versionId,
            MvccRowLocation rowLocation) throws StandardException {
        StoreDataValue[] row = indexRowTemplate(transaction, table, column);
        row[KEY_FIELD] = StoreValueCopySupport.cloneValue(key);
        StoreTypeUtil.setLongValue(row[ROW_ID_FIELD], rowId);
        StoreTypeUtil.setLongValue(row[VERSION_ID_FIELD], versionId);
        if (rowLocation == null || rowLocation.rowId() != rowId) {
            throw new IllegalArgumentException(
                    "RawStore MVCC ordered-index row location does not match row " + rowId);
        }
        row[ROW_LOCATION_FIELD] = rowLocation.cloneValue(false);
        return row;
    }

    static StoreDataValue[] indexRowTemplate(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            int column) throws StandardException {
        StoreDataValue[] row = new StoreDataValue[INDEX_FIELD_COUNT];
        row[KEY_FIELD] = MvccRawStoreFormat.nullValue(
                transaction,
                table.formatIds()[column],
                table.collationIds()[column]);
        row[ROW_ID_FIELD] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[VERSION_ID_FIELD] = MvccRawStoreFormat.longValue(transaction, 0L);
        row[ROW_LOCATION_FIELD] = new MvccRowLocation();
        return row;
    }


    static boolean indexesColumn(MvccRawStoreTable.Descriptor table, int columnId) {
        if (columnId < 0 || columnId >= table.columnCount()) {
            return false;
        }
        return isOrderableFormat(table.formatIds()[columnId]);
    }

    static boolean maintainsColumn(MvccRawStoreTable.Descriptor table, int columnId) {
        if (!indexesColumn(table, columnId)) {
            return false;
        }
        for (MvccRawStoreTable.UniqueConstraint constraint : table.uniqueConstraints()) {
            int[] columns = constraint.columns();
            if (columns.length > 0 && columns[0] == columnId) {
                return true;
            }
        }
        return false;
    }

    static boolean requiresLargePage(MvccRawStoreTable.Descriptor table, int columnId) {
        if (!indexesColumn(table, columnId)) {
            return false;
        }
        return switch (table.formatIds()[columnId]) {
            case StoredFormatIds.SQL_VARCHAR_ID,
                    StoredFormatIds.SQL_VARBIT_ID -> true;
            default -> false;
        };
    }

    static void validateConstraintColumns(
            MvccRawStoreTable.Descriptor table,
            int[] columns) throws StandardException {
        for (int column : columns) {
            if (!indexesColumn(table, column)) {
                throw StandardException.newException(
                        SQLState.NOT_IMPLEMENTED,
                        "RawStore MVCC unique constraints require orderable columns");
            }
        }
    }

    static int indexedColumnCount(MvccRawStoreTable.Descriptor table) {
        int count = 0;
        for (int column = 0; column < table.columnCount(); column++) {
            if (indexesColumn(table, column)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isOrderableFormat(int formatId) {
        return switch (formatId) {
            case StoredFormatIds.SQL_BLOB_ID,
                    StoredFormatIds.SQL_CLOB_ID,
                    StoredFormatIds.SQL_LONGVARCHAR_ID,
                    StoredFormatIds.SQL_LONGVARBIT_ID,
                    StoredFormatIds.SQL_USERTYPE_ID_V3,
                    StoredFormatIds.XML_ID -> false;
            default -> true;
        };
    }


    private static boolean indexedValueUnchanged(
            StoreDataValue[] previousValues,
            StoreDataValue[] values,
            int column) throws StandardException {
        return previousValues != null
                && StoreTypeUtil.compare(previousValues[column], values[column], true) == 0;
    }

    private static boolean isOversizedBtreeKey(StandardException failure) {
        return StandardException.getSQLStateFromIdentifier(
                SQLState.BTREE_NO_SPACE_FOR_KEY).equals(failure.getSQLState());
    }

    private static void rejectDuplicate(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.UniqueConstraint constraint,
            StoreDataValue[] values,
            int[] columns,
            long currentRowId,
            MvccRawStoreTable.VisibleRow candidate) throws StandardException {
        if (candidate == null || candidate.rowId() == currentRowId) {
            return;
        }
        if (sameKey(values, candidate.values(), columns)) {
            throw StandardException.newException(
                    SQLState.LANG_DUPLICATE_KEY_CONSTRAINT,
                    constraint.displayName(),
                    "RAWSTORE_MVCC_" + table.metadataContainer().getContainerId());
        }
    }


    private static MvccRawStoreVersionRows.FetchProjection constraintProjection(
            MvccRawStoreTable.Descriptor table,
            int[] columns) {
        FormatableBitSet projection = new FormatableBitSet(table.columnCount());
        for (int column : columns) {
            projection.set(column);
        }
        return MvccRawStoreVersionRows.projection(table, projection);
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


    record Candidate(
            int columnId,
            StoreDataValue key,
            long rowId,
            long versionId,
            MvccRowLocation rowLocation) {
        Candidate {
            if (key == null) {
                throw new IllegalArgumentException(
                        "RawStore MVCC candidate key is absent for row " + rowId);
            }
            if (rowLocation == null || rowLocation.rowId() != rowId) {
                throw new IllegalArgumentException(
                        "RawStore MVCC candidate location does not match row " + rowId);
            }
            rowLocation = (MvccRowLocation) rowLocation.cloneValue(false);
        }
    }

    record VersionInput(
            long rowId,
            long versionId,
            MvccRowLocation rowLocation,
            StoreDataValue[] values) {
        VersionInput {
            if (rowLocation == null || rowLocation.rowId() != rowId) {
                throw new IllegalArgumentException(
                        "RawStore MVCC rebuild location does not match row " + rowId);
            }
            rowLocation = (MvccRowLocation) rowLocation.cloneValue(false);
            values = values == null ? null : values.clone();
        }
    }

}
