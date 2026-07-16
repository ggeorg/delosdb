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
import org.apache.derby.iapi.store.types.DelosStorageAccessDecisionKind;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Derby-compatible scan controller for {@code delos_mvcc} tables.
 *
 * <p>The scan opens the appropriate MVCC read view and returns visible rows
 * through Derby's inherited {@code ScanController} and result-set path.</p>
 */
public final class MvccScanController implements ScanManager {
    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private final TransactionManager transactionManager;
    private final boolean hold;
    private final boolean completeWithDerbyTransaction;
    private final MvccBridgeIsolationPolicy isolationPolicy;
    private final boolean transactionScopedReader;
    private final boolean readerBorrowedFromWriter;
    private final DelosStorageTransaction reader;
    private final DelosStorageSnapshot snapshot;
    private boolean statementReaderClosed;
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
    private long rowsVisited;
    private long rowsQualified;

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
        this.isolationPolicy = MvccBridgeIsolationPolicy.fromDerbyIsolationLevel(isolationLevel);
        this.scanColumnList = scanColumnList;
        this.qualifiers = qualifiers;
        DelosStorageTransaction activeWriter = DelosStorageTransactionRegistry.activeWriterTransaction(
                transactionManager,
                state.table());
        this.transactionScopedReader = isolationPolicy.usesTransactionScopedSnapshot();
        ReaderContext readerContext = openReaderContext(activeWriter);
        this.readerBorrowedFromWriter = readerContext.borrowedFromWriter();
        this.reader = readerContext.transaction();
        this.snapshot = readerContext.snapshot();
        try {
            openPreferredStorageAccess(qualifiers);
        } catch (StandardException e) {
            cleanupFailedConstruction(e);
            throw new IllegalStateException("Could not open MVCC storage-api scan", e);
        } catch (RuntimeException e) {
            cleanupFailedConstruction(e);
            throw e;
        } catch (Error e) {
            cleanupFailedConstruction(e);
            throw e;
        }
    }


    private ReaderContext openReaderContext(DelosStorageTransaction activeWriter) {
        if (activeWriter != null) {
            if (transactionScopedReader) {
                DelosStorageTransactionRegistry.Reader transactionReader =
                        DelosStorageTransactionRegistry.reader(transactionManager, state.table());
                return new ReaderContext(
                        true,
                        activeWriter,
                        state.snapshot(activeWriter, transactionReader.snapshot()));
            }
            return new ReaderContext(true, activeWriter, state.snapshot(activeWriter));
        }

        if (transactionScopedReader) {
            DelosStorageTransactionRegistry.Reader transactionReader =
                    DelosStorageTransactionRegistry.reader(transactionManager, state.table());
            return new ReaderContext(
                    false,
                    transactionReader.transaction(),
                    transactionReader.snapshot());
        }

        DelosStorageTransaction statementReader = state.beginReadOnlyTransaction();
        try {
            return new ReaderContext(false, statementReader, state.snapshot(statementReader));
        } catch (RuntimeException | Error snapshotFailure) {
            try {
                state.abort(statementReader);
            } catch (RuntimeException | Error abortFailure) {
                snapshotFailure.addSuppressed(abortFailure);
            }
            throw snapshotFailure;
        }
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        if (!closed) {
            closeOwnedResources(false);
        }
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        if (!hold || closeHeldScan) {
            if (!closed) {
                closeOwnedResources(true);
            }
            return true;
        }
        return false;
    }

    @Override
    public void fetchSet(long maxRowCount, int[] keyColumnNumbers, BackingStoreHashtable hashTable)
            throws StandardException {
        ensureOpen();
        if (hashTable == null) {
            throw new IllegalArgumentException("hashTable must not be null");
        }
        long fetchedRowCount = 0L;
        while ((maxRowCount < 0L || fetchedRowCount < maxRowCount) && advanceToNextQualifiedRow()) {
            StoreDataValue[] row = current.values();
            StoreRowLocation rowLocation = new MvccRowLocation();
            MvccRowLocation.from(rowLocation).copyFrom(state.rowLocationFor(current.rowId()));
            hashTable.putRow(true, row, rowLocation);
            fetchedRowCount++;
        }
        while (advanceToNextQualifiedRow()) {
            // fetchSet must leave the scan exhausted even when maxRowCount is reached.
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
        closeStorageScan();
        current = null;
        this.qualifiers = qualifier;
        try {
            openPreferredStorageAccess(qualifier);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not reopen MVCC storage-api scan", e);
        }
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier) {
        ensureOpen();
        MvccRowLocation.from(startRowLocation);
        closeStorageScan();
        current = null;
        this.qualifiers = qualifier;
        try {
            openPreferredStorageAccess(qualifier);
        } catch (StandardException e) {
            throw new IllegalStateException("Could not reopen MVCC storage-api scan", e);
        }
    }

    /**
     * Select the narrowest safe current-committed read path before opening a
     * full committed-image scan.
     *
     * <p>Previously every scan eagerly decoded the complete committed image
     * before the ordered index was consulted. Equality and bounded-range
     * lookups then discarded that materialized scan and fetched only the
     * indexed row ids. Selecting the ordered path first preserves the same
     * current-committed authority rules while avoiding unused full-image
     * construction.</p>
     */
    private void openPreferredStorageAccess(Qualifier[][] indexQualifiers) throws StandardException {
        pageBackedCommittedRead = false;
        orderedIndexRowIdScan = false;
        orderedIndexRowIds = null;
        if (resetOrderedIndexScan(indexQualifiers)) {
            return;
        }
        openStorageScan();
    }

    private void openStorageScan() throws StandardException {
        pageBackedCommittedRead = false;
        if (canUseCurrentCommittedOptimization()) {
            try {
                scan = state.openCommittedImageScan(snapshot);
                pageBackedCommittedRead = true;
                MvccBridgeDiagnosticsSupport.incrementPageBackedCommittedScanCount();
                recordChosenStoragePath(
                        DelosStorageAccessDecisionKind.MVCC_FULL_SCAN,
                        "current-committed page-backed image scan selected",
                        true,
                        DelosStoragePathDiagnostic.UNKNOWN_ROW_ID_COUNT,
                        List.of("pageBackedCommittedRead=true"));
                return;
            } catch (IllegalStateException staleCommittedImage) {
                // A commit advanced the current committed image after the
                // statement snapshot was captured. Fall back to the MVCC
                // version-chain scan, which can evaluate the captured snapshot.
                recordStoragePathFallback(
                        "current-committed image was stale; falling back to MVCC version-chain scan",
                        List.of("exception=" + staleCommittedImage.getClass().getSimpleName()));
            }
        }
        scan = state.openScan(snapshot);
        recordChosenStoragePath(
                DelosStorageAccessDecisionKind.MVCC_FULL_SCAN,
                "MVCC version-chain full scan selected",
                false,
                DelosStoragePathDiagnostic.UNKNOWN_ROW_ID_COUNT,
                List.of("pageBackedCommittedRead=false"));
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
            rowsVisited++;
            if (rowQualifies(candidate.values())) {
                rowsQualified++;
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
            rowsVisited++;
            Optional<StoreDataValue[]> visible = readCurrentCommittedOrSnapshot(rowId);
            if (visible.isEmpty()) {
                MvccBridgeDiagnosticsSupport.incrementCandidateIndexVisibilityRejectCount();
                continue;
            }
            StoreDataValue[] row = visible.get();
            if (rowQualifies(row)) {
                rowsQualified++;
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
            recordChosenStoragePath(
                    DelosStorageAccessDecisionKind.MVCC_ROW_ID_LOOKUP,
                    visible.isPresent()
                            ? "current-committed row-id lookup returned a visible row"
                            : "current-committed row-id lookup missed and will check MVCC visibility",
                    true,
                    1L,
                    List.of("rowId=" + rowId, "hit=" + visible.isPresent()));
            if (visible.isPresent()) {
                MvccBridgeDiagnosticsSupport.incrementRowIdFastPathHitCount();
                return visible;
            }
            recordStoragePathFallback(
                    "row-id fast path miss; MVCC version-chain visibility remains authority",
                    List.of("rowId=" + rowId));
        }
        return state.read(rowId, snapshot);
    }

    private boolean resetOrderedIndexScan(Qualifier[][] indexQualifiers) {
        if (!canUseCommittedOrderedIndex()) {
            if (shouldRecordOrderedIndexNonShortcut(indexQualifiers)) {
                state.recordOrderedIndexFallbackForDiagnostics(nonShortcutFallbackReason());
                recordStoragePathFallback(
                        "ordered MVCC index shortcut rejected because the read is not current-committed",
                        List.of("fallbackReason=" + nonShortcutFallbackReason()));
            }
            orderedIndexRowIdScan = false;
            orderedIndexRowIds = null;
            return false;
        }
        Optional<List<Long>> indexedRowIds = state.orderedIndexRowIdsFor(indexQualifiers, snapshot);
        if (indexedRowIds.isEmpty()) {
            if (MvccConglomerateState.hasIndexQualifiers(indexQualifiers)) {
                DelosStorageAccessDecisionKind rejectedKind = rejectedOrderedIndexDecisionKind(indexQualifiers);
                recordRejectedStoragePath(
                        rejectedKind,
                        "ordered MVCC index could not derive a single supported typed key",
                        List.of("fallbackReason=" + DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE));
                recordStoragePathFallback(
                        "ordered MVCC index could not derive a supported typed key; full scan remains authority",
                        List.of("fallbackReason=" + DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE,
                                "rejectedStoragePath=" + rejectedKind));
            }
            orderedIndexRowIdScan = false;
            orderedIndexRowIds = null;
            return false;
        }
        pageBackedCommittedRead = true;
        List<Long> rowIds = indexedRowIds.get();
        orderedIndexRowIdScan = true;
        orderedIndexRowIds = rowIds.iterator();
        DelosStorageAccessDecisionKind decisionKind = orderedIndexDecisionKind(indexQualifiers);
        recordChosenStoragePath(
                decisionKind,
                "ordered MVCC index page lookup selected row-id narrowing",
                true,
                rowIds.size(),
                List.of("orderedIndexRowIdScan=true", "rowIds=" + rowIds.size()));
        // Keep the historical diagnostic counter names for existing gates, but
        // the normal row-id source here is the ordered MVCC index page store.
        MvccBridgeDiagnosticsSupport.incrementCandidateIndexLookupCount();
        MvccBridgeDiagnosticsSupport.addCandidateIndexRowIdCount(rowIds.size());
        DelosOptimizerPredicatePushdownDiagnostics.recordExecutionIfEnabledForTesting(
                "delos_mvcc",
                Math.toIntExact(state.key().getSegmentId()),
                state.key().getContainerId(),
                rowIds.size());
        return true;
    }

    private DelosStorageAccessDecisionKind orderedIndexDecisionKind(Qualifier[][] indexQualifiers) {
        if (containsEqualityQualifier(indexQualifiers)) {
            return DelosStorageAccessDecisionKind.MVCC_ORDERED_EQUALITY_LOOKUP;
        }
        return DelosStorageAccessDecisionKind.MVCC_ORDERED_RANGE_SCAN;
    }

    private DelosStorageAccessDecisionKind rejectedOrderedIndexDecisionKind(Qualifier[][] indexQualifiers) {
        if (containsEqualityQualifier(indexQualifiers)) {
            return DelosStorageAccessDecisionKind.MVCC_ORDERED_EQUALITY_LOOKUP;
        }
        return DelosStorageAccessDecisionKind.MVCC_ORDERED_RANGE_SCAN;
    }

    private static boolean containsEqualityQualifier(Qualifier[][] indexQualifiers) {
        if (indexQualifiers == null) {
            return false;
        }
        for (Qualifier[] andTerm : indexQualifiers) {
            if (andTerm == null) {
                continue;
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier != null
                        && qualifier.getOperator() == org.apache.derby.iapi.store.types.StoreOrderable.ORDER_OP_EQUALS
                        && !qualifier.negateCompareResult()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void recordChosenStoragePath(
            DelosStorageAccessDecisionKind decisionKind,
            String reason,
            boolean shortcutSafe,
            long rowIdCount,
            List<String> details) {
        MvccBridgeDiagnosticsSupport.recordStoragePathDiagnostic(
                DelosStoragePathDiagnostic.chosen(
                        decisionKind,
                        "delos_mvcc",
                        Math.toIntExact(state.key().getSegmentId()),
                        state.key().getContainerId(),
                        reason,
                        storagePathReadMode(),
                        shortcutSafe,
                        rowIdCount,
                        details));
    }

    private void recordRejectedStoragePath(
            DelosStorageAccessDecisionKind decisionKind,
            String reason,
            List<String> details) {
        MvccBridgeDiagnosticsSupport.recordStoragePathDiagnostic(
                DelosStoragePathDiagnostic.rejected(
                        decisionKind,
                        "delos_mvcc",
                        Math.toIntExact(state.key().getSegmentId()),
                        state.key().getContainerId(),
                        reason,
                        storagePathReadMode(),
                        details));
    }

    private void recordStoragePathFallback(String reason, List<String> details) {
        MvccBridgeDiagnosticsSupport.recordStoragePathDiagnostic(
                DelosStoragePathDiagnostic.fallback(
                        "delos_mvcc",
                        Math.toIntExact(state.key().getSegmentId()),
                        state.key().getContainerId(),
                        reason,
                        storagePathReadMode(),
                        details));
    }

    private String storagePathReadMode() {
        if (readerBorrowedFromWriter) {
            return "writer-borrowed";
        }
        if (transactionScopedReader) {
            return "transaction-scoped-snapshot";
        }
        if (pageBackedCommittedRead) {
            return "current-committed";
        }
        return "statement-scoped";
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
        return canUseCurrentCommittedOptimization() && state.canReadCommittedImage(snapshot);
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

    private void closeStorageScan() {
        if (scan != null) {
            scan.close();
            scan = null;
        }
    }

    private void cleanupFailedConstruction(Throwable failure) {
        try {
            closeStorageScan();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            closeReaderIfStatementScoped();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void closeReaderIfStatementScoped() {
        if (readerBorrowedFromWriter || transactionScopedReader || statementReaderClosed) {
            return;
        }
        state.abort(reader);
        statementReaderClosed = true;
    }

    private void closeOwnedResources(boolean endTransaction) {
        Throwable failure = null;
        failure = attemptCleanup(failure, this::closeStorageScan);
        failure = attemptCleanup(failure, this::closeReaderIfStatementScoped);
        if (endTransaction) {
            failure = attemptCleanup(failure, this::commitWriterIfActive);
        } else if (!completeWithDerbyTransaction) {
            failure = attemptCleanup(failure, this::abortWriterIfActive);
        }
        if (failure == null) {
            failure = attemptCleanup(failure, () -> transactionManager.closeMe(this));
        }
        if (failure == null) {
            closed = true;
            return;
        }
        rethrowCleanupFailure(failure);
    }

    private static Throwable attemptCleanup(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
            return failure;
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure == null) {
                return cleanupFailure;
            }
            failure.addSuppressed(cleanupFailure);
            return failure;
        }
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
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

    private record ReaderContext(
            boolean borrowedFromWriter,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        private ReaderContext {
            transaction = java.util.Objects.requireNonNull(transaction, "transaction");
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC scan controller is closed");
        }
    }

}
