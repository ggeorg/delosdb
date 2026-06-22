/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineHeapTableAccessProof

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

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.ConglomerateController;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.ScanController;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosIndexAccess;
import org.apache.derby.iapi.store.types.DelosIndexStats;
import org.apache.derby.iapi.store.types.DelosIndexableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRange;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.shared.common.error.StandardException;

/**
 * C22 compile-time honesty proof for mapping the C20 table-access contracts to
 * the inherited Derby heap store.
 *
 * <p>This class is deliberately not wired into Derby heap SQL execution.  It is
 * a proof adapter only: the public capability methods decline runtime use, while
 * the private proof methods type-check the exact Derby heap concepts that would
 * be needed by a future real adapter: {@link TransactionController},
 * {@link ScanController}, {@link ConglomerateController}, {@link RowLocation},
 * {@link StoreRowLocation}, Derby lock levels, and Derby isolation levels.</p>
 */
public final class EngineHeapTableAccessProof
        implements DelosFilterableTableAccess, DelosIndexableTableAccess, DelosMutableTableAccess {
    public static final String PROVIDER_NAME = "heap";

    public static final DelosContextKey<TransactionController> TRANSACTION_CONTROLLER_KEY =
            DelosContextKey.of("delosdb.heap.transactionController", TransactionController.class);
    public static final DelosContextKey<Long> CONGLOMERATE_ID_KEY =
            DelosContextKey.of("delosdb.heap.conglomerateId", Long.class);
    public static final DelosContextKey<Integer> OPEN_MODE_KEY =
            DelosContextKey.of("delosdb.heap.openMode", Integer.class);
    public static final DelosContextKey<Integer> LOCK_LEVEL_KEY =
            DelosContextKey.of("delosdb.heap.lockLevel", Integer.class);
    public static final DelosContextKey<Integer> ISOLATION_LEVEL_KEY =
            DelosContextKey.of("delosdb.heap.isolationLevel", Integer.class);
    public static final DelosContextKey<FormatableBitSet> SCAN_COLUMN_LIST_KEY =
            DelosContextKey.of("delosdb.heap.scanColumnList", FormatableBitSet.class);
    public static final DelosContextKey<StoreDataValue[]> START_KEY_VALUE_KEY =
            DelosContextKey.of("delosdb.heap.startKeyValue", StoreDataValue[].class);
    public static final DelosContextKey<StoreDataValue[]> STOP_KEY_VALUE_KEY =
            DelosContextKey.of("delosdb.heap.stopKeyValue", StoreDataValue[].class);
    public static final DelosContextKey<Qualifier[][]> QUALIFIER_KEY =
            DelosContextKey.of("delosdb.heap.qualifier", Qualifier[][].class);
    public static final DelosContextKey<StaticCompiledOpenConglomInfo> STATIC_COMPILED_INFO_KEY =
            DelosContextKey.of("delosdb.heap.staticCompiledInfo", StaticCompiledOpenConglomInfo.class);
    public static final DelosContextKey<DynamicCompiledOpenConglomInfo> DYNAMIC_COMPILED_INFO_KEY =
            DelosContextKey.of("delosdb.heap.dynamicCompiledInfo", DynamicCompiledOpenConglomInfo.class);

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;

    public EngineHeapTableAccessProof(DelosTableIdentity identity, DelosTableShape rowShape) {
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
        return DelosTableCapabilities.of(
                DelosTableCapability.FILTERABLE,
                DelosTableCapability.PROJECTABLE,
                DelosTableCapability.INDEXABLE,
                DelosTableCapability.MUTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(
                DelosTableGuarantee.ROW_LOCKING,
                DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> mutableFilters,
            DelosProjection projection) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mutableFilters, "mutableFilters");
        Objects.requireNonNull(projection, "projection");
        throw proofOnlyUnsupported("Derby heap scan still runs through TableScanResultSet and TransactionController.openScan/openCompiledScan");
    }

    @Override
    public DelosIndexAccess openIndex(DelosAccessContext context, String indexName) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(indexName, "indexName");
        throw proofOnlyUnsupported("Derby heap/btree index access still runs through native Derby ScanController paths");
    }

    @Override
    public DelosMutationResult insert(DelosAccessContext context, DelosRow row) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(row, "row");
        throw proofOnlyUnsupported("Derby heap INSERT still runs through RowChangerImpl and ConglomerateController.insert/insertAndFetchLocation");
    }

    @Override
    public DelosMutationResult update(DelosAccessContext context, DelosRowIdentity rowIdentity, DelosRow replacement) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rowIdentity, "rowIdentity");
        Objects.requireNonNull(replacement, "replacement");
        throw proofOnlyUnsupported("Derby heap UPDATE still runs through RowChangerImpl and ConglomerateController.replace by row location");
    }

    @Override
    public DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(rowIdentity, "rowIdentity");
        throw proofOnlyUnsupported("Derby heap DELETE still runs through RowChangerImpl and ConglomerateController.delete by row location");
    }

    public static DelosRowIdentity rowIdentity(Object rowLocation) {
        return new HeapRowIdentity(EngineStoreRowLocationBridge.requireStoreRowLocation(rowLocation));
    }

    private static UnsupportedOperationException proofOnlyUnsupported(String detail) {
        return new UnsupportedOperationException("C22 heap table-access proof only; " + detail);
    }

    @SuppressWarnings("unused")
    private ScanController compileTimeOpenHeapScan(DelosAccessContext context) throws StandardException {
        requirePhysicalAccess(context);
        TransactionController tc = context.require(TRANSACTION_CONTROLLER_KEY);
        return tc.openScan(
                context.require(CONGLOMERATE_ID_KEY),
                false,
                openMode(context),
                lockLevel(context),
                isolationLevel(context),
                context.find(SCAN_COLUMN_LIST_KEY).orElse(null),
                context.find(START_KEY_VALUE_KEY).orElse(null),
                ScanController.GE,
                context.find(QUALIFIER_KEY).orElse(null),
                context.find(STOP_KEY_VALUE_KEY).orElse(null),
                ScanController.GT);
    }

    @SuppressWarnings("unused")
    private ScanController compileTimeOpenCompiledHeapScan(DelosAccessContext context) throws StandardException {
        requirePhysicalAccess(context);
        TransactionController tc = context.require(TRANSACTION_CONTROLLER_KEY);
        return tc.openCompiledScan(
                false,
                openMode(context),
                lockLevel(context),
                isolationLevel(context),
                context.find(SCAN_COLUMN_LIST_KEY).orElse(null),
                context.find(START_KEY_VALUE_KEY).orElse(null),
                ScanController.GE,
                context.find(QUALIFIER_KEY).orElse(null),
                context.find(STOP_KEY_VALUE_KEY).orElse(null),
                ScanController.GT,
                context.find(STATIC_COMPILED_INFO_KEY).orElse(null),
                context.find(DYNAMIC_COMPILED_INFO_KEY).orElse(null));
    }

    @SuppressWarnings("unused")
    private ConglomerateController compileTimeOpenHeapConglomerate(DelosAccessContext context)
            throws StandardException {
        requirePhysicalAccess(context);
        TransactionController tc = context.require(TRANSACTION_CONTROLLER_KEY);
        return tc.openConglomerate(
                context.require(CONGLOMERATE_ID_KEY),
                false,
                openMode(context),
                lockLevel(context),
                isolationLevel(context));
    }

    @SuppressWarnings("unused")
    private ConglomerateController compileTimeOpenCompiledHeapConglomerate(DelosAccessContext context)
            throws StandardException {
        requirePhysicalAccess(context);
        TransactionController tc = context.require(TRANSACTION_CONTROLLER_KEY);
        return tc.openCompiledConglomerate(
                false,
                openMode(context),
                lockLevel(context),
                isolationLevel(context),
                context.find(STATIC_COMPILED_INFO_KEY).orElse(null),
                context.find(DYNAMIC_COMPILED_INFO_KEY).orElse(null));
    }

    @SuppressWarnings("unused")
    private DelosRowIdentity compileTimeInsertAndFetchHeapRowLocation(
            ConglomerateController controller,
            StoreDataValue[] row)
            throws StandardException {
        StoreRowLocation storeLocation = controller.newRowLocationTemplate();
        RowLocation engineLocation = EngineStoreRowLocationBridge.requireEngineRowLocation(storeLocation);
        StoreRowLocation unwrappedLocation = EngineStoreRowLocationBridge.requireStoreRowLocation(engineLocation);
        controller.insertAndFetchLocation(row, unwrappedLocation);
        return new HeapRowIdentity(unwrappedLocation);
    }

    @SuppressWarnings("unused")
    private int compileTimeInsertHeapRow(ConglomerateController controller, StoreDataValue[] row)
            throws StandardException {
        return controller.insert(row);
    }

    @SuppressWarnings("unused")
    private boolean compileTimeUpdateHeapRow(
            ConglomerateController controller,
            DelosRowIdentity rowIdentity,
            StoreDataValue[] replacement,
            FormatableBitSet validColumns)
            throws StandardException {
        return controller.replace(requireHeapStoreRowLocation(rowIdentity), replacement, validColumns);
    }

    @SuppressWarnings("unused")
    private boolean compileTimeDeleteHeapRow(
            ConglomerateController controller,
            DelosRowIdentity rowIdentity)
            throws StandardException {
        return controller.delete(requireHeapStoreRowLocation(rowIdentity));
    }

    @SuppressWarnings("unused")
    private boolean compileTimeLockHeapRowForUpdate(
            ConglomerateController controller,
            DelosRowIdentity rowIdentity)
            throws StandardException {
        return controller.lockRow(
                requireHeapStoreRowLocation(rowIdentity),
                ConglomerateController.LOCK_UPD,
                true,
                0);
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!context.physicalAccessAllowed()) {
            throw new IllegalStateException("heap physical access is not allowed by this context");
        }
    }

    private static int openMode(DelosAccessContext context) {
        return context.find(OPEN_MODE_KEY).orElse(TransactionController.OPENMODE_FORUPDATE);
    }

    private static int lockLevel(DelosAccessContext context) {
        return context.find(LOCK_LEVEL_KEY).orElse(TransactionController.MODE_RECORD);
    }

    private static int isolationLevel(DelosAccessContext context) {
        return context.find(ISOLATION_LEVEL_KEY).orElse(TransactionController.ISOLATION_READ_COMMITTED);
    }

    private static StoreRowLocation requireHeapStoreRowLocation(DelosRowIdentity rowIdentity) {
        if (!(rowIdentity instanceof HeapRowIdentity heapIdentity)) {
            throw new IllegalArgumentException("heap mutation requires a heap row identity produced by this proof adapter");
        }
        return heapIdentity.rowLocation();
    }

    /** Heap row identity wraps the native Derby row-location object. */
    public static final class HeapRowIdentity implements DelosRowIdentity {
        private final StoreRowLocation rowLocation;

        private HeapRowIdentity(StoreRowLocation rowLocation) {
            this.rowLocation = Objects.requireNonNull(rowLocation, "rowLocation").unwrapStoreRowLocation();
        }

        @Override
        public String providerName() {
            return PROVIDER_NAME;
        }

        @Override
        public Object nativeIdentity() {
            return rowLocation;
        }

        public StoreRowLocation rowLocation() {
            return rowLocation;
        }
    }

    private static final class HeapIndexAccessProof implements DelosIndexAccess {
        private final String indexName;

        private HeapIndexAccessProof(String indexName) {
            this.indexName = Objects.requireNonNull(indexName, "indexName");
        }

        @Override
        public String indexName() {
            return indexName;
        }

        @Override
        public DelosIndexStats stats(DelosAccessContext context) {
            Objects.requireNonNull(context, "context");
            throw proofOnlyUnsupported("Derby heap index statistics still come from native Derby store cost/stat paths");
        }

        @Override
        public DelosScan scan(DelosAccessContext context, DelosRange range, DelosProjection projection) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(projection, "projection");
            throw proofOnlyUnsupported("Derby heap index scans still run through TransactionController.openScan");
        }

        @Override
        public void close() {
        }
    }
}
