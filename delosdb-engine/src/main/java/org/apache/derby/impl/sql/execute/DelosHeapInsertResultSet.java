/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosHeapInsertResultSet

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.sql.execute;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.sql.ResultSet;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.impl.services.storetypes.EngineHeapRowChangerMutationAdapter;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccessProof;
import org.apache.derby.shared.common.error.StandardException;

/**
 * N2/O2 property-gated heap INSERT live route for supported shapes.
 *
 * <p>This result set is deliberately narrow. It activates only for the default
 * heap provider, only when {@link #HEAP_INSERT_LIVE_ROUTE_PROPERTY} is set, and
 * only for ordinary immediate heap INSERT shapes that Derby has already
 * normalized into source rows. The actual heap mutation is still Derby-owned
 * through {@link EngineHeapTableAccess} and RowChanger. This is
 * not a heap DELETE/UPDATE route, not a heap lock/reservation API, and not a
 * full heap mutable-provider implementation.</p>
 */
final class DelosHeapInsertResultSet extends DMLWriteResultSet {
    static final String HEAP_INSERT_LIVE_ROUTE_PROPERTY =
            "delosdb.storage.phaseN2.heapInsertLiveRoute";

    private static final AtomicInteger LIVE_BRANCH_COUNT = new AtomicInteger();
    private static final AtomicReference<DelosTableScanProviderLookup.Result> LAST_LIVE_LOOKUP =
            new AtomicReference<>();

    private final InsertResultSetParameters params;
    private final InsertConstantAction constants;
    private final DelosTableScanProviderLookup.Result providerLookup;
    private boolean sourceOpen;

    private DelosHeapInsertResultSet(
            InsertResultSetParameters params,
            InsertConstantAction constants,
            DelosTableScanProviderLookup.Result providerLookup)
            throws StandardException {
        super(params.activation);
        this.params = Objects.requireNonNull(params, "params");
        this.constants = Objects.requireNonNull(constants, "constants");
        this.providerLookup = Objects.requireNonNull(providerLookup, "providerLookup");
        this.resultDescription = params.source.getResultDescription();
        LIVE_BRANCH_COUNT.incrementAndGet();
        LAST_LIVE_LOOKUP.set(providerLookup);
    }

    static Optional<ResultSet> createIfEnabled(InsertResultSetParameters params)
            throws StandardException {
        if (!DelosTableScanProviderLookup.isHeapInsertLiveRouteEnabled()) {
            return Optional.empty();
        }

        ConstantAction action = params.activation.getConstantAction();
        if (!(action instanceof InsertConstantAction insertConstants)) {
            return Optional.empty();
        }
        if (!isSupportedHeapInsertShape(params, insertConstants)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup = DelosTableScanProviderLookup.find(
                params.activation,
                DelosNativeResultSetSupport.qualifiedName(params.schemaName, params.tableName));
        if (lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosHeapInsertResultSet(params, insertConstants, lookup.get()));
    }

    private static boolean isSupportedHeapInsertShape(
            InsertResultSetParameters params,
            InsertConstantAction constants) {
        return params.source != null
                && params.generationClauses == null
                && params.checkGM == null
                && !constants.deferred
                && constants.getFKInfo() == null
                && constants.getTriggerInfo() == null
                && !constants.hasAutoincrement()
                && !constants.underMerge()
                && constants.getProperty("insertMode") == null;
    }

    @Override
    public void open() throws StandardException {
        setup();
        beginTime = getCurrentTimeMillis();
        rowCount = 0L;
        EngineHeapRowChangerMutationAdapter adapter = null;
        try {
            params.source.openCore();
            sourceOpen = true;
            EngineHeapTableAccess heapAccess = new EngineHeapTableAccess(
                    DelosTableIdentity.of(providerLookup.schemaName(), providerLookup.tableName()),
                    DelosTableShape.of(List.of()));
            adapter = heapAccess.openMutationAdapter(heapMutationContext());

            ExecRow row;
            while ((row = params.source.getNextRowCore()) != null) {
                adapter.insert(row);
                rowCount++;
            }
            adapter.finish();
        } catch (RuntimeException e) {
            throw StandardException.plainWrapException(e);
        } finally {
            StandardException pending = null;
            if (adapter != null) {
                try {
                    adapter.close();
                } catch (StandardException e) {
                    pending = e;
                }
            }
            if (sourceOpen) {
                try {
                    params.source.close();
                } catch (StandardException e) {
                    if (pending == null) {
                        pending = e;
                    }
                } finally {
                    sourceOpen = false;
                }
            }
            endTime = getCurrentTimeMillis();
            if (pending != null) {
                throw pending;
            }
        }
    }

    private DelosAccessContext heapMutationContext() {
        DelosAccessContext.Builder builder = DelosAccessContext.builder(true)
                .put(EngineHeapTableAccess.EXECUTION_FACTORY_KEY,
                        lcc.getLanguageConnectionFactory().getExecutionFactory())
                .put(EngineHeapTableAccessProof.CONGLOMERATE_ID_KEY, constants.conglomId)
                .put(EngineHeapTableAccessProof.TRANSACTION_CONTROLLER_KEY,
                        activation.getTransactionController())
                .put(EngineHeapTableAccess.NUMBER_OF_COLUMNS_KEY, 0)
                .put(EngineHeapTableAccess.ACTIVATION_KEY, activation)
                .put(EngineHeapTableAccess.MUTATION_LOCK_MODE_KEY, decodeLockMode(constants.lockMode));
        putIfPresent(builder, EngineHeapTableAccess.HEAP_SCOCI_KEY, constants.heapSCOCI);
        putIfPresent(builder, EngineHeapTableAccess.HEAP_DCOCI_KEY, heapDCOCI);
        putIfPresent(builder, EngineHeapTableAccess.INDEX_ROW_GENERATORS_KEY, constants.irgs);
        putIfPresent(builder, EngineHeapTableAccess.INDEX_CONGLOMERATE_IDS_KEY, constants.indexCIDS);
        putIfPresent(builder, EngineHeapTableAccess.INDEX_SCOCIS_KEY, constants.indexSCOCIs);
        putIfPresent(builder, EngineHeapTableAccess.INDEX_DCOCIS_KEY, indexDCOCIs);
        putIfPresent(builder, EngineHeapTableAccess.STREAM_STORABLE_COLUMN_IDS_KEY,
                constants.getStreamStorableHeapColIds());
        putIfPresent(builder, EngineHeapTableAccess.INDEX_NAMES_KEY, constants.indexNames);
        return builder.build();
    }

    private static <T> void putIfPresent(
            DelosAccessContext.Builder builder,
            DelosContextKey<T> key,
            T value) {
        if (value != null) {
            builder.put(key, value);
        }
    }

    @Override
    public void close() throws StandardException {
        close(constants.underMerge());
    }

    @Override
    public void cleanUp() throws StandardException {
        if (sourceOpen) {
            try {
                params.source.close();
            } finally {
                sourceOpen = false;
            }
        }
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
