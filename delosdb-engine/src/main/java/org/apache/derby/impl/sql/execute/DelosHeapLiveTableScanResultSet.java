/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosHeapLiveTableScanResultSet

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

package org.apache.derby.impl.sql.execute;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptor;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptorList;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.services.storetypes.EngineHeapDerbyAccessSupport;
import org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge;
import org.apache.derby.shared.common.error.StandardException;

/**
 * M3/O2 property-gated heap SELECT live route for supported read-only shapes.
 *
 * <p>This result set is the first heap SQL read path that crosses a Delos table
 * access object.  It is deliberately narrow: default-provider heap only,
 * read-only base scans only, no index-name scans, no explicit keyed start/stop
 * getters, no mutations, no row reservation, no locking abstraction, and no
 * native registry registration.  Unsupported shapes fall back to Derby's
 * ordinary {@link TableScanResultSet} / {@link BulkTableScanResultSet} paths.</p>
 */
final class DelosHeapLiveTableScanResultSet extends TableScanResultSet {
    static final String HEAP_SELECT_LIVE_ROUTE_PROPERTY =
            "delosdb.storage.phaseM3.heapSelectLiveRoute";

    private static final AtomicInteger LIVE_BRANCH_COUNT = new AtomicInteger();
    private static final AtomicReference<DelosTableScanProviderLookup.Result> LAST_LIVE_LOOKUP =
            new AtomicReference<>();

    private final TableScanResultSetParameters params;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private DelosScan heapScan;
    private RowLocation currentHeapRowLocation;

    private DelosHeapLiveTableScanResultSet(
            TableScanResultSetParameters params,
            DelosTableScanProviderLookup.Result providerLookup)
            throws StandardException {
        super(params);
        this.params = params;
        this.providerLookup = Objects.requireNonNull(providerLookup, "providerLookup");
        LIVE_BRANCH_COUNT.incrementAndGet();
        LAST_LIVE_LOOKUP.set(providerLookup);
    }

