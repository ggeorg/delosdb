/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineHeapTableAccess

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.services.storetypes;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.derby.iapi.services.io.FormatableBitSet;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.dictionary.IndexRowGenerator;
import org.apache.derby.iapi.sql.execute.ExecutionFactory;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

/**
 * O2 heap table-access facade.
 *
 * <p>O2 consolidates the already-proven heap read and RowChanger-backed write
 * pieces behind one heap access object. It deliberately does not introduce a
 * heap implementation of {@code DelosMutableTableAccess}, a generic lock API,
 * a reservation API, or a heap MVCC-style concurrency guarantee. Heap locking
 * remains Derby-owned.</p>
 */
public final class EngineHeapTableAccess implements DelosFilterableTableAccess, DelosCostableTableAccess {
    public static final String PROVIDER_NAME = EngineHeapDerbyAccessSupport.PROVIDER_NAME;

    public static final DelosContextKey<StoreDataValue[]> ROW_TEMPLATE_KEY =
            EngineHeapTableAccessLiveCandidate.ROW_TEMPLATE_KEY;
    public static final DelosContextKey<Boolean> HOLD_SCAN_OPEN_KEY =
            EngineHeapTableAccessLiveCandidate.HOLD_SCAN_OPEN_KEY;

    public static final DelosContextKey<ExecutionFactory> EXECUTION_FACTORY_KEY =
            DelosContextKey.of("delosdb.heap.executionFactory", ExecutionFactory.class);
    public static final DelosContextKey<StaticCompiledOpenConglomInfo> HEAP_SCOCI_KEY =
            DelosContextKey.of("delosdb.heap.heapSCOCI", StaticCompiledOpenConglomInfo.class);
    public static final DelosContextKey<DynamicCompiledOpenConglomInfo> HEAP_DCOCI_KEY =
            DelosContextKey.of("delosdb.heap.heapDCOCI", DynamicCompiledOpenConglomInfo.class);
    public static final DelosContextKey<IndexRowGenerator[]> INDEX_ROW_GENERATORS_KEY =
            DelosContextKey.of("delosdb.heap.indexRowGenerators", IndexRowGenerator[].class);
    public static final DelosContextKey<long[]> INDEX_CONGLOMERATE_IDS_KEY =
            DelosContextKey.of("delosdb.heap.indexConglomerateIds", long[].class);
    public static final DelosContextKey<StaticCompiledOpenConglomInfo[]> INDEX_SCOCIS_KEY =
            DelosContextKey.of("delosdb.heap.indexSCOCIs", StaticCompiledOpenConglomInfo[].class);
    public static final DelosContextKey<DynamicCompiledOpenConglomInfo[]> INDEX_DCOCIS_KEY =
            DelosContextKey.of("delosdb.heap.indexDCOCIs", DynamicCompiledOpenConglomInfo[].class);
    public static final DelosContextKey<Integer> NUMBER_OF_COLUMNS_KEY =
            DelosContextKey.of("delosdb.heap.numberOfColumns", Integer.class);
    public static final DelosContextKey<int[]> CHANGED_COLUMN_IDS_KEY =
            DelosContextKey.of("delosdb.heap.changedColumnIds", int[].class);
    public static final DelosContextKey<FormatableBitSet> BASE_ROW_READ_LIST_KEY =
            DelosContextKey.of("delosdb.heap.baseRowReadList", FormatableBitSet.class);
    public static final DelosContextKey<int[]> BASE_ROW_READ_MAP_KEY =
            DelosContextKey.of("delosdb.heap.baseRowReadMap", int[].class);
    public static final DelosContextKey<int[]> STREAM_STORABLE_COLUMN_IDS_KEY =
            DelosContextKey.of("delosdb.heap.streamStorableColumnIds", int[].class);
    public static final DelosContextKey<Activation> ACTIVATION_KEY =
            DelosContextKey.of("delosdb.heap.activation", Activation.class);
    public static final DelosContextKey<String[]> INDEX_NAMES_KEY =
            DelosContextKey.of("delosdb.heap.indexNames", String[].class);
    public static final DelosContextKey<Integer> MUTATION_LOCK_MODE_KEY =
            DelosContextKey.of("delosdb.heap.mutationLockMode", Integer.class);

    private static final AtomicInteger FACADE_SCAN_OPEN_COUNT = new AtomicInteger();
    private static final AtomicInteger FACADE_MUTATION_ADAPTER_OPEN_COUNT = new AtomicInteger();

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;
    private final EngineHeapTableAccessLiveCandidate scanAccess;
    private final EngineHeapDerbyAccessSupport costAccess;

    public EngineHeapTableAccess(DelosTableIdentity identity, DelosTableShape rowShape) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
        this.scanAccess = new EngineHeapTableAccessLiveCandidate(identity, rowShape);
        this.costAccess = new EngineHeapDerbyAccessSupport(identity, rowShape);
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
                DelosTableCapability.COSTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(DelosTableGuarantee.ROW_LOCKING, DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> mutableFilters,
            DelosProjection projection) {
        FACADE_SCAN_OPEN_COUNT.incrementAndGet();
        return scanAccess.scan(context, mutableFilters, projection);
    }

    @Override
    public DelosTableCostEstimate estimateTableCost(DelosAccessContext context) {
        return costAccess.estimateTableCost(context);
    }

    /**
     * Open the Derby RowChanger-backed mutation adapter for supported heap
     * write routes. This is an internal heap facade method, not a generic
     * DelosMutableTableAccess lock/reservation contract.
     */
    public EngineHeapRowChangerMutationAdapter openMutationAdapter(DelosAccessContext context)
            throws StandardException {
        requirePhysicalAccess(context);
        FACADE_MUTATION_ADAPTER_OPEN_COUNT.incrementAndGet();
        return EngineHeapRowChangerMutationAdapter.open(
                context.require(EXECUTION_FACTORY_KEY),
                context.require(EngineHeapDerbyAccessSupport.CONGLOMERATE_ID_KEY),
                context.find(HEAP_SCOCI_KEY).orElse(null),
                context.find(HEAP_DCOCI_KEY).orElse(null),
                context.find(INDEX_ROW_GENERATORS_KEY).orElse(null),
                context.find(INDEX_CONGLOMERATE_IDS_KEY).orElse(null),
                context.find(INDEX_SCOCIS_KEY).orElse(null),
                context.find(INDEX_DCOCIS_KEY).orElse(null),
                context.find(NUMBER_OF_COLUMNS_KEY).orElse(0),
                context.require(EngineHeapDerbyAccessSupport.TRANSACTION_CONTROLLER_KEY),
                context.find(CHANGED_COLUMN_IDS_KEY).orElse(null),
                context.find(BASE_ROW_READ_LIST_KEY).orElse(null),
                context.find(BASE_ROW_READ_MAP_KEY).orElse(null),
                context.find(STREAM_STORABLE_COLUMN_IDS_KEY).orElse(null),
                context.require(ACTIVATION_KEY),
                context.find(INDEX_NAMES_KEY).orElse(null),
                context.require(MUTATION_LOCK_MODE_KEY));
    }

    public static void resetFacadeCountersForTesting() {
        FACADE_SCAN_OPEN_COUNT.set(0);
        FACADE_MUTATION_ADAPTER_OPEN_COUNT.set(0);
    }

    public static int facadeScanOpenCountForTesting() {
        return FACADE_SCAN_OPEN_COUNT.get();
    }

    public static int facadeMutationAdapterOpenCountForTesting() {
        return FACADE_MUTATION_ADAPTER_OPEN_COUNT.get();
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("heap physical mutation access is not allowed by this context");
        }
    }
}
