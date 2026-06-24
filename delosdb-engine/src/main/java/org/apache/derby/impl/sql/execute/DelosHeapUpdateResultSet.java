/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosHeapUpdateResultSet

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.sql.execute;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.shared.common.error.StandardException;

/**
 * N3 property-gated heap UPDATE live route for supported ordinary heap shapes.
 *
 * <p>This route deliberately keeps Derby's RowChanger-owned update semantics by
 * extending {@link UpdateResultSet}. It makes the heap UPDATE execution boundary
 * explicit without claiming heap locking/reservation parity or introducing a
 * heap {@code DelosMutableTableAccess} implementation.</p>
 */
final class DelosHeapUpdateResultSet extends UpdateResultSet {
    private static final AtomicInteger LIVE_BRANCH_COUNT = new AtomicInteger();
    private static final AtomicReference<DelosTableScanProviderLookup.Result> LAST_LIVE_LOOKUP =
            new AtomicReference<>();

    private DelosHeapUpdateResultSet(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation,
            DelosTableScanProviderLookup.Result providerLookup)
            throws StandardException {
        super(source, generationClauses, checkGM, activation);
        LIVE_BRANCH_COUNT.incrementAndGet();
        LAST_LIVE_LOOKUP.set(providerLookup);
    }

    static Optional<org.apache.derby.iapi.sql.ResultSet> createIfEnabled(
            NoPutResultSet source,
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            Activation activation)
            throws StandardException {
        if (!Boolean.getBoolean(DelosHeapDeleteResultSet.HEAP_DELETE_UPDATE_LIVE_ROUTE_PROPERTY)) {
            return Optional.empty();
        }
        if (source == null || activation == null) {
            return Optional.empty();
        }

        ConstantAction action = activation.getConstantAction();
        if (!(action instanceof UpdateConstantAction updateConstants)) {
            return Optional.empty();
        }
        if (!isSupportedHeapUpdateShape(generationClauses, checkGM, updateConstants)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup = DelosTableScanProviderLookup.find(
                activation,
                DelosNativeResultSetSupport.qualifiedName(
                        updateConstants.getSchemaName(),
                        updateConstants.getTableName()));
        if (lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosHeapUpdateResultSet(
                source,
                generationClauses,
                checkGM,
                activation,
                lookup.get()));
    }

    private static boolean isSupportedHeapUpdateShape(
            GeneratedMethod generationClauses,
            GeneratedMethod checkGM,
            UpdateConstantAction constants) {
        return generationClauses == null
                && checkGM == null
                && !constants.deferred
                && constants.getFKInfo() == null
                && constants.getTriggerInfo() == null
                && !constants.hasAutoincrement()
                && !constants.underMerge();
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
