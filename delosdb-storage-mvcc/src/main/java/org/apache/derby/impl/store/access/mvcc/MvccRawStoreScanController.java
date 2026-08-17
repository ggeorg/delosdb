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
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * RawStore-backed MVCC scan.
 *
 * <p>The default path preserves the existing materialized scan. An opt-in
 * diagnostic path streams ordered-index candidates through bounded batches so
 * range-scan materialization can be measured without changing the production
 * default.</p>
 */
final class MvccRawStoreScanController implements ScanManager {
    private static final String BOUNDED_INDEXED_SCAN_PROPERTY =
            "delosdb.experimental.mvccBoundedIndexedScan";
    private static final int INDEXED_SCAN_BATCH_SIZE = 64;
    private final MvccRawStoreRuntime runtime;
    private final MvccRawStoreTable.Descriptor table;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private final boolean hold;
    private final boolean forUpdate;
    private final FormatableBitSet scanColumnList;
    private final MvccRawStoreVersionRows.FetchProjection versionProjection;
    private final MvccConglomerate.MvccDynamicCompiledOpenConglomInfo compiledInfo;
    private final long snapshotSequence;
    private final MvccRawStoreRuntime.SnapshotLease heldSnapshotLease;
    private final boolean readCommittedUpdateRecheck;
    private Qualifier[][] qualifiers;
    private List<MvccRawStoreTable.VisibleRow> rows;
    private int nextIndex;
    private MvccRawStoreTable.VisibleRow current;
    private boolean currentDeleted;
    private boolean closed;
    private long rowsVisited;
    private long rowsQualified;
    private long estimatedRowCount;
    private boolean orderedIndexScan;
    private boolean coveringIndexScan;
    private MvccRawStoreIndexedReadMetrics.Snapshot indexedReadMetrics =
            MvccRawStoreIndexedReadMetrics.EMPTY;
    private MvccRawStoreOrderedIndex.CandidateCursor indexedCandidateCursor;
    private MvccRawStoreIndexedReader indexedReader;
    private MvccRawStoreRuntime.TableReadBoundary indexedReadBoundary;
    private boolean boundedIndexedScan;
    private boolean boundedCoveringEligible;
    private boolean boundedAllCovered;
    private boolean boundedSawCandidate;

    MvccRawStoreScanController(
            MvccRawStoreRuntime runtime,
            MvccRawStoreTable.Descriptor table,
            TransactionManager transactionManager,
            Transaction rawTransaction,
            boolean hold,
            int openMode,
            int isolationLevel,
            FormatableBitSet scanColumnList,
            Qualifier[][] qualifiers,
            MvccConglomerate.MvccDynamicCompiledOpenConglomInfo compiledInfo)
            throws StandardException {
        this.runtime = runtime;
        this.table = table;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
        this.hold = hold;
        this.forUpdate = (openMode & TransactionController.OPENMODE_FORUPDATE) != 0;
        this.scanColumnList = scanColumnList;
        this.versionProjection = MvccRawStoreVersionRows.projection(table, scanColumnList);
        this.qualifiers = qualifiers;
        this.compiledInfo = compiledInfo;
        MvccRawStoreTransactionContext context = runtime.context(
                transactionManager,
                rawTransaction);
        boolean statementSnapshot = usesStatementSnapshot(isolationLevel);
        this.readCommittedUpdateRecheck = forUpdate && statementSnapshot;
        if (statementSnapshot) {
            // READ COMMITTED and weaker isolation levels require a fresh
            // committed horizon for every SQL scan. Keep the lease until the
            // scan closes so vacuum cannot cross that statement.
            this.heldSnapshotLease = runtime.openSnapshotLease();
            this.snapshotSequence = heldSnapshotLease.sequence();
        } else {
            // REPEATABLE READ uses the transaction-owned snapshot. A held
            // cursor needs an additional lease if the transaction commits.
            this.snapshotSequence = context.snapshotSequence();
            this.heldSnapshotLease = hold ? context.retainSnapshotLease() : null;
        }
        try {
            reload();
        } catch (StandardException | RuntimeException | Error failure) {
            if (heldSnapshotLease != null) {
                heldSnapshotLease.close();
            }
            throw failure;
        }
    }


