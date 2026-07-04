/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccScanController

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

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.BackingStoreHashtable;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.RowUtil;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * MODULE6D inherited ScanManager preflight for Delos MVCC.
 *
 * <p>The scan opens a statement snapshot against the MVCC kernel and returns
 * visible rows through Derby's inherited ScanController shape. MODULE6F
 * allows physical MVCC full-table SQL SELECT to reach this controller through
 * the inherited TableScanResultSet path.</p>
 */
public final class MvccScanController implements ScanManager {
    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private final TransactionManager transactionManager;
    private final boolean hold;
    private final boolean completeWithDerbyTransaction;
    private final boolean transactionScopedReader;
    private final boolean readerBorrowedFromWriter;
    private final DelosStorageTransactionRegistry.Reader registeredReader;
    private final DelosStorageTransaction reader;
    private final DelosStorageSnapshot snapshot;
    private DelosStorageScan scan;
    private final FormatableBitSet scanColumnList;
    private Qualifier[][] qualifiers;
    private Iterator<Long> orderedIndexRowIds;
    private boolean orderedIndexRowIdScan;
    private boolean pageBackedCommittedRead;
    private DelosStorageRow current;
    private boolean closed;
    private DelosStorageTransaction writer;
    private MvccStoreAccessTransactionRegistry.Writer registeredWriter;
    private boolean writerBorrowedFromRegistry;
    private long estimatedRowCount;

