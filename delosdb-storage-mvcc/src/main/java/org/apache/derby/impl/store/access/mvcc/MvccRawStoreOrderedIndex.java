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
 * It contains one Derby B-tree conglomerate id for every orderable base-table
 * column.  The B-trees narrow predicates to logical row ids; the authoritative
 * MVCC version chain always rechecks visibility and remaining qualifiers.</p>
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
            ContainerKey directoryKey,
            long rowId,
            long versionId,
            MvccRowLocation rowLocation,
            StoreDataValue[] values) throws StandardException {
        if (values == null) {
            return;
        }
        if (values.length != table.columnCount()) {
            throw new IllegalArgumentException(
                    "RawStore MVCC ordered-index value count mismatch");
        }
        long[] btrees = MvccRawStoreOrderedIndexGeneration.requireBtreeConglomerates(
                transactionManager,
                table,
                directoryKey);
        for (int column = 0; column < table.columnCount(); column++) {
            if (!indexesColumn(table, column)) {
                continue;
            }
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
            Transaction transaction,
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] previousValues,
            StoreDataValue[] values,
            long currentRowId,
            MvccRawStoreTransactionContext context) throws StandardException {
        if (values == null || values.length != table.columnCount()) {
            throw new IllegalArgumentException("RawStore MVCC unique-key row width mismatch");
        }
        List<MvccRawStoreTable.UniqueConstraint> constraints =
                MvccRawStoreTableMetadata.refreshUniqueConstraints(transaction, table, false);
        if (constraints.isEmpty()) {
            return;
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
            List<Candidate> candidates = candidatesForKey(
                    context.transactionManager(),
                    table,
                    directoryKey,
                    firstColumn,
                    values[firstColumn]);
            for (Candidate candidateEntry : candidates) {
                long candidateRowId = candidateEntry.rowId();
                if (candidateRowId == currentRowId) {
                    continue;
                }
                MvccRawStoreTable.VisibleRow candidate = MvccRawStoreTable.readVisibleAt(
                        transaction,
                        table,
                        candidateEntry.rowLocation(),
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

    static Optional<List<Candidate>> candidatesForAt(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            Qualifier[][] qualifiers) throws StandardException {
        Optional<MvccRawStoreOrderedIndexPredicate.Predicate> optionalPredicate =
                MvccRawStoreOrderedIndexPredicate.from(qualifiers);
        if (optionalPredicate.isEmpty()) {
            return Optional.empty();
        }
        MvccRawStoreOrderedIndexPredicate.Predicate predicate = optionalPredicate.get();
        if (!indexesColumn(table, predicate.columnId()) || directoryKey == null) {
            return Optional.empty();
        }
        long[] btrees = MvccRawStoreOrderedIndexGeneration.readBtreeConglomerates(
                transactionManager.getRawStoreXact(),
                table,
                directoryKey);
        if (btrees == null || btrees[predicate.columnId()] == 0L) {
            return Optional.empty();
        }
        return Optional.of(scanCandidates(
                transactionManager,
                table,
                btrees[predicate.columnId()],
                predicate));
    }


    private static List<Candidate> candidatesForKey(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            int column,
            StoreDataValue key) throws StandardException {
        long[] btrees = MvccRawStoreOrderedIndexGeneration.requireBtreeConglomerates(
                transactionManager, table, directoryKey);
        MvccRawStoreOrderedIndexPredicate.Predicate predicate =
                MvccRawStoreOrderedIndexPredicate.equality(column, key);
        return scanCandidates(
                transactionManager,
                table,
                btrees[column],
                predicate);
    }

    private static List<Candidate> scanCandidates(
            TransactionManager transactionManager,
            MvccRawStoreTable.Descriptor table,
            long btreeConglomerate,
            MvccRawStoreOrderedIndexPredicate.Predicate predicate) throws StandardException {
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
        ScanController scan = transactionManager.openScan(
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
        try {
            while (scan.next()) {
                StoreDataValue[] row = indexRowTemplate(
                        transactionManager.getRawStoreXact(),
                        table,
                        predicate.columnId());
                scan.fetch(row);
                long rowId = StoreTypeUtil.getLong(row[ROW_ID_FIELD]);
                MvccRowLocation rowLocation = MvccRowLocation.from(
                        row[ROW_LOCATION_FIELD]);
                if (rowLocation.rowId() != rowId) {
                    throw new IllegalStateException(
                            "RawStore MVCC B-tree candidate row-location identity mismatch: row="
                                    + rowId + ", location=" + rowLocation);
                }
                candidates.putIfAbsent(
                        rowId,
                        new Candidate(rowId, rowLocation));
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
            if (!indexesColumn(table, column)) {
                continue;
            }
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


    record Candidate(long rowId, MvccRowLocation rowLocation) {
        Candidate {
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
