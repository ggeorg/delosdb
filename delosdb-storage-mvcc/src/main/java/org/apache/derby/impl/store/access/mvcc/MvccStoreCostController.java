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

import java.util.Properties;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.DelosStoreCostTuning;
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.iapi.store.access.StoreCostResult;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.Property;

/** Conservative optimizer cost model for RawStore-backed MVCC scans. */
final class MvccStoreCostController implements StoreCostController {
    private static final String PHYSICAL_SCAN_COST_ENABLED_PROPERTY =
            "delosdb.experimental.mvccPhysicalScanCost.enabled";
    private static final String PHYSICAL_ROW_LOCATION_COST_ENABLED_PROPERTY =
            "delosdb.experimental.mvccPhysicalRowLocationCost.enabled";

    private final MvccConglomerate conglomerate;
    private final Transaction rawTransaction;
    private final MvccRawStoreTable.Descriptor table;
    private long estimatedRowCount;
    private final boolean physicalScanCostEnabled;
    private final boolean physicalRowLocationCostEnabled;
    private long estimatedCurrentPageCount = 1L;
    private long estimatedCurrentPageSize = Long.parseLong(Property.PAGE_SIZE_DEFAULT_LONG);
    private double estimatedCurrentRowSize = 1.0d;
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
        this.physicalScanCostEnabled = Boolean.parseBoolean(System.getProperty(
                PHYSICAL_SCAN_COST_ENABLED_PROPERTY, "true"));
        this.physicalRowLocationCostEnabled = Boolean.parseBoolean(System.getProperty(
                PHYSICAL_ROW_LOCATION_COST_ENABLED_PROPERTY, "true"));
        if (physicalScanCostEnabled || physicalRowLocationCostEnabled) {
            initializePhysicalStats();
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public double getFetchFromRowLocationCost(FormatableBitSet validColumns, int accessType) {
        ensureOpen();
        if (!physicalRowLocationCostEnabled) {
            return BASE_CACHED_ROW_FETCH_COST;
        }
        return physicalRowLocationFetchCost(accessType);
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
        double cost = physicalScanCostEnabled
                ? physicalScanCost(scanType, groupSize, rows)
                : legacyScanCost(scanType, groupSize, rows);
        if (forUpdate) {
            cost += rows * BASE_CACHED_ROW_FETCH_COST;
        }
        if (reopenScan) {
            cost += BASE_CACHED_ROW_FETCH_COST;
        }
        costResult.setEstimatedRowCount(rows);
        costResult.setEstimatedCost(Math.max(1.0d, cost));
    }


    private double legacyScanCost(int scanType, int groupSize, long rows) {
        double perRow = scanType == STORECOST_SCAN_SET
                ? BASE_HASHSCAN_ROW_FETCH_COST
                : BASE_NONGROUPSCAN_ROW_FETCH_COST;
        if (groupSize > 1) {
            perRow = BASE_GROUPSCAN_ROW_COST;
        }
        return rows * perRow;
    }

    private double physicalScanCost(int scanType, int groupSize, long rows) {
        double cost = estimatedCurrentPageCount * DelosStoreCostTuning.uncachedRowFetchCost();
        cost += rows * estimatedCurrentRowSize * BASE_ROW_PER_BYTECOST;
        return cost + legacyScanCost(scanType, groupSize, rows);
    }

    private double physicalRowLocationFetchCost(int accessType) {
        // Keep the inherited StoreCostController contract: random RowLocation
        // probes pay uncached page access, clustered probes pay cached access,
        // and both pay for the estimated CURRENT bytes materialized.
        double cost = estimatedCurrentRowSize * BASE_ROW_PER_BYTECOST;
        long pagesPerRow =
                (long) (estimatedCurrentRowSize / estimatedCurrentPageSize) + 1L;
        double pageFetchCost = (accessType & STORECOST_CLUSTERED) == 0
                ? DelosStoreCostTuning.uncachedRowFetchCost()
                : BASE_CACHED_ROW_FETCH_COST;
        return cost + (pagesPerRow * pageFetchCost);
    }

    private void initializePhysicalStats() throws StandardException {
        ContainerHandle container = rawTransaction.openContainer(
                table.metadataContainer(),
                MvccRawStorePhysicalLocking.rowLevel(rawTransaction),
                ContainerHandle.MODE_READONLY
                        | (table.temporary() ? ContainerHandle.MODE_TEMP_IS_KEPT : 0));
        if (container == null) {
            return;
        }
        try {
            estimatedCurrentPageCount = Math.max(1L, container.getEstimatedPageCount(0));
            Properties properties = new Properties();
            properties.put(Property.PAGE_SIZE_PARAMETER, "");
            container.getContainerProperties(properties);
            String pageSizeText = properties.getProperty(Property.PAGE_SIZE_PARAMETER);
            estimatedCurrentPageSize = Long.parseLong(
                    pageSizeText == null || pageSizeText.isBlank()
                            ? Property.PAGE_SIZE_DEFAULT_LONG
                            : pageSizeText);
            double estimatedCurrentBytes =
                    (double) estimatedCurrentPageCount * (double) estimatedCurrentPageSize;
            estimatedCurrentRowSize = Math.max(
                    1.0d,
                    estimatedCurrentBytes / (double) Math.max(1L, estimatedRowCount));
        } finally {
            container.close();
        }
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
        if (physicalScanCostEnabled || physicalRowLocationCostEnabled) {
            initializePhysicalStats();
        }
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
