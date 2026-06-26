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

import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/** MODULE6C inherited ConglomerateController skeleton for Delos MVCC. */
public final class MvccConglomerateController implements ConglomerateController {
    private final MvccConglomerate conglomerate;
    private boolean closed;
    private long nextSyntheticRowId = 1L;

    MvccConglomerateController(MvccConglomerate conglomerate) {
        this.conglomerate = conglomerate;
    }

    public MvccConglomerate conglomerate() {
        return conglomerate;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean closeForEndTransaction(boolean closeHeldScan) {
        close();
        return true;
    }

    @Override
    public void checkConsistency() {
    }

    @Override
    public boolean delete(StoreRowLocation loc) {
        ensureOpen();
        MvccRowLocation.from(loc);
        return false;
    }

    @Override
    public boolean fetch(StoreRowLocation loc, StoreDataValue[] destRow, FormatableBitSet validColumns) {
        ensureOpen();
        MvccRowLocation.from(loc);
        return false;
    }

    @Override
    public boolean fetch(
            StoreRowLocation loc,
            StoreDataValue[] destRow,
            FormatableBitSet validColumns,
            boolean waitForLock) {
        return fetch(loc, destRow, validColumns);
    }

    @Override
    public int insert(StoreDataValue[] row) {
        ensureOpen();
        nextSyntheticRowId++;
        return 0;
    }

    @Override
    public void insertAndFetchLocation(StoreDataValue[] row, StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation.from(destRowLocation).set(nextSyntheticRowId++, 0L, -1);
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
    public boolean replace(StoreRowLocation loc, StoreDataValue[] row, FormatableBitSet validColumns) {
        ensureOpen();
        MvccRowLocation.from(loc);
        return false;
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC conglomerate controller is closed");
        }
    }
}