    static Optional<NoPutResultSet> createIfEnabled(TableScanResultSetParameters params)
            throws StandardException {
        if (!DelosTableScanProviderLookup.isHeapSelectLiveRouteEnabled()) {
            return Optional.empty();
        }
        if (!isSupportedHeapReadShape(params)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(params.activation, params.tableName);
        if (lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosHeapLiveTableScanResultSet(params, lookup.get()));
    }

    private static boolean isSupportedHeapReadShape(TableScanResultSetParameters params) {
        return !params.forUpdate
                && !hasIndexName(params.indexName)
                && params.indexColItem == -1
                && params.startKeyGetter == null
                && params.stopKeyGetter == null;
    }

    private static boolean hasIndexName(String indexName) {
        return indexName != null && !indexName.trim().isEmpty();
    }

    @Override
    public void openCore() throws StandardException {
        beginTime = getCurrentTimeMillis();
        try {
            if (isOpen) {
                throw StandardException.plainWrapException(
                        new IllegalStateException("DelosHeapLiveTableScanResultSet is already open"));
            }
            if (!isSupportedHeapReadShape(params)) {
                throw StandardException.plainWrapException(
                        new UnsupportedOperationException(
                                "M3 heap SELECT live route only supports read-only base heap scans"));
            }

            TransactionController transactionController = activation.getTransactionController();
            initIsolationLevel();
            if (dcoci == null) {
                dcoci = transactionController.getDynamicCompiledConglomInfo(conglomId);
            }

            TableDescriptor tableDescriptor = DelosNativeResultSetSupport.tableDescriptor(
                    params.activation,
                    providerLookup.schemaName(),
                    providerLookup.tableName(),
                    "M3 heap SELECT live route");
            EngineHeapTableAccess heapAccess = new EngineHeapTableAccess(
                    DelosTableIdentity.of(providerLookup.schemaName(), providerLookup.tableName()),
                    tableShape(tableDescriptor));
            heapScan = heapAccess.scan(heapContext(transactionController), List.of(), DelosProjection.all());
            isKeyed = false;
            scanControllerOpened = true;
            rowsThisScan = 0L;
            isOpen = true;
            finished = false;
            numOpens++;
            nextDone = false;
        } catch (RuntimeException e) {
            abortHeapScan();
            throw StandardException.plainWrapException(e);
        } catch (StandardException e) {
            abortHeapScan();
            throw e;
        } finally {
            openTime += getElapsedMillis(beginTime);
        }
    }

    private DelosAccessContext heapContext(TransactionController transactionController) {
        return DelosAccessContext.builder(true)
                .put(EngineHeapDerbyAccessSupport.TRANSACTION_CONTROLLER_KEY, transactionController)
                .put(EngineHeapDerbyAccessSupport.CONGLOMERATE_ID_KEY, params.conglomId)
                .put(EngineHeapDerbyAccessSupport.OPEN_MODE_KEY, 0)
                .put(EngineHeapDerbyAccessSupport.LOCK_LEVEL_KEY, lockMode)
                .put(EngineHeapDerbyAccessSupport.ISOLATION_LEVEL_KEY, isolationLevel)
                .put(EngineHeapDerbyAccessSupport.QUALIFIER_KEY, params.qualifiers)
                .put(EngineHeapDerbyAccessSupport.STATIC_COMPILED_INFO_KEY, params.scoci)
                .put(EngineHeapDerbyAccessSupport.DYNAMIC_COMPILED_INFO_KEY, dcoci)
                .put(EngineHeapTableAccess.HOLD_SCAN_OPEN_KEY, activation.getResultSetHoldability())
                .put(EngineHeapTableAccess.ROW_TEMPLATE_KEY, candidate.getRowArray())
                .build();
    }

    @Override
    public ExecRow getNextRowCore() throws StandardException {
        if (isXplainOnlyMode()) {
            return null;
        }
        checkCancellationFlag();
        beginTime = getCurrentTimeMillis();
        ExecRow result = null;
        try {
            if (isOpen && !nextDone && heapScan != null) {
                nextDone = oneRowScan;
                if (heapScan.next()) {
                    DelosRow row = heapScan.row();
                    currentHeapRowLocation = heapRowLocation(row);
                    materializeHeapRow(row, candidate);
                    rowsSeen++;
                    rowsThisScan++;
                    result = getCompactRow(candidate, accessedCols, false);
                } else {
                    currentHeapRowLocation = null;
                    finished = true;
                }
            }
            setCurrentRow(result);
            currentRowIsValid = result != null;
            scanRepositioned = false;
            qualify = true;
            return result;
        } catch (RuntimeException e) {
            throw StandardException.plainWrapException(e);
        } finally {
            nextTime += getElapsedMillis(beginTime);
        }
    }

    @Override
    public RowLocation getRowLocation() {
        return currentHeapRowLocation;
    }

    @Override
    public void close() throws StandardException {
        RuntimeException closeFailure = null;
        if (heapScan != null) {
            try {
                heapScan.close();
            } catch (RuntimeException e) {
                closeFailure = e;
            } finally {
                heapScan = null;
                currentHeapRowLocation = null;
            }
        }
        super.close();
        if (closeFailure != null) {
            throw StandardException.plainWrapException(closeFailure);
        }
    }

    private void abortHeapScan() {
        if (heapScan == null) {
            return;
        }
        try {
            heapScan.close();
        } finally {
            heapScan = null;
            currentHeapRowLocation = null;
        }
    }

    private static RowLocation heapRowLocation(DelosRow row) {
        Optional<DelosRowIdentity> identity = row.rowIdentity();
        if (identity.isEmpty()) {
            return null;
        }
        Object nativeIdentity = identity.get().nativeIdentity();
        if (nativeIdentity instanceof RowLocation rowLocation) {
            return rowLocation;
        }
        if (nativeIdentity instanceof StoreRowLocation storeRowLocation) {
            return EngineStoreRowLocationBridge.requireEngineRowLocation(storeRowLocation);
        }
        return EngineStoreRowLocationBridge.requireEngineRowLocation(nativeIdentity);
    }

    private static void materializeHeapRow(DelosRow row, ExecRow targetRow)
            throws StandardException {
        int columnCount = Math.min(targetRow.nColumns(), row.values().size());
        for (int column = 1; column <= columnCount; column++) {
            DataValueDescriptor target = targetRow.getColumn(column);
            if (target == null) {
                continue;
            }
            StoreDataValue value = row.values().get(column - 1);
            if (value == null) {
                target.setToNull();
            } else if (value instanceof DataValueDescriptor dvd) {
                target.setValue(dvd);
            } else {
                target.setValue(value);
            }
        }
    }

    private static DelosTableShape tableShape(TableDescriptor tableDescriptor) {
        ColumnDescriptorList descriptors = tableDescriptor.getColumnDescriptorList();
        List<DelosTableShape.Column> columns = new ArrayList<>(descriptors.size());
        for (int i = 0; i < descriptors.size(); i++) {
            ColumnDescriptor descriptor = descriptors.elementAt(i);
            columns.add(new DelosTableShape.Column(
                    descriptor.getColumnName(),
                    descriptor.getType().getFullSQLTypeName(),
                    descriptor.getType().isNullable()));
        }
        return DelosTableShape.of(columns);
    }

    static void resetForTesting() {
        LIVE_BRANCH_COUNT.set(0);
        LAST_LIVE_LOOKUP.set(null);
    }

    static int liveBranchCountForTesting() {
        return LIVE_BRANCH_COUNT.get();
    }

    static Optional<DelosTableScanProviderLookup.Result> lastLiveLookupForTesting() {
        return Optional.ofNullable(LAST_LIVE_LOOKUP.get());
    }
}