    private static boolean usesStatementSnapshot(int isolationLevel) {
        return isolationLevel == TransactionController.ISOLATION_READ_UNCOMMITTED
                || isolationLevel == TransactionController.ISOLATION_READ_COMMITTED
                || isolationLevel == TransactionController.ISOLATION_READ_COMMITTED_NOHOLDLOCK;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            closeBoundedIndexedResources();
            rows = List.of();
            current = null;
            if (heldSnapshotLease != null) {
                heldSnapshotLease.close();
            }
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
            StoreDataValue[] row = StoreValueCopySupport.cloneRow(current.values());
            MvccRowLocation location = (MvccRowLocation) current.directoryLocation().cloneValue(false);
            if (hashTable.includeRowLocations()) {
                location.setWriteVersion(current.versionId());
            }
            hashTable.putRow(true, row, location);
            count++;
        }
        while (next()) {
            // The interface requires fetchSet to leave the scan exhausted.
        }
    }

    @Override
    public ScanInfo getScanInfo() {
        ensureOpen();
        String scanType = coveringIndexScan
                ? "delos_mvcc_rawstore_ordered_index_covering"
                : orderedIndexScan ? "delos_mvcc_rawstore_ordered_index" : "delos_mvcc";
        return new MvccScanInfo(
                scanType,
                rowsVisited,
                rowsQualified,
                scanColumnList,
                indexedReadMetrics,
                snapshotSequence);
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
        ensurePositioned();
        if (currentDeleted) {
            return false;
        }
        MvccRowLocation location = (MvccRowLocation) current.directoryLocation().cloneValue(false);
        location.setWriteVersion(current.versionId());
        boolean deleted = MvccRawStoreTable.delete(
                rawTransaction,
                table,
                location,
                runtime.context(transactionManager, rawTransaction));
        currentDeleted = deleted;
        return deleted;
    }

    @Override
    public void didNotQualify() {
        ensureOpen();
    }

    @Override
    public boolean doesCurrentPositionQualify() throws StandardException {
        ensureOpen();
        return current != null && !currentDeleted && qualifies(current.values());
    }

    @Override
    public boolean isHeldAfterCommit() {
        return hold;
    }

    @Override
    public void fetch(StoreDataValue[] destRow) throws StandardException {
        ensurePositioned();
        StoreValueCopySupport.copyRow(current.values(), destRow, scanColumnList);
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
            StoreValueCopySupport.copyRow(current.values(), rowArray[count], scanColumnList);
            if (rowlocArray != null) {
                if (rowlocArray[count] == null) {
                    rowlocArray[count] = new MvccRowLocation();
                }
                MvccRowLocation location = MvccRowLocation.from(rowlocArray[count]);
                location.copyFrom(current.directoryLocation());
                location.setWriteVersion(current.versionId());
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
            destination.copyFrom(current.directoryLocation());
            destination.setWriteVersion(current.versionId());
        }
    }

    @Override
    public boolean isCurrentPositionDeleted() {
        ensureOpen();
        return currentDeleted;
    }

    @Override
    public boolean next() throws StandardException {
        ensureOpen();
        while (true) {
            while (nextIndex < rows.size()) {
                MvccRawStoreTable.VisibleRow candidate = rows.get(nextIndex++);
                rowsVisited++;
                if (qualifies(candidate.values())) {
                    if (readCommittedUpdateRecheck) {
                        candidate = lockAndRefresh(candidate);
                        if (candidate == null || !qualifies(candidate.values())) {
                            continue;
                        }
                    }
                    rowsQualified++;
                    current = candidate;
                    currentDeleted = false;
                    return true;
                }
            }
            if (!boundedIndexedScan || !loadNextBoundedIndexedBatch()) {
                current = null;
                currentDeleted = false;
                return false;
            }
        }
    }


    private MvccRawStoreTable.VisibleRow lockAndRefresh(
            MvccRawStoreTable.VisibleRow candidate) throws StandardException {
        MvccRawStoreTransactionContext context = runtime.context(
                transactionManager, rawTransaction);
        context.lockRowForReadCommittedUpdate(
                table, (MvccRowLocation) candidate.directoryLocation());
        try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
            return MvccRawStoreTable.readLockedCurrentForWrite(
                    rawTransaction,
                    table,
                    (MvccRowLocation) candidate.directoryLocation(),
                    versionProjection);
        }
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) throws StandardException {
        ensureOpen();
        MvccRowLocation mvccLocation = MvccRowLocation.from(rowLocation);
        MvccRawStoreTransactionContext context = runtime.context(
                transactionManager,
                rawTransaction);
        MvccRawStoreTable.VisibleRow visible;
        try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
            visible = MvccRawStoreTable.readVisibleAt(
                    rawTransaction,
                    table,
                    mvccLocation,
                    snapshotSequence,
                    versionProjection,
                    context);
        }
        if (visible == null || !qualifies(visible.values())) {
            current = null;
            currentDeleted = false;
            return false;
        }
        current = visible;
        currentDeleted = false;
        return true;
    }

    @Override
    public boolean replace(StoreDataValue[] row, FormatableBitSet validColumns) throws StandardException {
        ensurePositioned();
        if (currentDeleted) {
            return false;
        }
        StoreDataValue[] replacement = StoreValueCopySupport.replacementRow(
                current.values(),
                row,
                validColumns);
        MvccRowLocation location = (MvccRowLocation) current.directoryLocation().cloneValue(false);
        location.setWriteVersion(current.versionId());
        boolean replaced = MvccRawStoreTable.replace(
                rawTransaction,
                table,
                location,
                row,
                validColumns,
                runtime.context(transactionManager, rawTransaction),
                readCommittedUpdateRecheck);
        if (replaced) {
            current = new MvccRawStoreTable.VisibleRow(
                    current.rowId(),
                    current.versionId(),
                    replacement,
                    current.versionHandle(),
                    current.directoryLocation());
        }
        return replaced;
    }

    private void reload() throws StandardException {
        closeBoundedIndexedResources();
        boundedIndexedScan = false;
        boundedCoveringEligible = false;
        boundedAllCovered = false;
        boundedSawCandidate = false;
        MvccRawStoreTransactionContext context = runtime.context(transactionManager, rawTransaction);
        boolean boundedIndexedScanEnabled = Boolean.getBoolean(BOUNDED_INDEXED_SCAN_PROPERTY)
                && !forUpdate
                && !hold;
        if (boundedIndexedScanEnabled) {
            loadBoundedIndexedScan(context);
        } else {
            loadMaterializedScan(context);
        }
        nextIndex = 0;
        current = null;
        currentDeleted = false;
        rowsVisited = 0L;
        rowsQualified = 0L;
        estimatedRowCount = boundedIndexedScan ? 0L : rows.size();
    }

    private void loadMaterializedScan(MvccRawStoreTransactionContext context)
            throws StandardException {
        try (MvccRawStoreRuntime.TableReadBoundary ignored = runtime.enterTableRead(table)) {
            java.util.Optional<List<MvccRawStoreOrderedIndex.Candidate>> candidates =
                    MvccRawStoreTable.orderedIndexCandidatesForAt(
                            table,
                            qualifiers,
                            context,
                            compiledInfo);
            if (candidates.isPresent()) {
                List<MvccRawStoreOrderedIndex.Candidate> candidateRows = candidates.get();
                List<MvccRawStoreTable.VisibleRow> indexedRows = new java.util.ArrayList<>();
                boolean allCovered = !candidateRows.isEmpty();
                try (MvccRawStoreIndexedReader reader = new MvccRawStoreIndexedReader(
                        rawTransaction,
                        table,
                        snapshotSequence,
                        versionProjection,
                        context)) {
                    boolean coveringEligible = !candidateRows.isEmpty()
                            && coveringEligible(candidateRows.get(0).columnId());
                    for (MvccRawStoreIndexedReader.Result result : reader.read(
                            candidateRows,
                            coveringEligible)) {
                        allCovered &= result.covered();
                        if (result.row() != null) {
                            indexedRows.add(result.row());
                        }
                    }
                    indexedReadMetrics = reader.metrics();
                }
                rows = List.copyOf(indexedRows);
                orderedIndexScan = true;
                coveringIndexScan = allCovered;
            } else {
                loadMaterializedTableScan(context);
            }
        }
    }

    private void loadBoundedIndexedScan(MvccRawStoreTransactionContext context)
            throws StandardException {
        MvccRawStoreRuntime.TableReadBoundary boundary = runtime.enterTableRead(table);
        boolean retainBoundary = false;
        try {
            java.util.Optional<MvccRawStoreOrderedIndex.CandidateCursor> candidates =
                    MvccRawStoreOrderedIndex.openCandidateCursorForAt(
                            transactionManager,
                            table,
                            context.orderedIndexForRead(table),
                            qualifiers,
                            context,
                            compiledInfo);
            if (candidates.isEmpty()) {
                loadMaterializedTableScan(context);
                return;
            }

            indexedCandidateCursor = candidates.get();
            indexedReader = new MvccRawStoreIndexedReader(
                    rawTransaction,
                    table,
                    snapshotSequence,
                    versionProjection,
                    context);
            indexedReadBoundary = boundary;
            retainBoundary = true;
            boundedIndexedScan = true;
            boundedCoveringEligible = coveringEligible(indexedCandidateCursor.columnId());
            boundedAllCovered = true;
            boundedSawCandidate = false;
            rows = List.of();
            orderedIndexScan = true;
            coveringIndexScan = false;
            indexedReadMetrics = MvccRawStoreIndexedReadMetrics.EMPTY;
        } catch (StandardException | RuntimeException | Error failure) {
            closeBoundedIndexedResources();
            throw failure;
        } finally {
            if (!retainBoundary) {
                boundary.close();
            }
        }
    }

    private void loadMaterializedTableScan(MvccRawStoreTransactionContext context)
            throws StandardException {
        rows = MvccRawStoreTable.scanVisibleAt(
                rawTransaction,
                table,
                snapshotSequence,
                versionProjection,
                context);
        orderedIndexScan = false;
        coveringIndexScan = false;
        indexedReadMetrics = MvccRawStoreIndexedReadMetrics.EMPTY;
    }

    private boolean loadNextBoundedIndexedBatch() throws StandardException {
        while (true) {
            List<MvccRawStoreOrderedIndex.Candidate> candidates =
                    indexedCandidateCursor.nextBatch(INDEXED_SCAN_BATCH_SIZE);
            if (candidates.isEmpty()) {
                indexedReadMetrics = indexedReader.metrics();
                coveringIndexScan = boundedSawCandidate && boundedAllCovered;
                closeBoundedIndexedResources();
                rows = List.of();
                nextIndex = 0;
                return false;
            }

            boundedSawCandidate = true;
            List<MvccRawStoreIndexedReader.Result> results = indexedReader.read(
                    candidates,
                    boundedCoveringEligible);
            java.util.ArrayList<MvccRawStoreTable.VisibleRow> visibleRows =
                    new java.util.ArrayList<>(results.size());
            for (MvccRawStoreIndexedReader.Result result : results) {
                boundedAllCovered &= result.covered();
                if (result.row() != null) {
                    visibleRows.add(result.row());
                }
            }
            indexedReadMetrics = indexedReader.metrics();
            rows = visibleRows.isEmpty() ? List.of() : List.copyOf(visibleRows);
            nextIndex = 0;
            if (!rows.isEmpty()) {
                return true;
            }
        }
    }

    private void closeBoundedIndexedResources() {
        if (indexedReader != null) {
            indexedReader.close();
            indexedReader = null;
        }
        if (indexedCandidateCursor != null) {
            indexedCandidateCursor.close();
            indexedCandidateCursor = null;
        }
        if (indexedReadBoundary != null) {
            indexedReadBoundary.close();
            indexedReadBoundary = null;
        }
        boundedIndexedScan = false;
    }


    private boolean coveringEligible(int indexedColumn) {
        if (forUpdate || scanColumnList == null) {
            return false;
        }
        int column = -1;
        while ((column = scanColumnList.anySetBit(column)) >= 0) {
            if (column != indexedColumn) {
                return false;
            }
        }
        if (qualifiers != null) {
            for (Qualifier[] group : qualifiers) {
                if (group == null) {
                    return false;
                }
                for (Qualifier qualifier : group) {
                    if (qualifier == null || qualifier.getColumnId() != indexedColumn) {
                        return false;
                    }
                }
            }
        }
        return true;
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

}
