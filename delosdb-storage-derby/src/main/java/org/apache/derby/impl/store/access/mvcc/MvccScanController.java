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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.BackingStoreHashtable;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
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
    private static final AtomicInteger OPEN_COUNT = new AtomicInteger();

    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private final TransactionManager transactionManager;
    private final boolean hold;
    private final MvccTransaction reader;
    private final MvccSnapshot snapshot;
    private MvccScan<Long, StoreDataValue[]> scan;
    private MvccRow<Long, StoreDataValue[]> current;
    private boolean closed;
    private long estimatedRowCount;

    MvccScanController(MvccConglomerate conglomerate, TransactionManager transactionManager, boolean hold) {
        OPEN_COUNT.incrementAndGet();
        this.conglomerate = conglomerate;
        this.state = conglomerate.state();
        this.transactionManager = transactionManager;
        this.hold = hold;
        this.reader = state.transactions().begin();
        this.snapshot = state.transactions().snapshot(reader);
        this.scan = state.table().openScan(snapshot, state.transactions());
    }


    public static void resetOpenCountForTesting() {
        OPEN_COUNT.set(0);
    }

    public static int openCountForTesting() {
        return OPEN_COUNT.get();
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        if (!closed) {
            scan.close();
            state.transactions().abort(reader);
            closed = true;
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
        scan = state.table().openScan(snapshot, state.transactions());
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier) {
        ensureOpen();
        MvccRowLocation.from(startRowLocation);
        scan.close();
        current = null;
        scan = state.table().openScan(snapshot, state.transactions());
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
        return false;
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
        if (!scan.next()) {
            current = null;
            return false;
        }
        current = scan.row();
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
        while (count < rowArray.length && scan.next()) {
            current = scan.row();
            MvccConglomerateController.copyRow(current.value(), rowArray[count], null);
            if (rowlocArray != null) {
                if (rowlocArray[count] == null) {
                    rowlocArray[count] = new MvccRowLocation();
                }
                MvccRowLocation.from(rowlocArray[count]).set(current.key(), 0L, -1);
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

    @Override
    public void fetchLocation(StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation destination = MvccRowLocation.from(destRowLocation);
        if (current == null) {
            destination.restoreToNull();
        } else {
            destination.set(current.key(), 0L, -1);
        }
    }

    @Override
    public boolean isCurrentPositionDeleted() {
        ensureOpen();
        return false;
    }

    @Override
    public boolean next() {
        ensureOpen();
        if (!scan.next()) {
            current = null;
            return false;
        }
        current = scan.row();
        return true;
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(rowLocation);
        Optional<StoreDataValue[]> visible = state.table().read(location.rowId(), snapshot, state.transactions());
        if (visible.isEmpty()) {
            current = null;
            return false;
        }
        current = new MvccRow<>(location.rowId(), visible.get());
        return true;
    }

    private void copyCurrentRow(StoreDataValue[] destRow, FormatableBitSet validColumns) throws StandardException {
        MvccConglomerateController.copyRow(current.value(), destRow, validColumns);
        copyCurrentRowLocation(destRow);
    }

    private void copyCurrentRowLocation(StoreDataValue[] destRow) {
        if (destRow == null || current == null || destRow.length <= current.value().length) {
            return;
        }
        StoreDataValue rowLocationColumn = destRow[current.value().length];
        if (rowLocationColumn instanceof StoreRowLocation rowLocation) {
            MvccRowLocation.from(rowLocation).set(current.key(), 0L, -1);
        }
    }

    @Override
    public boolean replace(StoreDataValue[] row, FormatableBitSet validColumns) {
        ensureOpen();
        return false;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC scan controller is closed");
        }
    }
}
