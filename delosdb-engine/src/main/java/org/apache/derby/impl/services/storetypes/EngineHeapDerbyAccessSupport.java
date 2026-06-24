/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineHeapDerbyAccessSupport

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.storetypes;

import java.util.Objects;
import java.util.Set;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/**
 * O4 Derby heap access support shared by the live heap facade, heap scan
 * candidate, and heap cost mapping.
 *
 * <p>This replaces the old proof-only heap adapter name. It is deliberately
 * not a mutation API and does not implement {@code DelosMutableTableAccess}.
 * Heap write execution remains RowChanger-backed through
 * {@link EngineHeapTableAccess} and {@link EngineHeapRowChangerMutationAdapter};
 * heap transaction and locking semantics remain Derby-owned.</p>
 */
public final class EngineHeapDerbyAccessSupport implements DelosCostableTableAccess {
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
    public static final DelosContextKey<Long> ESTIMATED_ROW_COUNT_KEY =
            DelosContextKey.of("delosdb.heap.estimatedRowCount", Long.class);
    public static final DelosContextKey<Double> ESTIMATED_SCAN_COST_KEY =
            DelosContextKey.of("delosdb.heap.estimatedScanCost", Double.class);

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;

    public EngineHeapDerbyAccessSupport(DelosTableIdentity identity, DelosTableShape rowShape) {
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
        return DelosTableCapabilities.of(DelosTableCapability.COSTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(DelosTableGuarantee.ROW_LOCKING, DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosTableCostEstimate estimateTableCost(DelosAccessContext context) {
        requirePhysicalAccess(context);
        long rowCount = nonNegative(context.find(ESTIMATED_ROW_COUNT_KEY).orElse(0L));
        long scanCost = Math.max(1L, Math.round(nonNegative(
                context.find(ESTIMATED_SCAN_COST_KEY).orElse((double) rowCount))));
        return new DelosTableCostEstimate(rowCount, rowCount, rowCount, 0L, scanCost);
    }

    public static DelosRowIdentity rowIdentity(Object rowLocation) {
        return new HeapRowIdentity(EngineStoreRowLocationBridge.requireStoreRowLocation(rowLocation));
    }

    public static int openMode(DelosAccessContext context) {
        return context.find(OPEN_MODE_KEY).orElse(TransactionController.OPENMODE_FORUPDATE);
    }

    public static int lockLevel(DelosAccessContext context) {
        return context.find(LOCK_LEVEL_KEY).orElse(TransactionController.MODE_RECORD);
    }

    public static int isolationLevel(DelosAccessContext context) {
        return context.find(ISOLATION_LEVEL_KEY).orElse(TransactionController.ISOLATION_READ_COMMITTED);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0d ? value : 0.0d;
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("heap physical access is not allowed by this context");
        }
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
}
