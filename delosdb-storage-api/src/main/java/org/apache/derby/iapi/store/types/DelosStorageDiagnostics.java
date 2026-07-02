/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageDiagnostics

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

import java.nio.file.Path;
import java.util.List;

/**
 * Provider-neutral diagnostics surface for storage-provider smoke fixtures.
 *
 * <p>This interface is intentionally not part of the production storage path.
 * It gives verification code a stable storage-api boundary for provider state
 * files, counters, and runtime observations without importing temporary bridge
 * implementation classes directly.</p>
 */
public interface DelosStorageDiagnostics {
    String providerId();

    /**
     * Optional database-directory context for file-based compatibility inspectors.
     * Providers which do not need a database directory may ignore this hook.
     */
    default void setDatabaseDirectoryForTesting(Path databaseDirectory) {
    }

    default void clearDatabaseDirectoryForTesting() {
    }

    void clearRuntimeStateForTesting();

    int runtimeStateCountForTesting();

    Path pageVolumeStateFileForTesting(int segment, long containerId);

    Path rowDirectoryStateFileForTesting(int segment, long containerId);

    Path reusablePageIndexFileForTesting(int segment, long containerId);

    Path pageMutationLogFileForTesting(int segment, long containerId);

    Path writeAheadLogFileForTesting(int segment, long containerId);

    Path checkpointFileForTesting(int segment, long containerId);

    Path legacySnapshotFileForTesting(int segment, long containerId);

    String checkpointStatusForTesting(int segment, long containerId);

    int physicalVersionCountForTesting(int segment, long containerId);

    int logicalRowCountForTesting(int segment, long containerId);

    default List<String> pageBackedVisibleRowSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default int lastCommittedChangedRowCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int lastCommittedWriteIntentCountForTesting(int segment, long containerId) {
        return 0;
    }

    default List<String> lastCommittedWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default int transactionLocalWriteIntentReadCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int transactionLocalWriteIntentScanCountForTesting(int segment, long containerId) {
        return 0;
    }

    long pageCountForTesting(int segment, long containerId);

    long overflowPageCountForTesting(int segment, long containerId);

    long reusablePageCountForTesting(int segment, long containerId);

    long pageCacheMaxPageCountForTesting(int segment, long containerId);

    long pageCacheSizeForTesting(int segment, long containerId);

    long pageCacheHitCountForTesting(int segment, long containerId);

    long pageCacheMissCountForTesting(int segment, long containerId);

    long pageCacheWriteCountForTesting(int segment, long containerId);

    long pageCacheEvictionCountForTesting(int segment, long containerId);

    long pageCacheInvalidationCountForTesting(int segment, long containerId);

    int consistencyErrorCountForTesting(int segment, long containerId);

    String consistencySummaryForTesting(int segment, long containerId);

    void assertConsistentForTesting(int segment, long containerId);

    default DelosStoragePageDiagnostics pageDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStoragePageDiagnostics(
                pageCountForTesting(segment, containerId),
                overflowPageCountForTesting(segment, containerId),
                reusablePageCountForTesting(segment, containerId),
                physicalVersionCountForTesting(segment, containerId),
                logicalRowCountForTesting(segment, containerId));
    }

    default DelosStoragePageCacheDiagnostics pageCacheDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStoragePageCacheDiagnostics(
                pageCacheMaxPageCountForTesting(segment, containerId),
                pageCacheSizeForTesting(segment, containerId),
                pageCacheHitCountForTesting(segment, containerId),
                pageCacheMissCountForTesting(segment, containerId),
                pageCacheWriteCountForTesting(segment, containerId),
                pageCacheEvictionCountForTesting(segment, containerId),
                pageCacheInvalidationCountForTesting(segment, containerId));
    }

    default DelosStorageConsistencyDiagnostics consistencyDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageConsistencyDiagnostics(
                consistencyErrorCountForTesting(segment, containerId),
                consistencySummaryForTesting(segment, containerId));
    }

    default DelosVacuumOutcome lastVacuumOutcomeForTesting(int segment, long containerId) {
        return new DelosVacuumOutcome(
                lastVacuumSkippedForTesting(segment, containerId),
                lastVacuumReasonForTesting(segment, containerId),
                lastVacuumRemovedVersionsForTesting(segment, containerId),
                lastVacuumRemainingVersionsForTesting(segment, containerId));
    }

    boolean lastVacuumSkippedForTesting(int segment, long containerId);

    String lastVacuumReasonForTesting(int segment, long containerId);

    int lastVacuumRemovedVersionsForTesting(int segment, long containerId);

    int lastVacuumRemainingVersionsForTesting(int segment, long containerId);

    void resetMutationCountersForTesting();

    int insertCountForTesting();

    int updateCountForTesting();

    int deleteCountForTesting();

    void resetScanCountersForTesting();

    int scanOpenCountForTesting();

    void resetQualifierRejectCountForTesting();

    int qualifierRejectCountForTesting();

    void resetCandidateIndexCountersForTesting();

    int candidateIndexLookupCountForTesting();

    int candidateIndexRowIdCountForTesting();

    default int candidateIndexKeyCountForTesting(int segment, long containerId) {
        return 0;
    }

    int candidateIndexVisibilityRejectCountForTesting();

    int candidateIndexQualifierRejectCountForTesting();

    default int pageBackedCommittedScanCountForTesting() {
        return 0;
    }

    default int pageBackedCommittedReadCountForTesting() {
        return 0;
    }

    void clearTransactionsForTesting();

    boolean isProviderScan(Object scanController);

    boolean hasLocatorHint(StoreRowLocation location);
}
