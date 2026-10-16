/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageLifecycleConsistencySnapshot

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

import java.util.Objects;

/**
 * Read-only storage lifecycle snapshot for a single heap or MVCC target.
 *
 * <p>The snapshot aggregates lifecycle signals which already exist elsewhere:
 * checkpoint status, purge/vacuum state,
 * analyze/update-statistics diagnostics, backup status, and consistency.  It is
 * a reporting shape only; it is not storage authority and it must not change
 * optimizer, recovery, backup, heap, or MVCC behavior.</p>
 */
public record DelosStorageLifecycleConsistencySnapshot(
        String providerId,
        int segment,
        long containerId,
        String checkpointStatus,
        boolean checkpointObserved,
        long purgeQueuePendingCount,
        long purgeDaemonRunCount,
        boolean purgeObserved,
        String purgeSummary,
        boolean analyzeObserved,
        long analyzeUpdateCount,
        String analyzeSummary,
        String backupStatus,
        boolean consistent,
        String consistencySummary,
        String summary) {
    public DelosStorageLifecycleConsistencySnapshot {
        providerId = DelosStorageProviderIds.normalize(providerId);
        checkpointStatus = normalize(checkpointStatus);
        purgeSummary = normalize(purgeSummary);
        analyzeSummary = normalize(analyzeSummary);
        backupStatus = normalize(backupStatus);
        consistencySummary = normalize(consistencySummary);
        summary = normalize(summary);
        if (purgeQueuePendingCount < 0L
                || purgeDaemonRunCount < 0L
                || analyzeUpdateCount < 0L) {
            throw new IllegalArgumentException("lifecycle diagnostic counts must not be negative");
        }
    }

    public static DelosStorageLifecycleConsistencySnapshot fromDiagnostics(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId) {
        return fromDiagnostics(diagnostics, segment, containerId, "not-evaluated");
    }

    public static DelosStorageLifecycleConsistencySnapshot fromDiagnostics(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId,
            String backupStatus) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        String providerId = diagnostics.providerId();
        String checkpointStatus = diagnostics.checkpointStatusForTesting(segment, containerId);
        DelosVacuumOutcome vacuum = diagnostics.lastVacuumOutcomeForTesting(segment, containerId);
        long purgeQueuePending = diagnostics.purgeQueuePendingCountForTesting(segment, containerId);
        long purgeDaemonRuns = diagnostics.purgeDaemonRunCountForTesting(segment, containerId);
        String purgeSummary = "vacuumSkipped=" + vacuum.skipped()
                + " reason=" + vacuum.reason()
                + " removedVersions=" + vacuum.removedVersions()
                + " remainingVersions=" + vacuum.remainingVersions()
                + " queuePending=" + purgeQueuePending
                + " daemonRuns=" + purgeDaemonRuns
                + " daemonDecision=" + diagnostics.purgeDaemonLastDecisionForTesting(segment, containerId);
        boolean purgeObserved = purgeQueuePending > 0L
                || diagnostics.purgeQueueEnqueueCountForTesting(segment, containerId) > 0L
                || diagnostics.purgeQueueDrainCountForTesting(segment, containerId) > 0L
                || purgeDaemonRuns > 0L
                || vacuum.removedVersions() > 0
                || !"none".equalsIgnoreCase(vacuum.reason());
        boolean analyzeObserved = DelosStorageProviderIds.isMvcc(providerId)
                && DelosMvccAnalyzeStatisticsLifecycleDiagnostics.explicitUpdateCountForTesting() > 0L
                && DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastContainerIdForTesting() == containerId;
        long analyzeUpdateCount = analyzeObserved
                ? DelosMvccAnalyzeStatisticsLifecycleDiagnostics.explicitUpdateCountForTesting()
                : 0L;
        String analyzeSummary = analyzeObserved
                ? DelosMvccAnalyzeStatisticsLifecycleDiagnostics.lastSummaryForTesting()
                : "not-observed";
        boolean consistent = diagnostics.consistencyErrorCountForTesting(segment, containerId) == 0;
        String consistencySummary = diagnostics.consistencySummaryForTesting(segment, containerId);
        boolean checkpointObserved = checkpointObserved(checkpointStatus);
        String summary = "provider=" + DelosStorageProviderIds.normalize(providerId)
                + " segment=" + segment
                + " container=" + containerId
                + " checkpoint=" + checkpointStatus
                + " checkpointObserved=" + checkpointObserved
                + " purgeObserved=" + purgeObserved
                + " analyzeObserved=" + analyzeObserved
                + " backup=" + normalize(backupStatus)
                + " consistent=" + consistent;
        return new DelosStorageLifecycleConsistencySnapshot(
                providerId,
                segment,
                containerId,
                checkpointStatus,
                checkpointObserved,
                purgeQueuePending,
                purgeDaemonRuns,
                purgeObserved,
                purgeSummary,
                analyzeObserved,
                analyzeUpdateCount,
                analyzeSummary,
                backupStatus,
                consistent,
                consistencySummary,
                summary);
    }

    public boolean clean() {
        return consistent && checkpointObserved;
    }

    private static boolean checkpointObserved(String checkpointStatus) {
        String normalized = normalize(checkpointStatus);
        return !normalized.isBlank()
                && !"ABSENT".equalsIgnoreCase(normalized)
                && !"DISABLED".equalsIgnoreCase(normalized)
                && !"INCOMPLETE".equalsIgnoreCase(normalized)
                && !normalized.toUpperCase(java.util.Locale.ROOT).contains("MISSING");
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
