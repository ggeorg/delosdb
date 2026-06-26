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

import java.util.Optional;
import java.util.Properties;

import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.store.types.StoreValueOperations;
import org.apache.derby.shared.common.error.StandardException;

/**
 * MODULE6D inherited ConglomerateController preflight for Delos MVCC.
 *
 * <p>The controller writes through the MVCC visibility kernel while staying
 * below SQL execution. A normal close aborts the controller-local writer; the
 * inherited end-transaction close commits it. This is intentionally only a
 * direct store/access proof and not final Derby transaction integration.</p>
 */
public final class MvccConglomerateController implements ConglomerateController {
    private final MvccConglomerate conglomerate;
    private final MvccConglomerateState state;
    private boolean closed;
    private MvccTransaction writer;

    MvccConglomerateController(MvccConglomerate conglomerate) {
        this.conglomerate = conglomerate;
        this.state = conglomerate.state();
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        abortWriterIfActive();
        closed = true;
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        commitWriterIfActive();
        closed = true;
        return true;
    }

    @Override
    public void checkConsistency() {
    }

    @Override
    public boolean delete(StoreRowLocation loc) {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(loc);
        MvccTransaction transaction = writer();
        MvccSnapshot snapshot = state.transactions().snapshot(transaction);
        state.table().delete(location.rowId(), transaction, snapshot, state.transactions());
        return true;
    }

    @Override
    public boolean fetch(StoreRowLocation loc, StoreDataValue[] destRow, FormatableBitSet validColumns)
            throws StandardException {
        ensureOpen();
        MvccRowLocation location = MvccRowLocation.from(loc);
        MvccTransaction reader = state.transactions().begin();
        try {
            Optional<StoreDataValue[]> visible = state.table().read(
                    location.rowId(),
                    state.transactions().snapshot(reader),
                    state.transactions());
            if (visible.isEmpty()) {
                return false;
            }
            copyRow(visible.get(), destRow, validColumns);
            return true;
        } finally {
            state.transactions().abort(reader);
        }
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
        MvccTransaction transaction = writer();
        MvccSnapshot snapshot = state.transactions().snapshot(transaction);
        state.table().update(location.rowId(), cloneRow(row), transaction, snapshot, state.transactions());
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
        state.table().insert(rowId, cloneRow(row), writer());
        if (destination != null) {
            destination.set(rowId, 0L, -1);
        }
    }

    private MvccTransaction writer() {
        if (writer == null) {
            writer = state.transactions().begin();
        }
        return writer;
    }

    private void commitWriterIfActive() {
        if (writer != null) {
            state.transactions().commit(writer);
            writer = null;
        }
    }

    private void abortWriterIfActive() {
        if (writer != null) {
            state.transactions().abort(writer);
            writer = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC conglomerate controller is closed");
        }
    }

    static StoreDataValue[] cloneRow(StoreDataValue[] row) throws StandardException {
        if (row == null) {
            return new StoreDataValue[0];
        }
        StoreDataValue[] copy = new StoreDataValue[row.length];
        for (int i = 0; i < row.length; i++) {
            copy[i] = cloneValue(row[i]);
        }
        return copy;
    }

    static void copyRow(StoreDataValue[] source, StoreDataValue[] destination, FormatableBitSet validColumns)
            throws StandardException {
        if (destination == null) {
            return;
        }
        int sourceColumn = 0;
        for (int i = 0; i < destination.length && i < source.length; i++) {
            if (validColumns != null && !validColumns.isSet(i)) {
                continue;
            }
            StoreDataValue value = source[sourceColumn++];
            if (destination[i] instanceof StoreValueOperations destinationValue) {
                destinationValue.setValue(value);
            } else {
                destination[i] = cloneValue(value);
            }
        }
    }

    private static StoreDataValue cloneValue(StoreDataValue value) throws StandardException {
        if (value == null) {
            return null;
        }
        if (value instanceof StoreValueOperations operations) {
            return operations.cloneValue(false);
        }
        throw new IllegalArgumentException("MVCC store/access preflight requires cloneable StoreDataValue: "
                + value.getClass().getName());
    }
}