    MvccScanController(
            MvccConglomerate conglomerate,
            TransactionManager transactionManager,
            boolean hold,
            int openMode,
            int isolationLevel,
            FormatableBitSet scanColumnList,
            Qualifier[][] qualifiers) {
        MvccBridgeDiagnosticsSupport.incrementOpenCount();
        this.conglomerate = conglomerate;
        this.state = conglomerate.state();
        this.transactionManager = transactionManager;
        this.hold = hold;
        this.completeWithDerbyTransaction = (openMode & TransactionController.OPENMODE_FORUPDATE)
                == TransactionController.OPENMODE_FORUPDATE;
        this.scanColumnList = scanColumnList;
        this.qualifiers = qualifiers;
        DelosStorageTransaction activeWriter = DelosStorageTransactionRegistry.activeWriterTransaction(
                transactionManager,
                state.table());
        this.transactionScopedReader = usesTransactionScopedSnapshot(isolationLevel);
        if (activeWriter != null) {
            this.readerBorrowedFromWriter = true;
            this.reader = activeWriter;
            if (transactionScopedReader) {
                this.registeredReader = DelosStorageTransactionRegistry.reader(transactionManager, state.table());
                this.snapshot = state.snapshot(reader, registeredReader.snapshot());
            } else {
                this.registeredReader = null;
                this.snapshot = state.snapshot(reader);
            }
        } else {
            this.readerBorrowedFromWriter = false;
            if (transactionScopedReader) {
                this.registeredReader = DelosStorageTransactionRegistry.reader(transactionManager, state.table());
                this.reader = registeredReader.transaction();
                this.snapshot = registeredReader.snapshot();
            } else {
                this.registeredReader = null;
                this.reader = state.beginTransaction();
                this.snapshot = state.snapshot(reader);
            }
        }
        try {
            openStorageScan();
        } catch (StandardException e) {
            throw new IllegalStateException("Could not open MVCC storage-api scan", e);
        }
        resetOrderedIndexScan(qualifiers);
    }


    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        if (!closed) {
            scan.close();
            closeReaderIfStatementScoped();
            if (!completeWithDerbyTransaction) {
                abortWriterIfActive();
            }
            closed = true;
            transactionManager.closeMe(this);
        }
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        if (!hold || closeHeldScan) {
            if (!closed) {
                scan.close();
                closeReaderIfStatementScoped();
                commitWriterIfActive();
                closed = true;
                transactionManager.closeMe(this);
            }
            return true;
        }
        return false;
    }

    @Override
    public void fetchSet(long maxRowCount, int[] keyColumnNumbers, BackingStoreHashtable hashTable) {
        ensureOpen();
    }

    @Override
    public ScanInfo getScanInfo() {
        ensureOpen();
        return null;
    }

    @Override
    public boolean isKeyed() {
        return false;
    }

    @Override
    public boolean isTableLocked() {
        return false;
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
            int stopSearchOperator) {
        ensureOpen();
        scan.close();
        current = null;
        try {
            openStorageScan();
        } catch (StandardException e) {
            throw new IllegalStateException("Could not reopen MVCC storage-api scan", e);
        }
        this.qualifiers = qualifier;
        resetOrderedIndexScan(qualifier);
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier) {
        ensureOpen();
        MvccRowLocation.from(startRowLocation);
        scan.close();
        current = null;
        try {
            openStorageScan();
        } catch (StandardException e) {
            throw new IllegalStateException("Could not reopen MVCC storage-api scan", e);
        }
        this.qualifiers = qualifier;
        resetOrderedIndexScan(qualifier);
    }

    private void openStorageScan() throws StandardException {
        pageBackedCommittedRead = false;
        if (canUseCurrentCommittedOptimization()) {
            try {
                scan = state.openCommittedImageScan(snapshot);
                pageBackedCommittedRead = true;
                MvccBridgeDiagnosticsSupport.incrementPageBackedCommittedScanCount();
                return;
            } catch (IllegalStateException staleCommittedImage) {
                // A commit advanced the current committed image after the
                // statement snapshot was captured. Fall back to the MVCC
                // version-chain scan, which can evaluate the captured snapshot.
            }
        }
        scan = state.openScan(snapshot);
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
    public boolean delete() {
        ensureOpen();
        if (current == null) {
            return false;
        }
        DelosStorageTransaction transaction = writer();
        DelosStorageSnapshot writeSnapshot = state.snapshot(transaction);
        if (state.read(current.rowId(), writeSnapshot).isEmpty()) {
            current = null;
            return false;
        }
        state.delete(current.rowId(), transaction, writeSnapshot);
        MvccBridgeDiagnosticsSupport.incrementDeleteCount();
        current = null;
        return true;
    }

    @Override
    public void didNotQualify() {
        ensureOpen();
    }

    @Override
    public boolean doesCurrentPositionQualify() {
        ensureOpen();
        return current != null;
    }

    @Override
    public boolean isHeldAfterCommit() {
        return hold;
    }

    @Override
    public void fetch(StoreDataValue[] destRow) throws StandardException {
        ensureOpen();
        if (current == null) {
            throw new IllegalStateException("MVCC scan is not positioned on a row");
        }
        copyCurrentRow(destRow, null);
    }

    @Override
    public void fetchWithoutQualify(StoreDataValue[] destRow) throws StandardException {
        fetch(destRow);
    }

    @Override
    public boolean fetchNext(StoreDataValue[] destRow) throws StandardException {
        ensureOpen();
        if (!advanceToNextQualifiedRow()) {
            return false;
        }
        copyCurrentRow(destRow, null);
        return true;
    }

    @Override
    public int fetchNextGroup(StoreDataValue[][] rowArray, StoreRowLocation[] rowlocArray) throws StandardException {
        ensureOpen();
        if (rowArray == null || rowArray.length == 0) {
            return 0;
        }
        int count = 0;
        while (count < rowArray.length && advanceToNextQualifiedRow()) {
            if (rowArray[count] == null) {
                rowArray[count] = newGroupFetchRowTemplate(rowArray);
            }
            MvccConglomerateController.copyRow(current.values(), rowArray[count], null);
            if (rowlocArray != null) {
                if (rowlocArray[count] == null) {
                    rowlocArray[count] = new MvccRowLocation();
                }
                MvccRowLocation.from(rowlocArray[count]).copyFrom(state.rowLocationFor(current.rowId()));
            }
            count++;
        }
        if (count == 0) {
            current = null;
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

    private StoreDataValue[] newGroupFetchRowTemplate(StoreDataValue[][] rowArray) throws StandardException {
        if (rowArray.length == 0 || rowArray[0] == null) {
            throw new IllegalStateException("MVCC bulk scan requires a non-null first row template");
        }
        return RowUtil.newRowFromTemplatePreservingArrayType(rowArray[0]);
    }

    @Override
    public void fetchLocation(StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation destination = MvccRowLocation.from(destRowLocation);
        if (current == null) {
            destination.restoreToNull();
        } else {
            destination.copyFrom(state.rowLocationFor(current.rowId()));
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
        return advanceToNextQualifiedRow();
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(rowLocation);
        Optional<StoreDataValue[]> visible = readCurrentCommittedOrSnapshot(location.rowId());
        if (visible.isEmpty()) {
            current = null;
            return false;
        }
        current = new DelosStorageRow(location.rowId(), visible.get());
        return true;
    }

    private boolean advanceToNextQualifiedRow() throws StandardException {
        if (orderedIndexRowIdScan) {
            return advanceToNextIndexedRow();
        }
        while (scan.next()) {
            DelosStorageRow candidate = scan.row();
            if (rowQualifies(candidate.values())) {
                current = candidate;
                return true;
            }
            MvccBridgeDiagnosticsSupport.incrementQualifierRejectCount();
        }
        current = null;
        return false;
    }

    private boolean advanceToNextIndexedRow() throws StandardException {
        while (orderedIndexRowIds != null && orderedIndexRowIds.hasNext()) {
            long rowId = orderedIndexRowIds.next();
            Optional<StoreDataValue[]> visible = readCurrentCommittedOrSnapshot(rowId);
            if (visible.isEmpty()) {
                MvccBridgeDiagnosticsSupport.incrementCandidateIndexVisibilityRejectCount();
                continue;
            }
            StoreDataValue[] row = visible.get();
            if (rowQualifies(row)) {
                current = new DelosStorageRow(rowId, row);
                return true;
            }
            MvccBridgeDiagnosticsSupport.incrementCandidateIndexQualifierRejectCount();
            MvccBridgeDiagnosticsSupport.incrementQualifierRejectCount();
        }
        current = null;
        return false;
    }

    private Optional<StoreDataValue[]> readCurrentCommittedOrSnapshot(long rowId) {
        if (pageBackedCommittedRead) {
            MvccBridgeDiagnosticsSupport.incrementRowIdFastPathReadCount();
            MvccBridgeDiagnosticsSupport.incrementPageBackedCommittedReadCount();
            Optional<StoreDataValue[]> visible = state.readCommittedImage(rowId, snapshot);
            if (visible.isPresent()) {
                MvccBridgeDiagnosticsSupport.incrementRowIdFastPathHitCount();
                return visible;
            }
        }
        return state.read(rowId, snapshot);
    }

    private void resetOrderedIndexScan(Qualifier[][] indexQualifiers) {
        if (!canUseCommittedOrderedIndex()) {
            if (shouldRecordOrderedIndexNonShortcut(indexQualifiers)) {
                state.recordOrderedIndexFallbackForDiagnostics(nonShortcutFallbackReason());
            }
            orderedIndexRowIdScan = false;
            orderedIndexRowIds = null;
            return;
        }
        Optional<List<Long>> indexedRowIds = state.orderedIndexRowIdsFor(indexQualifiers);
        if (indexedRowIds.isEmpty()) {
            orderedIndexRowIdScan = false;
            orderedIndexRowIds = null;
            return;
        }
        List<Long> rowIds = indexedRowIds.get();
        orderedIndexRowIdScan = true;
        orderedIndexRowIds = rowIds.iterator();
        // Keep the historical diagnostic counter names for existing gates, but
        // the normal row-id source here is the ordered MVCC index page store.
        MvccBridgeDiagnosticsSupport.incrementCandidateIndexLookupCount();
        MvccBridgeDiagnosticsSupport.addCandidateIndexRowIdCount(rowIds.size());
        DelosOptimizerPredicatePushdownDiagnostics.recordExecutionIfEnabledForTesting(
                "delos_mvcc",
                Math.toIntExact(state.key().getSegmentId()),
                state.key().getContainerId(),
                rowIds.size());
    }

    /**
     * The ordered MVCC index is rebuilt from the current committed visible
     * image. It is therefore a safe row-id narrowing authority only for
     * statement-scoped reads over the current committed image. Transaction-
     * scoped snapshots may still need rows whose current committed key was
     * updated or deleted after the snapshot was captured, and scans borrowing an
     * active writer must also see uncommitted same-transaction writes that are
     * not part of the committed ordered index. Those cases must fall back to
     * the full MVCC scan and let row-version visibility decide.
     */
    private boolean canUseCommittedOrderedIndex() {
        return pageBackedCommittedRead;
    }

    private boolean shouldRecordOrderedIndexNonShortcut(Qualifier[][] indexQualifiers) {
        return transactionScopedReader
                || readerBorrowedFromWriter
                || MvccConglomerateState.hasIndexQualifiers(indexQualifiers);
    }

    private DelosStorageOrderedIndexFallbackReason nonShortcutFallbackReason() {
        if (transactionScopedReader || readerBorrowedFromWriter) {
            return DelosStorageOrderedIndexFallbackReason.INTENTIONAL_NON_SHORTCUT_READ;
        }
        return DelosStorageOrderedIndexFallbackReason.FULL_COMMITTED_SCAN_FALLBACK;
    }

    private boolean canUseCurrentCommittedOptimization() {
        return !transactionScopedReader && !readerBorrowedFromWriter;
    }

    private boolean rowQualifies(StoreDataValue[] row) throws StandardException {
        if (qualifiers == null || qualifiers.length == 0) {
            return true;
        }
        return RowUtil.qualifyRow(row, qualifiers);
    }

    private void copyCurrentRow(StoreDataValue[] destRow, FormatableBitSet validColumns) throws StandardException {
        MvccConglomerateController.copyRow(current.values(), destRow, validColumns);
        copyCurrentRowLocation(destRow);
    }

    private void copyCurrentRowLocation(StoreDataValue[] destRow) {
        if (destRow == null || current == null || destRow.length <= current.values().length) {
            return;
        }
        StoreDataValue rowLocationColumn = destRow[current.values().length];
        if (rowLocationColumn instanceof StoreRowLocation rowLocation) {
            MvccRowLocation.from(rowLocation).copyFrom(state.rowLocationFor(current.rowId()));
        }
    }

    @Override
    public boolean replace(StoreDataValue[] row, FormatableBitSet validColumns) throws StandardException {
        ensureOpen();
        if (current == null) {
            return false;
        }
        DelosStorageTransaction transaction = writer();
        DelosStorageSnapshot writeSnapshot = state.snapshot(transaction);
        Optional<StoreDataValue[]> visible = state.read(current.rowId(), writeSnapshot);
        if (visible.isEmpty()) {
            current = null;
            return false;
        }
        StoreDataValue[] replacement = MvccConglomerateController.replacementRow(
                visible.get(),
                row,
                validColumns);
        state.update(current.rowId(), replacement, transaction, writeSnapshot);
        MvccBridgeDiagnosticsSupport.incrementUpdateCount();
        current = new DelosStorageRow(current.rowId(), replacement);
        return true;
    }

    private void closeReaderIfStatementScoped() {
        if (readerBorrowedFromWriter) {
            return;
        }
        if (!transactionScopedReader) {
            state.abort(reader);
        }
    }

    private static boolean usesTransactionScopedSnapshot(int isolationLevel) {
        return isolationLevel == TransactionController.ISOLATION_REPEATABLE_READ
                || isolationLevel == TransactionController.ISOLATION_SERIALIZABLE;
    }

    private DelosStorageTransaction writer() {
        if (writer == null) {
            if (completeWithDerbyTransaction) {
                DelosStorageTransaction activeWriter = DelosStorageTransactionRegistry.activeWriterTransaction(
                        transactionManager,
                        state.table());
                if (activeWriter != null) {
                    writer = activeWriter;
                    writerBorrowedFromRegistry = true;
                    return writer;
                }
            }
            writer = state.beginTransaction();
            if (completeWithDerbyTransaction) {
                registeredWriter = MvccStoreAccessTransactionRegistry.register(
                        transactionManager,
                        state.table(),
                        writer);
            }
        }
        return writer;
    }

    private void commitWriterIfActive() {
        if (writer != null) {
            if (writerBorrowedFromRegistry) {
                writer = null;
                writerBorrowedFromRegistry = false;
                return;
            }
            if (registeredWriter != null) {
                registeredWriter.commit();
                MvccStoreAccessTransactionRegistry.complete(registeredWriter);
                registeredWriter = null;
            } else {
                state.commit(writer);
            }
            writer = null;
        }
    }

    private void abortWriterIfActive() {
        if (writer != null) {
            if (writerBorrowedFromRegistry) {
                writer = null;
                writerBorrowedFromRegistry = false;
                return;
            }
            if (registeredWriter != null) {
                registeredWriter.abort();
                MvccStoreAccessTransactionRegistry.complete(registeredWriter);
                registeredWriter = null;
            } else {
                state.abort(writer);
            }
            writer = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC scan controller is closed");
        }
    }

}
