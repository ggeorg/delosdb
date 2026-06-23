/*

   Derby - Class org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup

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
package org.apache.derby.impl.sql.compile;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.iapi.sql.compile.CostEstimate;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.shared.common.error.StandardException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * H2 diagnostic bridge from Derby's native optimizer cost path to Delos table
 * cost estimates.
 *
 * <p>This class deliberately observes {@link FromBaseTable#estimateCost} after
 * Derby has already selected normal metadata and store-cost paths.  It maps a
 * catalog-backed {@code delos_mvcc} {@link TableDescriptor} to
 * {@link DelosCostableTableAccess#estimateTableCost} and records the provider
 * estimate next to Derby's current cost estimate. L4 keeps the old diagnostic
 * behavior as the default, but adds an explicit delos_mvcc-only gate that can
 * replace Derby's current {@link CostEstimate} with the provider estimate before
 * {@link FromBaseTable#estimateCost} returns it to the optimizer.</p>
 */
public final class DelosNativeTableCostLookup {
    public static final String NATIVE_TABLE_COST_PROBE_PROPERTY =
            "delosdb.storage.phase.h2.nativeTableCostProbe";

    /**
     * L4 proof gate: when enabled for a delos_mvcc table, the provider cost
     * estimate replaces Derby's current optimizer CostEstimate at the existing
     * FromBaseTable.estimateCost boundary. The default remains diagnostic-only.
     */
    public static final String NATIVE_TABLE_COST_CONSUMPTION_PROPERTY =
            "delosdb.storage.phase.l4.nativeOptimizerCostConsumption";

    private static final String PROVIDER_NAME = "delos_mvcc";
    private static final AtomicReference<Result> LAST_LOOKUP = new AtomicReference<>();
    private static final AtomicInteger LOOKUP_COUNT = new AtomicInteger();

    private DelosNativeTableCostLookup() {
    }

    public static Optional<Result> observeIfEnabled(
            TableDescriptor tableDescriptor,
            CostEstimate derbyCostEstimate) throws StandardException {
        boolean diagnosticEnabled = Boolean.getBoolean(NATIVE_TABLE_COST_PROBE_PROPERTY);
        boolean consumptionEnabled = Boolean.getBoolean(NATIVE_TABLE_COST_CONSUMPTION_PROPERTY);
        if (!diagnosticEnabled && !consumptionEnabled) {
            return Optional.empty();
        }
        Optional<Result> result = estimate(tableDescriptor, derbyCostEstimate, consumptionEnabled);
        result.ifPresent(value -> {
            LOOKUP_COUNT.incrementAndGet();
            LAST_LOOKUP.set(value);
        });
        return result;
    }

    public static void resetForTesting() {
        LOOKUP_COUNT.set(0);
        LAST_LOOKUP.set(null);
    }

    public static int lookupCountForTesting() {
        return LOOKUP_COUNT.get();
    }

    public static Optional<Result> lastLookupForTesting() {
        return Optional.ofNullable(LAST_LOOKUP.get());
    }

    private static Optional<Result> estimate(
            TableDescriptor tableDescriptor,
            CostEstimate derbyCostEstimate,
            boolean consumptionEnabled) throws StandardException {
        if (tableDescriptor == null || !isDelosMvcc(tableDescriptor.getStorageProviderName())) {
            return Optional.empty();
        }
        try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                     DelosNativeTableRegistry.openNativeExecutionTableAccess(tableDescriptor)
                             .orElse(null)) {
            if (nativeAccess == null || !(nativeAccess.tableAccess() instanceof DelosCostableTableAccess costable)) {
                return Optional.empty();
            }
            DelosTableCostEstimate providerEstimate = costable.estimateTableCost(nativeAccess.context());
            double derbyCost = derbyCostEstimate == null ? 0.0d : derbyCostEstimate.getEstimatedCost();
            double derbyRows = derbyCostEstimate == null ? 0.0d : derbyCostEstimate.rowCount();
            double derbySingleScanRows = derbyCostEstimate == null ? 0.0d : derbyCostEstimate.singleScanRowCount();

            boolean consumed = false;
            if (consumptionEnabled
                    && derbyCostEstimate != null
                    && safeToConsume(providerEstimate)) {
                double providerCost = Math.max(1.0d, providerEstimate.estimatedFullScanCost());
                double providerRows = providerEstimate.visibleRowCount();
                derbyCostEstimate.setCost(providerCost, providerRows, providerRows);
                consumed = true;
            }

            double optimizerCost = derbyCostEstimate == null ? derbyCost : derbyCostEstimate.getEstimatedCost();
            double optimizerRows = derbyCostEstimate == null ? derbyRows : derbyCostEstimate.rowCount();
            double optimizerSingleScanRows = derbyCostEstimate == null
                    ? derbySingleScanRows
                    : derbyCostEstimate.singleScanRowCount();

            return Optional.of(new Result(
                    tableDescriptor.getSchemaName(),
                    tableDescriptor.getName(),
                    normalizeProvider(tableDescriptor.getStorageProviderName()),
                    providerEstimate.logicalRowCount(),
                    providerEstimate.visibleRowCount(),
                    providerEstimate.physicalVersionCount(),
                    providerEstimate.deadVersionEstimate(),
                    providerEstimate.estimatedFullScanCost(),
                    derbyCost,
                    derbyRows,
                    derbySingleScanRows,
                    optimizerCost,
                    optimizerRows,
                    optimizerSingleScanRows,
                    consumed));
        } catch (SQLException | RuntimeException e) {
            throw StandardException.plainWrapException(e);
        }
    }

    private static boolean isDelosMvcc(String providerName) {
        return PROVIDER_NAME.equals(normalizeProvider(providerName));
    }

    private static boolean safeToConsume(DelosTableCostEstimate providerEstimate) {
        return providerEstimate != null
                && providerEstimate.estimatedFullScanCost() > 0L
                && providerEstimate.visibleRowCount() >= 0L;
    }

    private static String normalizeProvider(String providerName) {
        if (providerName == null) {
            return "default";
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "default" : normalized;
    }

    public record Result(
            String schemaName,
            String tableName,
            String storageProviderName,
            long logicalRowCount,
            long visibleRowCount,
            long physicalVersionCount,
            long deadVersionEstimate,
            long estimatedFullScanCost,
            double derbyEstimatedCost,
            double derbyRowCount,
            double derbySingleScanRowCount,
            double optimizerEstimatedCost,
            double optimizerRowCount,
            double optimizerSingleScanRowCount,
            boolean consumedByDerbyOptimizer) {
        public Result {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(tableName, "tableName");
            storageProviderName = normalizeProvider(storageProviderName);
            if (logicalRowCount < 0L
                    || visibleRowCount < 0L
                    || physicalVersionCount < 0L
                    || deadVersionEstimate < 0L
                    || estimatedFullScanCost < 0L
                    || optimizerEstimatedCost < 0.0d
                    || optimizerRowCount < 0.0d
                    || optimizerSingleScanRowCount < 0.0d) {
                throw new IllegalArgumentException("native table cost values must be non-negative");
            }
        }

        public String qualifiedTableName() {
            return schemaName + "." + tableName;
        }

        public String decision() {
            return consumedByDerbyOptimizer ? "consumed" : "diagnostic-only";
        }
    }
}
