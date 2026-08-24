/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccStoreCostController

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
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.iapi.store.access.StoreCostResult;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/** Conservative optimizer cost model for RawStore-backed MVCC scans. */
final class MvccStoreCostController implements StoreCostController {
    private final MvccConglomerate conglomerate;
    private final Transaction rawTransaction;
    private final MvccRawStoreTable.Descriptor table;
    private long estimatedRowCount;
    private boolean closed;

    MvccStoreCostController(
            MvccConglomerate conglomerate,
            Transaction rawTransaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        this.conglomerate = java.util.Objects.requireNonNull(conglomerate, "conglomerate");
        this.rawTransaction = java.util.Objects.requireNonNull(rawTransaction, "rawTransaction");
        this.table = java.util.Objects.requireNonNull(table, "table");
        long persistedRowCount = MvccRawStoreTable.estimatedRowCount(rawTransaction, table);
        this.estimatedRowCount = persistedRowCount > 0L ? persistedRowCount : 1L;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public double getFetchFromRowLocationCost(FormatableBitSet validColumns, int accessType) {
        ensureOpen();
        return BASE_CACHED_ROW_FETCH_COST;
    }

    @Override
    public double getFetchFromFullKeyCost(FormatableBitSet validColumns, int accessType) {
        ensureOpen();
        return BASE_NONGROUPSCAN_ROW_FETCH_COST;
    }

    @Override
    public void getScanCost(
            int scanType,
            long rowCount,
            int groupSize,
            boolean forUpdate,
            FormatableBitSet scanColumnList,
            StoreDataValue[] template,
            StoreDataValue[] startKeyValue,
            int startSearchOperator,
            StoreDataValue[] stopKeyValue,
            int stopSearchOperator,
            boolean reopenScan,
            int accessType,
            StoreCostResult costResult) {
        ensureOpen();
        long rows = fallbackRows(rowCount);
        double perRow = scanType == STORECOST_SCAN_SET
                ? BASE_HASHSCAN_ROW_FETCH_COST
                : BASE_NONGROUPSCAN_ROW_FETCH_COST;
        if (groupSize > 1) {
            perRow = BASE_GROUPSCAN_ROW_COST;
        }
        double cost = Math.max(1.0d, rows * perRow);
        if (forUpdate) {
            cost += rows * BASE_CACHED_ROW_FETCH_COST;
        }
        if (reopenScan) {
            cost += BASE_CACHED_ROW_FETCH_COST;
        }
        costResult.setEstimatedRowCount(rows);
        costResult.setEstimatedCost(cost);
    }

    @Override
    public StoreRowLocation newRowLocationTemplate() {
        ensureOpen();
        return new MvccRowLocation();
    }

    @Override
    public long getEstimatedRowCount() {
        ensureOpen();
        return estimatedRowCount;
    }

    @Override
    public void setEstimatedRowCount(long count) throws StandardException {
        ensureOpen();
        long persistedRowCount = Math.max(0L, count);
        MvccRawStoreTable.setEstimatedRowCount(rawTransaction, table, persistedRowCount);
        estimatedRowCount = persistedRowCount > 0L ? persistedRowCount : 1L;
    }

    private long fallbackRows(long rowCount) {
        long rows = rowCount >= 0L ? rowCount : estimatedRowCount;
        return rows > 0L ? rows : 1L;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "MVCC cost controller is closed for " + conglomerate.getId());
        }
    }
}
