/*

   Derby - Class org.apache.derby.impl.sql.compile.DelosHeapCostProofLookup

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

import org.apache.derby.iapi.sql.compile.CostEstimate;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptor;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptorList;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccessProof;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * H3 proof-only heap mapping from Derby optimizer cost data to the Delos table
 * cost record.
 *
 * <p>Unlike H2, this class does not claim that Derby heap execution is routed
 * through Delos table access.  It constructs the existing proof-only heap
 * adapter with the catalog table shape and feeds Derby's own {@link CostEstimate}
 * row/cost values through the optional {@link EngineHeapTableAccessProof}
 * cost surface.  The result is diagnostic-only and is not consumed by Derby's
 * optimizer.</p>
 */
public final class DelosHeapCostProofLookup {
    public static final String HEAP_COST_PROOF_PROBE_PROPERTY =
            "delosdb.storage.phase.h3.heapCostProofProbe";

    private static final String DELOS_MVCC_PROVIDER = "delos_mvcc";
    private static final AtomicReference<Result> LAST_LOOKUP = new AtomicReference<>();
    private static final AtomicInteger LOOKUP_COUNT = new AtomicInteger();

    private DelosHeapCostProofLookup() {
    }

    public static Optional<Result> observeIfEnabled(
            TableDescriptor tableDescriptor,
            CostEstimate derbyCostEstimate) {
        if (!Boolean.getBoolean(HEAP_COST_PROOF_PROBE_PROPERTY)) {
            return Optional.empty();
        }
        Optional<Result> result = estimate(tableDescriptor, derbyCostEstimate);
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
            CostEstimate derbyCostEstimate) {
        if (tableDescriptor == null || isDelosMvcc(tableDescriptor.getStorageProviderName())) {
            return Optional.empty();
        }

        long derbyRows = nonNegativeLong(derbyCostEstimate == null ? 0.0d : derbyCostEstimate.rowCount());
        double derbyCost = nonNegativeDouble(derbyCostEstimate == null ? 0.0d : derbyCostEstimate.getEstimatedCost());
        EngineHeapTableAccessProof heapProof = new EngineHeapTableAccessProof(
                DelosTableIdentity.of(tableDescriptor.getSchemaName(), tableDescriptor.getName()),
                tableShape(tableDescriptor));
        DelosAccessContext context = DelosAccessContext.builder(true)
                .put(EngineHeapTableAccessProof.ESTIMATED_ROW_COUNT_KEY, derbyRows)
                .put(EngineHeapTableAccessProof.ESTIMATED_SCAN_COST_KEY, derbyCost)
                .build();
        DelosTableCostEstimate estimate = heapProof.estimateTableCost(context);
        return Optional.of(new Result(
                tableDescriptor.getSchemaName(),
                tableDescriptor.getName(),
                EngineHeapTableAccessProof.PROVIDER_NAME,
                estimate.logicalRowCount(),
                estimate.visibleRowCount(),
                estimate.physicalVersionCount(),
                estimate.deadVersionEstimate(),
                estimate.estimatedFullScanCost(),
                derbyCost,
                derbyRows,
                heapProof.capabilities().supports(DelosTableCapability.COSTABLE),
                false,
                true));
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

    private static boolean isDelosMvcc(String providerName) {
        return DELOS_MVCC_PROVIDER.equals(normalizeProvider(providerName));
    }

    private static String normalizeProvider(String providerName) {
        if (providerName == null) {
            return "default";
        }
        String normalized = providerName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "default" : normalized;
    }

    private static long nonNegativeLong(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return 0L;
        }
        return Math.max(0L, Math.round(value));
    }

    private static double nonNegativeDouble(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return 0.0d;
        }
        return value;
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
            long derbyRowCount,
            boolean costableCapabilityAdvertised,
            boolean consumedByDerbyOptimizer,
            boolean proofOnly) {
        public Result {
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(tableName, "tableName");
            storageProviderName = normalizeProvider(storageProviderName);
            if (logicalRowCount < 0L
                    || visibleRowCount < 0L
                    || physicalVersionCount < 0L
                    || deadVersionEstimate < 0L
                    || estimatedFullScanCost < 0L
                    || derbyRowCount < 0L) {
                throw new IllegalArgumentException("heap proof cost values must be non-negative");
            }
        }

        public String qualifiedTableName() {
            return schemaName + "." + tableName;
        }

        public String decision() {
            return consumedByDerbyOptimizer ? "consumed" : "proof-only";
        }
    }
}
