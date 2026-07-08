/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccAnalyzeStatisticsLifecycleDiagnostics

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
 * Diagnostic checkpoint for the MVCC analyze/update-statistics lifecycle.
 *
 * <p>Derby remains the statistics/catalog/optimizer authority.  This class only
 * records that an inherited explicit index-statistics refresh reached an MVCC
 * table and that DelosDB could take a read-only MVCC storage-statistics snapshot
 * at that lifecycle boundary.  It does not write SYSSTATISTICS, change plan
 * selection, or create a parallel optimizer statistics store.</p>
 */
public final class DelosMvccAnalyzeStatisticsLifecycleDiagnostics {
    private static long explicitUpdateCount;
    private static long ignoredNonMvccUpdateCount;
    private static long diagnosticFailureCount;
    private static String lastProviderId = "";
    private static String lastQualifiedTableName = "";
    private static String lastRunContext = "";
    private static long lastContainerId;
    private static long lastLogicalRowCount;
    private static long lastPhysicalVersionCount;
    private static long lastOrderedIndexEntryCount;
    private static long lastEstimatedFullScanCost;
    private static long lastEstimatedIndexLookupCost;
    private static String lastFailure = "";
    private static String lastSummary = "";

    private DelosMvccAnalyzeStatisticsLifecycleDiagnostics() {
    }

    public static void recordExplicitUpdateStatistics(
            String storageProviderName,
            String qualifiedTableName,
            long containerId,
            String runContext) {
        String providerId = DelosStorageProviderIds.normalize(storageProviderName);
        if (!DelosStorageProviderIds.isMvcc(providerId)) {
            synchronized (DelosMvccAnalyzeStatisticsLifecycleDiagnostics.class) {
                ignoredNonMvccUpdateCount++;
            }
            return;
        }

        try {
            DelosStorageStatistics statistics = DelosStorageDiagnosticsRegistry.statisticsForMvcc(0, containerId);
            DelosStorageCostEstimate estimate = DelosStorageCostEstimate.fromStatistics(statistics, true);
            synchronized (DelosMvccAnalyzeStatisticsLifecycleDiagnostics.class) {
                explicitUpdateCount++;
                lastProviderId = statistics.providerId();
                lastQualifiedTableName = valueOrEmpty(qualifiedTableName);
                lastRunContext = valueOrEmpty(runContext);
                lastContainerId = containerId;
                lastLogicalRowCount = statistics.logicalRowCount();
                lastPhysicalVersionCount = statistics.physicalVersionCount();
                lastOrderedIndexEntryCount = statistics.orderedIndexEntryCount();
                lastEstimatedFullScanCost = estimate.estimatedFullScanCost();
                lastEstimatedIndexLookupCost = estimate.estimatedIndexLookupCost();
                lastFailure = "";
                lastSummary = "DelosDBMvccAnalyzeStatistics{"
                        + "path=derby-index-statistics-refresher"
                        + ", source=mvcc-storage-statistics"
                        + ", provider=" + statistics.providerId()
                        + ", table=" + lastQualifiedTableName
                        + ", conglomId=" + containerId
                        + ", logicalRows=" + statistics.logicalRowCount()
                        + ", physicalVersions=" + statistics.physicalVersionCount()
                        + ", orderedIndexEntries=" + statistics.orderedIndexEntryCount()
                        + ", estimatedFullScanCost=" + estimate.estimatedFullScanCost()
                        + ", estimatedIndexLookupCost=" + estimate.estimatedIndexLookupCost()
                        + ", runContext=" + lastRunContext
                        + ", optimizerAuthority=derby"
                        + "}";
            }
        } catch (RuntimeException e) {
            recordDiagnosticFailure(providerId, qualifiedTableName, containerId, runContext, e);
        }
    }

    public static void recordDiagnosticFailure(
            String storageProviderName,
            String qualifiedTableName,
            long containerId,
            String runContext,
            Throwable failure) {
        String providerId = DelosStorageProviderIds.normalize(storageProviderName);
        if (!DelosStorageProviderIds.isMvcc(providerId)) {
            synchronized (DelosMvccAnalyzeStatisticsLifecycleDiagnostics.class) {
                ignoredNonMvccUpdateCount++;
            }
            return;
        }
        synchronized (DelosMvccAnalyzeStatisticsLifecycleDiagnostics.class) {
            diagnosticFailureCount++;
            lastProviderId = providerId;
            lastQualifiedTableName = valueOrEmpty(qualifiedTableName);
            lastRunContext = valueOrEmpty(runContext);
            lastContainerId = containerId;
            lastFailure = failure == null
                    ? ""
                    : failure.getClass().getName() + ": " + valueOrEmpty(failure.getMessage());
            lastSummary = "DelosDBMvccAnalyzeStatistics{"
                    + "path=derby-index-statistics-refresher"
                    + ", source=mvcc-storage-statistics"
                    + ", provider=" + providerId
                    + ", table=" + lastQualifiedTableName
                    + ", conglomId=" + containerId
                    + ", runContext=" + lastRunContext
                    + ", diagnosticFailure=true"
                    + ", optimizerAuthority=derby"
                    + "}";
        }
    }

    public static synchronized void clearForTesting() {
        explicitUpdateCount = 0L;
        ignoredNonMvccUpdateCount = 0L;
        diagnosticFailureCount = 0L;
        lastProviderId = "";
        lastQualifiedTableName = "";
        lastRunContext = "";
        lastContainerId = 0L;
        lastLogicalRowCount = 0L;
        lastPhysicalVersionCount = 0L;
        lastOrderedIndexEntryCount = 0L;
        lastEstimatedFullScanCost = 0L;
        lastEstimatedIndexLookupCost = 0L;
        lastFailure = "";
        lastSummary = "";
    }

    public static synchronized long explicitUpdateCountForTesting() {
        return explicitUpdateCount;
    }

    public static synchronized long ignoredNonMvccUpdateCountForTesting() {
        return ignoredNonMvccUpdateCount;
    }

    public static synchronized long diagnosticFailureCountForTesting() {
        return diagnosticFailureCount;
    }

    public static synchronized String lastProviderIdForTesting() {
        return lastProviderId;
    }

    public static synchronized String lastQualifiedTableNameForTesting() {
        return lastQualifiedTableName;
    }

    public static synchronized String lastRunContextForTesting() {
        return lastRunContext;
    }

    public static synchronized long lastContainerIdForTesting() {
        return lastContainerId;
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

    public static synchronized long lastEstimatedFullScanCostForTesting() {
        return lastEstimatedFullScanCost;
    }

    public static synchronized long lastEstimatedIndexLookupCostForTesting() {
        return lastEstimatedIndexLookupCost;
    }

    public static synchronized String lastFailureForTesting() {
        return lastFailure;
    }

    public static synchronized String lastSummaryForTesting() {
        return lastSummary;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
