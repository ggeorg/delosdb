/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreScanController

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.List;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.BackingStoreHashtable;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Materialized scan over RawStore directory and version rows. */
final class MvccRawStoreScanController implements ScanManager {
    private final MvccRawStoreRuntime runtime;
    private final MvccRawStoreTable.Descriptor table;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private final boolean hold;
    private final FormatableBitSet scanColumnList;
    private Qualifier[][] qualifiers;
    private List<MvccRawStoreTable.VisibleRow> rows;
    private int nextIndex;
    private MvccRawStoreTable.VisibleRow current;
    private boolean closed;
    private long rowsVisited;
    private long rowsQualified;
    private long estimatedRowCount;

    MvccRawStoreScanController(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            TransactionManager transactionManager,
            Transaction rawTransaction,
            boolean hold,
            FormatableBitSet scanColumnList,
            Qualifier[][] qualifiers) throws StandardException {
        this.runtime = runtime;
        this.table = table;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
        this.hold = hold;
        this.scanColumnList = scanColumnList;
        this.qualifiers = qualifiers;
        reload();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            rows = List.of();
            current = null;
            transactionManager.closeMe(this);
        }
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        if (!hold || closeHeldScan) {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void fetchSet(long maxRowCount, int[] keyColumnNumbers, BackingStoreHashtable hashTable)
            throws StandardException {
        ensureOpen();
        long count = 0L;
        while ((maxRowCount < 0L || count < maxRowCount) && next()) {
            StoreDataValue[] row = MvccConglomerateController.cloneRow(current.values());
            hashTable.putRow(true, row, new MvccRowLocation(current.rowId()));
            count++;
        }
        while (next()) {
            // The interface requires fetchSet to leave the scan exhausted.
        }
    }

    @Override
    public ScanInfo getScanInfo() {
        ensureOpen();
        return new MvccScanInfo(rowsVisited, rowsQualified, scanColumnList);
    }

    @Override
    public boolean isKeyed() {
        return false;
    }

    @Override
    public boolean isTableLocked() {
        return true;
    }

    @Override
    public StoreRowLocation newRowLocationTemplate() {
        ensureOpen();
        return new MvccRowLocation();
    }

    @Override
    public void reopenScan(
            StoreDataValue[] startKeyValue,
            int startSearchOperator,
            Qualifier[][] qualifier,
            StoreDataValue[] stopKeyValue,
            int stopSearchOperator) throws StandardException {
        ensureOpen();
        this.qualifiers = qualifier;
        reload();
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier)
            throws StandardException {
        ensureOpen();
        this.qualifiers = qualifier;
        reload();
        positionAtRowLocation(startRowLocation);
    }

    @Override
    public long getEstimatedRowCount() {
        return estimatedRowCount;
    }

    @Override
    public void setEstimatedRowCount(long count) {
        estimatedRowCount = count;
    }

    @Override
    public boolean delete() throws StandardException {
        throw unsupported("DELETE");
    }

    @Override
    public void didNotQualify() {
        ensureOpen();
    }

    @Override
    public boolean doesCurrentPositionQualify() throws StandardException {
        ensureOpen();
        return current != null && qualifies(current.values());
    }

    @Override
    public boolean isHeldAfterCommit() {
        return hold;
    }

    @Override
    public void fetch(StoreDataValue[] destRow) throws StandardException {
        ensurePositioned();
        MvccConglomerateController.copyRow(current.values(), destRow, scanColumnList);
    }

    @Override
    public void fetchWithoutQualify(StoreDataValue[] destRow) throws StandardException {
        fetch(destRow);
    }

    @Override
    public boolean fetchNext(StoreDataValue[] destRow) throws StandardException {
        if (!next()) {
            return false;
        }
        fetch(destRow);
        return true;
    }

    @Override
    public int fetchNextGroup(StoreDataValue[][] rowArray, StoreRowLocation[] rowlocArray)
            throws StandardException {
        int count = 0;
        while (count < rowArray.length && next()) {
            if (rowArray[count] == null) {
                if (rowArray.length == 0 || rowArray[0] == null) {
                    throw new IllegalStateException("RawStore MVCC group fetch requires a row template");
                }
                rowArray[count] = RowUtil.newRowFromTemplatePreservingArrayType(rowArray[0]);
            }
            MvccConglomerateController.copyRow(current.values(), rowArray[count], scanColumnList);
            if (rowlocArray != null) {
                if (rowlocArray[count] == null) {
                    rowlocArray[count] = new MvccRowLocation();
                }
                MvccRowLocation.from(rowlocArray[count]).set(current.rowId(), 0L, -1);
            }
            count++;
        }
        return count;
    }

    @Override
    public int fetchNextGroup(
            StoreDataValue[][] rowArray,
            StoreRowLocation[] oldrowlocArray,
            StoreRowLocation[] newrowlocArray) throws StandardException {
        return fetchNextGroup(rowArray, oldrowlocArray);
    }

    @Override
    public void fetchLocation(StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation destination = MvccRowLocation.from(destRowLocation);
        if (current == null) {
            destination.restoreToNull();
        } else {
            destination.set(current.rowId(), 0L, -1);
        }
    }

    @Override
    public boolean isCurrentPositionDeleted() {
        ensureOpen();
        return false;
    }

    @Override
    public boolean next() throws StandardException {
        ensureOpen();
        while (nextIndex < rows.size()) {
            MvccRawStoreTable.VisibleRow candidate = rows.get(nextIndex++);
            rowsVisited++;
            if (qualifies(candidate.values())) {
                rowsQualified++;
                current = candidate;
                return true;
            }
        }
        current = null;
        return false;
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) throws StandardException {
        ensureOpen();
        long rowId = MvccRowLocation.from(rowLocation).rowId();
        MvccRawStoreTable.VisibleRow visible = MvccRawStoreTable.readVisible(
                rawTransaction,
                table,
                rowId,
                runtime.context(transactionManager, rawTransaction));
        if (visible == null || !qualifies(visible.values())) {
            current = null;
            return false;
        }
        current = visible;
        return true;
    }

    @Override
    public boolean replace(StoreDataValue[] row, FormatableBitSet validColumns) throws StandardException {
        throw unsupported("UPDATE");
    }

    private void reload() throws StandardException {
        rows = MvccRawStoreTable.scanVisible(
                rawTransaction,
                table,
                runtime.context(transactionManager, rawTransaction));
        nextIndex = 0;
        current = null;
        rowsVisited = 0L;
        rowsQualified = 0L;
        estimatedRowCount = rows.size();
    }

    private boolean qualifies(StoreDataValue[] row) throws StandardException {
        return qualifiers == null || qualifiers.length == 0 || RowUtil.qualifyRow(row, qualifiers);
    }

    private void ensurePositioned() {
        ensureOpen();
        if (current == null) {
            throw new IllegalStateException("RawStore MVCC scan is not positioned on a row");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("RawStore MVCC scan is closed");
        }
    }

    private static StandardException unsupported(String operation) {
        return StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                operation + " for the isolated RawStore-backed delos_mvcc format");
    }
}
