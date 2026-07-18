/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateController

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

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.types.DelosStorageAccessDecisionKind;
import org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Derby-compatible conglomerate controller for {@code delos_mvcc} tables.
 *
 * <p>The controller writes through the MVCC visibility kernel while staying
 * below SQL execution. A normal close aborts the controller-local writer; the
 * inherited end-transaction close commits it. This is intentionally only a
 * direct store/access proof and not final Derby transaction integration.</p>
 */
public final class MvccConglomerateController implements ConglomerateController {
    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private final TransactionManager transactionManager;
    private final boolean completeWithDerbyTransaction;
    private boolean closed;
    private DelosStorageTransaction writer;
    private MvccStoreAccessTransactionRegistry.Writer registeredWriter;
    private boolean writerBorrowedFromRegistry;

    MvccConglomerateController(
            MvccConglomerate conglomerate,
            TransactionManager transactionManager,
            int openMode) {
        this.conglomerate = conglomerate;
        this.state = conglomerate.state();
        this.transactionManager = transactionManager;
        this.completeWithDerbyTransaction = (openMode & org.apache.derby.iapi.store.access.TransactionController.OPENMODE_FORUPDATE)
                == org.apache.derby.iapi.store.access.TransactionController.OPENMODE_FORUPDATE;
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        if (!closed) {
            if (!completeWithDerbyTransaction) {
                abortWriterIfActive();
            }
            closed = true;
            transactionManager.closeMe(this);
        }
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        if (!closed) {
            commitWriterIfActive();
            closed = true;
            transactionManager.closeMe(this);
        }
        return true;
    }

    @Override
    public void checkConsistency() {
    }

    @Override
    public boolean delete(StoreRowLocation loc) {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(loc);
        DelosStorageTransaction transaction = writer();
        DelosStorageSnapshot snapshot = state.snapshot(transaction);
        state.delete(location.rowId(), transaction, snapshot);
        state.databaseDiagnostics().incrementDeleteCount();
        return true;
    }

    @Override
    public boolean fetch(StoreRowLocation loc, StoreDataValue[] destRow, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(loc);
        DelosStorageTransaction activeWriter = DelosStorageTransactionRegistry.activeWriterTransaction(
                transactionManager,
                state.table());
        DelosStorageTransaction reader = activeWriter == null
                ? state.beginReadOnlyTransaction()
                : activeWriter;
        try {
            DelosStorageSnapshot snapshot = state.snapshot(reader);
            Optional<StoreDataValue[]> visible = readByRowIdFastPathOrSnapshot(
                    location.rowId(),
                    snapshot,
                    activeWriter == null);
            if (visible.isEmpty()) {
                return false;
            }
            copyRow(visible.get(), destRow, validColumns);
            return true;
        } finally {
            if (activeWriter == null) {
                state.abort(reader);
            }
        }
    }

    private Optional<StoreDataValue[]> readByRowIdFastPathOrSnapshot(
            long rowId,
            DelosStorageSnapshot snapshot,
            boolean statementScopedReader) {
        if (statementScopedReader && state.canReadCommittedImage(snapshot)) {
            state.databaseDiagnostics().incrementRowIdFastPathReadCount();
            state.databaseDiagnostics().incrementPageBackedCommittedReadCount();
            Optional<StoreDataValue[]> visible = state.readCommittedImage(rowId, snapshot);
            recordRowIdStoragePath(rowId, visible.isPresent());
            if (visible.isPresent()) {
                state.databaseDiagnostics().incrementRowIdFastPathHitCount();
                return visible;
            }
            recordRowIdStoragePathFallback(rowId);
        }
        return state.read(rowId, snapshot);
    }

    private void recordRowIdStoragePath(long rowId, boolean hit) {
        state.databaseDiagnostics().recordStoragePathDiagnostic(
                DelosStoragePathDiagnostic.chosen(
                        DelosStorageAccessDecisionKind.MVCC_ROW_ID_LOOKUP,
                        "delos_mvcc",
                        Math.toIntExact(state.key().getSegmentId()),
                        state.key().getContainerId(),
                        hit
                                ? "current-committed row-id point read returned a visible row"
                                : "current-committed row-id point read missed and will check MVCC visibility",
                        "current-committed",
                        true,
                        1L,
                        List.of("rowId=" + rowId, "hit=" + hit)));
    }

