/*

   Derby - Class org.apache.derby.impl.sql.execute.DelosHeapScanShadowResultSet

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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.shared.common.error.StandardException;

/**
 * M2 property-gated heap scan shadow branch.
 *
 * <p>This class deliberately extends Derby's own {@link TableScanResultSet} and
 * does not route heap SQL through the Delos table contract yet.  The branch is
 * only enabled by {@link #HEAP_SCAN_SHADOW_PROPERTY}, only for the default heap
 * provider, and only for read-only base table scans.  Its purpose is to prove
 * that {@link GenericResultSetFactory#getTableScanResultSet} can select a heap
 * shadow result-set shape without changing ordinary heap behavior when the
 * property is disabled.</p>
 */
final class DelosHeapScanShadowResultSet extends TableScanResultSet {
    static final String HEAP_SCAN_SHADOW_PROPERTY =
            "delosdb.storage.phaseM.heapScanShadow";

    private static final AtomicInteger SHADOW_BRANCH_COUNT = new AtomicInteger();
    private static final AtomicReference<DelosTableScanProviderLookup.Result> LAST_SHADOW_LOOKUP =
            new AtomicReference<>();

    private DelosHeapScanShadowResultSet(
            TableScanResultSetParameters params,
            DelosTableScanProviderLookup.Result providerLookup)
            throws StandardException {
        super(params);
        SHADOW_BRANCH_COUNT.incrementAndGet();
        LAST_SHADOW_LOOKUP.set(Objects.requireNonNull(providerLookup, "providerLookup"));
    }

    static Optional<NoPutResultSet> createIfEnabled(TableScanResultSetParameters params)
            throws StandardException {
        if (!Boolean.getBoolean(HEAP_SCAN_SHADOW_PROPERTY)) {
            return Optional.empty();
        }
        if (params.forUpdate || hasIndexName(params.indexName)) {
            return Optional.empty();
        }

        Optional<DelosTableScanProviderLookup.Result> lookup =
                DelosTableScanProviderLookup.find(params.activation, params.tableName);
        if (lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()) {
            return Optional.empty();
        }

        return Optional.of(new DelosHeapScanShadowResultSet(params, lookup.get()));
    }

    private static boolean hasIndexName(String indexName) {
        return indexName != null && !indexName.trim().isEmpty();
    }

    static void resetForTesting() {
        SHADOW_BRANCH_COUNT.set(0);
        LAST_SHADOW_LOOKUP.set(null);
    }

    static int shadowBranchCountForTesting() {
        return SHADOW_BRANCH_COUNT.get();
    }

    static Optional<DelosTableScanProviderLookup.Result> lastShadowLookupForTesting() {
        return Optional.ofNullable(LAST_SHADOW_LOOKUP.get());
    }
}
