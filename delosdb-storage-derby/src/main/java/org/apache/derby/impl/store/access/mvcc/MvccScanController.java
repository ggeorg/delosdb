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

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.BackingStoreHashtable;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanInfo;
import org.apache.derby.iapi.store.access.conglomerate.ScanManager;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/** MODULE6C inherited ScanManager skeleton for Delos MVCC. */
public final class MvccScanController implements ScanManager {
    private final MvccConglomerate conglomerate;
    private final boolean hold;
    private boolean closed;
    private long estimatedRowCount;

    MvccScanController(MvccConglomerate conglomerate, boolean hold) {
        this.conglomerate = conglomerate;
        this.hold = hold;
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
    }

    @Override
    public void reopenScanByRowLocation(StoreRowLocation startRowLocation, Qualifier[][] qualifier) {
        ensureOpen();
        MvccRowLocation.from(startRowLocation);
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
        return false;
    }

    @Override
    public boolean isHeldAfterCommit() {
        return hold;
    }

    @Override
    public void fetch(StoreDataValue[] destRow) {
        ensureOpen();
    }

    @Override
    public void fetchWithoutQualify(StoreDataValue[] destRow) {
        ensureOpen();
    }

    @Override
    public boolean fetchNext(StoreDataValue[] destRow) {
        ensureOpen();
        return false;
    }

    @Override
    public int fetchNextGroup(StoreDataValue[][] rowArray, StoreRowLocation[] rowlocArray) {
        ensureOpen();
        return 0;
    }

    @Override
    public int fetchNextGroup(
            StoreDataValue[][] rowArray,
            StoreRowLocation[] oldrowlocArray,
            StoreRowLocation[] newrowlocArray) {
        ensureOpen();
        return 0;
    }

    @Override
    public void fetchLocation(StoreRowLocation destRowLocation) {
        ensureOpen();
        MvccRowLocation.from(destRowLocation).restoreToNull();
    }

    @Override
    public boolean isCurrentPositionDeleted() {
        ensureOpen();
        return false;
    }

    @Override
    public boolean next() {
        ensureOpen();
        return false;
    }

    @Override
    public boolean positionAtRowLocation(StoreRowLocation rowLocation) {
        ensureOpen();
        MvccRowLocation.from(rowLocation);
        return false;
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
