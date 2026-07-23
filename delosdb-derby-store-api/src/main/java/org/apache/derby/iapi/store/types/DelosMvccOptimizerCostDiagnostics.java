/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccOptimizerCostDiagnostics

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
package org.apache.derby.iapi.store.types;

/**
 * Testing/diagnostic surface for the explicit MVCC StoreCostController
 * statistics bridge.
 *
 * <p>The bridge is disabled by default.  When enabled, MVCC statistics are fed
 * into Derby's inherited {@code StoreCostController} path instead of creating a
 * parallel optimizer statistics channel.</p>
 */
public final class DelosMvccOptimizerCostDiagnostics {
    public static final String PROPERTY_NAME = "delosdb.mvcc.optimizer.storageStatistics.enabled";

    private static long statisticsEstimateCount;
    private static long lastConglomerateId;
    private static long lastLogicalRowCount;
    private static long lastPhysicalVersionCount;
    private static long lastOrderedIndexEntryCount;
    private static double lastEstimatedCost;
    private static long lastEstimatedRows;
    private static String lastProviderId = "";
    private static String lastSummary = "";

    private DelosMvccOptimizerCostDiagnostics() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY_NAME);
    }

    public static synchronized void recordStatisticsEstimate(
            long conglomerateId,
            DelosStorageStatistics statistics,
            double estimatedCost,
            long estimatedRows) {
        statisticsEstimateCount++;
        lastConglomerateId = conglomerateId;
        lastProviderId = statistics.providerId();
        lastLogicalRowCount = statistics.logicalRowCount();
        lastPhysicalVersionCount = statistics.physicalVersionCount();
        lastOrderedIndexEntryCount = statistics.orderedIndexEntryCount();
        lastEstimatedCost = estimatedCost;
        lastEstimatedRows = estimatedRows;
        lastSummary = "DelosDBMvccStoreCost{"
                + "path=store-cost-controller"
                + ", source=mvcc-storage-statistics"
                + ", provider=" + statistics.providerId()
                + ", conglomId=" + conglomerateId
                + ", logicalRows=" + statistics.logicalRowCount()
                + ", physicalVersions=" + statistics.physicalVersionCount()
                + ", orderedIndexEntries=" + statistics.orderedIndexEntryCount()
                + ", estimatedCost=" + estimatedCost
                + ", estimatedRows=" + estimatedRows
                + "}";
    }

    public static synchronized void clearForTesting() {
        statisticsEstimateCount = 0L;
        lastConglomerateId = 0L;
        lastLogicalRowCount = 0L;
        lastPhysicalVersionCount = 0L;
        lastOrderedIndexEntryCount = 0L;
        lastEstimatedCost = 0.0d;
        lastEstimatedRows = 0L;
        lastProviderId = "";
        lastSummary = "";
    }

    public static synchronized long statisticsEstimateCountForTesting() {
        return statisticsEstimateCount;
    }

    public static synchronized long lastConglomerateIdForTesting() {
        return lastConglomerateId;
    }

    public static synchronized String lastProviderIdForTesting() {
        return lastProviderId;
    }

    public static synchronized long lastLogicalRowCountForTesting() {
        return lastLogicalRowCount;
    }

    public static synchronized long lastPhysicalVersionCountForTesting() {
        return lastPhysicalVersionCount;
    }

    public static synchronized long lastOrderedIndexEntryCountForTesting() {
        return lastOrderedIndexEntryCount;
    }

    public static synchronized double lastEstimatedCostForTesting() {
        return lastEstimatedCost;
    }

    public static synchronized long lastEstimatedRowsForTesting() {
        return lastEstimatedRows;
    }

    public static synchronized String lastSummaryForTesting() {
        return lastSummary;
    }
}
