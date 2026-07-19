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
import org.apache.derby.iapi.store.types.DelosMvccOptimizerCostDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageCostEstimate;
import org.apache.derby.iapi.store.types.DelosStorageStatistics;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Optimizer cost controller for the {@code delos_mvcc} access method.
 *
 * <p>This is deliberately conservative. It exists so Derby's inherited
 * optimizer can prepare a normal {@code TableScanResultSet} against an MVCC
 * physical conglomerate and then open {@link MvccScanController}. When
 * MVCC optimizer-cost diagnostics are enabled, it derives estimates from
 * MVCC storage statistics through this inherited Derby cost-controller seam
 * rather than through a parallel optimizer statistics channel.</p>
 */
final class MvccStoreCostController implements StoreCostController {
    private final MvccConglomerate conglomerate;
    private long estimatedRowCount = 1L;
    private boolean closed;

    MvccStoreCostController(MvccConglomerate conglomerate) {
        this.conglomerate = conglomerate;
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
        // Model this request through the currently available MVCC access paths.
        // a small scan rather than promising true full-key lookup semantics.
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
        if (DelosMvccOptimizerCostDiagnostics.enabled() && !conglomerate.rawStoreBacked()) {
            DelosStorageStatistics statistics = conglomerate.state().storageStatisticsSnapshot();
            DelosStorageCostEstimate estimate = DelosStorageCostEstimate.fromStatisticsForOptimizerCosting(statistics);
            long rows = statistics.logicalRowCount() > 0L
                    ? statistics.logicalRowCount()
                    : fallbackRows(rowCount);
            double cost = boundedCost(estimate.estimatedFullScanCost());
            if (scanType == STORECOST_SCAN_SET) {
                cost += Math.max(1.0d, rows * BASE_HASHSCAN_ROW_FETCH_COST);
            }
            if (groupSize > 1) {
                cost += Math.max(1.0d, rows * BASE_GROUPSCAN_ROW_COST);
            }
            if (forUpdate) {
                cost += Math.max(1.0d, rows * BASE_CACHED_ROW_FETCH_COST);
            }
            if (reopenScan) {
                cost += BASE_CACHED_ROW_FETCH_COST;
            }
            cost = Math.max(1.0d, cost);
            costResult.setEstimatedRowCount(rows);
            costResult.setEstimatedCost(cost);
            DelosMvccOptimizerCostDiagnostics.recordStatisticsEstimate(
                    conglomerate.getContainerid(), statistics, cost, rows);
            return;
        }

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
        if (DelosMvccOptimizerCostDiagnostics.enabled() && !conglomerate.rawStoreBacked()) {
            long rows = conglomerate.state().storageStatisticsSnapshot().logicalRowCount();
            return rows > 0L ? rows : estimatedRowCount;
        }
        return estimatedRowCount;
    }

    @Override
    public void setEstimatedRowCount(long count) {
        ensureOpen();
        estimatedRowCount = Math.max(0L, count);
    }

    private long fallbackRows(long rowCount) {
        long rows = rowCount >= 0L ? rowCount : estimatedRowCount;
        return rows > 0L ? rows : 1L;
    }

    private static double boundedCost(long cost) {
        if (cost <= 0L) {
            return 1.0d;
        }
        return Math.min((double) cost, Double.MAX_VALUE / 4.0d);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("MVCC cost controller is closed for " + conglomerate.getId());
        }
    }
}
