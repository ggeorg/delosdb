/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineHeapTableAccessLiveCandidate

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
package org.apache.derby.impl.services.storetypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * M1 isolated heap scan candidate.
 *
 * <p>M1 introduced this class without SQL routing. M3 uses it from a narrow,
 * property-gated heap SELECT live route for supported read-only base scans.
 * The candidate remains shaped around Derby's existing
 * {@link TransactionController#openScan} / {@link TransactionController#openCompiledScan}
 * and {@link ScanController} cursor APIs.</p>
 *
 * <p>It still deliberately avoids heap mutation, heap locking, row reservation,
 * provider registration, planner changes, and bytecode generation changes.</p>
 */
public final class EngineHeapTableAccessLiveCandidate implements DelosFilterableTableAccess {
    public static final String PROVIDER_NAME = EngineHeapTableAccessProof.PROVIDER_NAME;

    public static final DelosContextKey<StoreDataValue[]> ROW_TEMPLATE_KEY =
            DelosContextKey.of("delosdb.heap.rowTemplate", StoreDataValue[].class);
    public static final DelosContextKey<Boolean> HOLD_SCAN_OPEN_KEY =
            DelosContextKey.of("delosdb.heap.holdScanOpen", Boolean.class);

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;

    public EngineHeapTableAccessLiveCandidate(DelosTableIdentity identity, DelosTableShape rowShape) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
    }

    @Override
    public DelosTableIdentity identity() {
        return identity;
    }

    @Override
    public DelosTableShape rowShape() {
        return rowShape;
    }

    @Override
    public DelosTableCapabilities capabilities() {
        return DelosTableCapabilities.of(DelosTableCapability.FILTERABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of();
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> mutableFilters,
            DelosProjection projection) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mutableFilters, "mutableFilters");
        Objects.requireNonNull(projection, "projection");
        requirePhysicalAccess(context);
        if (!projection.allColumns()) {
            throw new UnsupportedOperationException(
                    "M1 heap scan candidate does not claim provider-side projection pushdown");
        }
        try {
            StoreDataValue[] rowTemplate = emptyFetchRow(context.require(ROW_TEMPLATE_KEY));
            return new HeapScan(openHeapScanCandidate(context), rowTemplate);
        } catch (StandardException e) {
            throw new IllegalStateException("Unable to open M1 heap scan candidate", e);
        }
    }

    private ScanController openHeapScanCandidate(DelosAccessContext context) throws StandardException {
        if (context.find(EngineHeapTableAccessProof.STATIC_COMPILED_INFO_KEY).isPresent()
                || context.find(EngineHeapTableAccessProof.DYNAMIC_COMPILED_INFO_KEY).isPresent()) {
            return openCompiledHeapScanCandidate(context);
        }
        return openUncompiledHeapScanCandidate(context);
    }

    private ScanController openUncompiledHeapScanCandidate(DelosAccessContext context) throws StandardException {
        TransactionController tc = context.require(EngineHeapTableAccessProof.TRANSACTION_CONTROLLER_KEY);
        return tc.openScan(
                context.require(EngineHeapTableAccessProof.CONGLOMERATE_ID_KEY),
                holdScanOpen(context),
                openMode(context),
                lockLevel(context),
                isolationLevel(context),
                scanColumnList(context),
                context.find(EngineHeapTableAccessProof.START_KEY_VALUE_KEY).orElse(null),
                ScanController.GE,
                qualifier(context),
                context.find(EngineHeapTableAccessProof.STOP_KEY_VALUE_KEY).orElse(null),
                ScanController.GT);
    }

    private ScanController openCompiledHeapScanCandidate(DelosAccessContext context) throws StandardException {
        TransactionController tc = context.require(EngineHeapTableAccessProof.TRANSACTION_CONTROLLER_KEY);
        return tc.openCompiledScan(
                holdScanOpen(context),
                openMode(context),
                lockLevel(context),
                isolationLevel(context),
                scanColumnList(context),
                context.find(EngineHeapTableAccessProof.START_KEY_VALUE_KEY).orElse(null),
                ScanController.GE,
                qualifier(context),
                context.find(EngineHeapTableAccessProof.STOP_KEY_VALUE_KEY).orElse(null),
                ScanController.GT,
                context.find(EngineHeapTableAccessProof.STATIC_COMPILED_INFO_KEY).orElse(null),
                context.find(EngineHeapTableAccessProof.DYNAMIC_COMPILED_INFO_KEY).orElse(null));
    }

    private static FormatableBitSet scanColumnList(DelosAccessContext context) {
        return context.find(EngineHeapTableAccessProof.SCAN_COLUMN_LIST_KEY).orElse(null);
    }

    private static Qualifier[][] qualifier(DelosAccessContext context) {
        return context.find(EngineHeapTableAccessProof.QUALIFIER_KEY).orElse(null);
    }

    private static boolean holdScanOpen(DelosAccessContext context) {
        return context.find(HOLD_SCAN_OPEN_KEY).orElse(false);
    }

    private static int openMode(DelosAccessContext context) {
        return context.find(EngineHeapTableAccessProof.OPEN_MODE_KEY).orElse(TransactionController.OPENMODE_FORUPDATE);
    }

    private static int lockLevel(DelosAccessContext context) {
        return context.find(EngineHeapTableAccessProof.LOCK_LEVEL_KEY).orElse(TransactionController.MODE_RECORD);
    }

    private static int isolationLevel(DelosAccessContext context) {
        return context.find(EngineHeapTableAccessProof.ISOLATION_LEVEL_KEY)
                .orElse(TransactionController.ISOLATION_READ_COMMITTED);
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!context.physicalAccessAllowed()) {
            throw new IllegalStateException("heap physical access is not allowed by this context");
        }
    }

    private static StoreDataValue[] emptyFetchRow(StoreDataValue[] sourceTemplate) throws StandardException {
        StoreDataValue[] fetchRow = new StoreDataValue[sourceTemplate.length];
        for (int i = 0; i < sourceTemplate.length; i++) {
            fetchRow[i] = emptyValue(sourceTemplate[i]);
        }
        return fetchRow;
    }

    private static StoreDataValue emptyValue(StoreDataValue value) throws StandardException {
        if (value instanceof DataValueDescriptor dvd) {
            return dvd.getNewNull();
        }
        return value;
    }

    private static StoreDataValue stableValue(StoreDataValue value) throws StandardException {
        if (value instanceof DataValueDescriptor dvd) {
            return dvd.cloneValue(false);
        }
        return value;
    }

    private static List<StoreDataValue> stableRow(StoreDataValue[] fetchRow) throws StandardException {
        List<StoreDataValue> values = new ArrayList<>(fetchRow.length);
        for (StoreDataValue value : fetchRow) {
            values.add(stableValue(value));
        }
        return values;
    }

    private static final class HeapScan implements DelosScan {
        private final ScanController scanController;
        private final StoreDataValue[] fetchRow;
        private DelosRow currentRow;

        private HeapScan(ScanController scanController, StoreDataValue[] fetchRow) {
            this.scanController = Objects.requireNonNull(scanController, "scanController");
            this.fetchRow = Objects.requireNonNull(fetchRow, "fetchRow");
        }

        @Override
        public boolean next() {
            try {
                if (!scanController.fetchNext(fetchRow)) {
                    currentRow = null;
                    return false;
                }
                StoreRowLocation rowLocation = scanController.newRowLocationTemplate();
                scanController.fetchLocation(rowLocation);
                currentRow = DelosRow.withIdentity(
                        EngineHeapTableAccessProof.rowIdentity(rowLocation),
                        stableRow(fetchRow));
                return true;
            } catch (StandardException e) {
                throw new IllegalStateException("Unable to fetch from M1 heap scan candidate", e);
            }
        }

        @Override
        public DelosRow row() {
            if (currentRow == null) {
                throw new IllegalStateException("M1 heap scan candidate has no current row");
            }
            return currentRow;
        }

        @Override
        public void close() {
            try {
                scanController.close();
            } catch (StandardException e) {
                throw new IllegalStateException("Unable to close M1 heap scan candidate", e);
            }
        }
    }
}