    private void recordRowIdStoragePathFallback(long rowId) {
        state.databaseDiagnostics().recordStoragePathDiagnostic(
                DelosStoragePathDiagnostic.fallback(
                        "delos_mvcc",
                        Math.toIntExact(state.key().getSegmentId()),
                        state.key().getContainerId(),
                        "row-id point read missed; MVCC version-chain visibility remains authority",
                        "current-committed",
                        List.of("rowId=" + rowId)));
    }

    @Override
    public boolean fetch(
            StoreRowLocation loc,
            StoreDataValue[] destRow,
            FormatableBitSet validColumns,
            boolean waitForLock) throws StandardException {
        return fetch(loc, destRow, validColumns);
    }

    @Override
    public int insert(StoreDataValue[] row) throws StandardException {
        ensureOpen();
        insertInternal(row, null);
        return 0;
    }

    @Override
    public void insertAndFetchLocation(StoreDataValue[] row, StoreRowLocation destRowLocation)
            throws StandardException {
        ensureOpen();
        insertInternal(row, MvccRowLocation.from(destRowLocation));
    }

    @Override
    public boolean isKeyed() {
        return false;
    }

    @Override
    public boolean lockRow(StoreRowLocation loc, int lockOper, boolean wait, int lockDuration) {
        ensureOpen();
        MvccRowLocation.from(loc);
        return true;
    }

    @Override
    public boolean lockRow(long pageNum, int recordId, int lockOper, boolean wait, int lockDuration) {
        ensureOpen();
        return true;
    }

    /**
     * MVCC visibility does not use Derby base-row locks, so a successful lock
     * probe cannot prove that a secondary-index delete has committed.
     */
    @Override
    public boolean supportsLockBasedCommittedDeleteReclamation() {
        return false;
    }

    @Override
    public void unlockRowAfterRead(StoreRowLocation loc, boolean forUpdate, boolean rowQualified) {
        ensureOpen();
    }

    @Override
    public StoreRowLocation newRowLocationTemplate() {
        ensureOpen();
        return new MvccRowLocation();
    }

    @Override
    public boolean replace(StoreRowLocation loc, StoreDataValue[] row, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(loc);
        DelosStorageTransaction transaction = writer();
        DelosStorageSnapshot snapshot = state.snapshot(transaction);
        Optional<StoreDataValue[]> visible = state.read(location.rowId(), snapshot);
        if (visible.isEmpty()) {
            return false;
        }
        state.update(
                location.rowId(),
                replacementRow(visible.get(), row, validColumns),
                transaction,
                snapshot);
        state.databaseDiagnostics().incrementUpdateCount();
        return true;
    }

    @Override
    public SpaceInfo getSpaceInfo() {
        ensureOpen();
        return null;
    }

    @Override
    public void debugConglomerate() {
    }

    @Override
    public void getTableProperties(Properties prop) {
    }

    @Override
    public Properties getInternalTablePropertySet(Properties prop) {
        return prop == null ? new Properties() : prop;
    }

    private void insertInternal(StoreDataValue[] row, MvccRowLocation destination) throws StandardException {
        long rowId = state.nextRowId();
        state.insert(rowId, cloneRow(row), writer());
        state.databaseDiagnostics().incrementInsertCount();
        if (destination != null) {
            destination.set(rowId, 0L, -1);
        }
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

    static StoreDataValue[] cloneRow(StoreDataValue[] row) throws StandardException {
        return StoreValueCopySupport.cloneRow(row);
    }

    static StoreDataValue[] replacementRow(
            StoreDataValue[] current,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns) throws StandardException {
        return StoreValueCopySupport.replacementRow(current, replacement, validColumns);
    }

    static void copyRow(StoreDataValue[] source, StoreDataValue[] destination, FormatableBitSet validColumns)
            throws StandardException {
        StoreValueCopySupport.copyRow(source, destination, validColumns);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC conglomerate controller is closed");
        }
    }
}

