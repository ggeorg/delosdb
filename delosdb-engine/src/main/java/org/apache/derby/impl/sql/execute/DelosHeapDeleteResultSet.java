/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosHeapDeleteResultSet

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
 */
package org.apache.derby.impl.sql.execute;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.shared.common.error.StandardException;

/**
 * N3 property-gated heap DELETE live route for supported ordinary heap shapes.
 *
 * <p>This route deliberately keeps Derby's RowChanger-owned delete semantics by
 * extending {@link DeleteResultSet}. It exists to make the heap DELETE execution
 * boundary explicit and property-gated before any broader heap mutable-provider
 * contract is claimed. It is not a heap locking/reservation API.</p>
 */
final class DelosHeapDeleteResultSet extends DeleteResultSet {
    static final String HEAP_DELETE_UPDATE_LIVE_ROUTE_PROPERTY =
            "delosdb.storage.phaseN3.heapDeleteUpdateLiveRoute";

    private static final AtomicInteger LIVE_BRANCH_COUNT = new AtomicInteger();
    private static final AtomicReference<DelosTableScanProviderLookup.Result> LAST_LIVE_LOOKUP =
            new AtomicReference<>();

    private DelosHeapDeleteResultSet(
            NoPutResultSet source,
            Activation activation,
            ConstantAction constantAction,
            DelosTableScanProviderLookup.Result providerLookup)
            throws StandardException {
        super(source, constantAction, activation);
        Objects.requireNonNull(providerLookup, "providerLookup");
        LIVE_BRANCH_COUNT.incrementAndGet();
        LAST_LIVE_LOOKUP.set(providerLookup);
    }

    static Optional<org.apache.derby.iapi.sql.ResultSet> createIfEnabled(
            NoPutResultSet source,
            Activation activation)
            throws StandardException {
        if (!DelosTableScanProviderLookup.isHeapDeleteUpdateLiveRouteEnabled()) {
            return Optional.empty();
        }
        if (source == null || activation == null) {
            return Optional.empty();
        }

        ConstantAction action = activation.getConstantAction();
        if (!(action instanceof DeleteConstantAction deleteConstants)) {
            return Optional.empty();
        }
        if (!isSupportedHeapDeleteShape(deleteConstants)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.findByTargetUUID(
                        activation,
                        deleteConstants.targetUUID);
        if (lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosHeapDeleteResultSet(
                source,
                activation,
                action,
                lookup.get()));
    }

    private static boolean isSupportedHeapDeleteShape(DeleteConstantAction constants) {
        return !constants.deferred
                && constants.getFKInfo() == null
                && constants.getTriggerInfo() == null
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
