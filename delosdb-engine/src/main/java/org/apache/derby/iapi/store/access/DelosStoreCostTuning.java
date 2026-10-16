/*

   Derby - Class org.apache.derby.iapi.store.access.DelosStoreCostTuning

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

package org.apache.derby.iapi.store.access;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.services.context.StoreExecutionContext;

/**
 * DelosDB's narrow cost-tuning adapter for inherited Derby store costing.
 *
 * <p>H4 deliberately makes one existing Derby store-cost constant observable
 * through the current SQL session.  This is not a provider-specific cost model
 * and does not replace the Derby optimizer's {@link org.apache.derby.iapi.sql.compile.CostEstimate};
 * it only redirects the existing uncached-row-fetch constant through a session
 * override when one has been set.</p>
 */
public final class DelosStoreCostTuning {
    public static final double DEFAULT_UNCACHED_ROW_FETCH_COST =
            StoreCostController.BASE_UNCACHED_ROW_FETCH_COST;

    public static final String UNSET_SENTINEL_TEXT = "NaN";

    private static final AtomicInteger UNCACHED_ROW_FETCH_COST_LOOKUPS = new AtomicInteger();
    private static volatile double lastUncachedRowFetchCostForTesting = Double.NaN;

    private DelosStoreCostTuning() {
    }

    public static double uncachedRowFetchCost() {
        double cost = DEFAULT_UNCACHED_ROW_FETCH_COST;
        StoreExecutionContext context = currentStoreExecutionContext();
        if (context != null) {
            double sessionOverride = context.getDelosUncachedRowFetchCostOverride();
            if (isValidCost(sessionOverride)) {
                cost = sessionOverride;
            }
        }
        lastUncachedRowFetchCostForTesting = cost;
        UNCACHED_ROW_FETCH_COST_LOOKUPS.incrementAndGet();
        return cost;
    }

    public static boolean isValidCost(double cost) {
        return Double.isFinite(cost) && cost > 0.0d;
    }

    public static void resetForTesting() {
        UNCACHED_ROW_FETCH_COST_LOOKUPS.set(0);
        lastUncachedRowFetchCostForTesting = Double.NaN;
    }

    public static int uncachedRowFetchCostLookupCountForTesting() {
        return UNCACHED_ROW_FETCH_COST_LOOKUPS.get();
    }

    public static double lastUncachedRowFetchCostForTesting() {
        return lastUncachedRowFetchCostForTesting;
    }

    private static StoreExecutionContext currentStoreExecutionContext() {
        return (StoreExecutionContext) ContextService.getContextOrNull(StoreExecutionContext.CONTEXT_ID);
    }
}
