/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics

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

/** Testing and diagnostic surface for a concrete provider-owned storage table. */
public interface DelosStorageTableDiagnostics {
    Path pageVolumeStateFileForTesting();

    Path rowDirectoryStateFileForTesting();

    Path reusablePageIndexFileForTesting();

    Path freeSpaceMapFileForTesting();

    Path visibilityMapFileForTesting();

    default Path purgeQueueFileForTesting() {
        return null;
    }

    default Path orderedIndexPagesFileForTesting() {
        return null;
    }

    Path pageMutationLogFileForTesting();

    Path writeAheadLogFileForTesting();

    Path checkpointFileForTesting();

    String checkpointStatusForTesting();

    int physicalVersionCountForTesting();

    int logicalRowCountForTesting();

    /**
     * Stable diagnostic summaries of rows visible in the provider-owned page-backed
     * committed image.  This is a testing/inspection hook, not a production row API.
     */
    default List<String> pageBackedVisibleRowSummariesForTesting() {
        return List.of();
    }

    default int lastCommittedChangedRowCountForTesting() {
        return 0;
    }

    default int lastCommittedWriteIntentCountForTesting() {
        return 0;
    }

    default List<String> lastCommittedWriteIntentPayloadSummariesForTesting() {
        return List.of();
    }

    default int activeProviderWriteAppendCountForTesting() {
        return 0;
    }

    default List<String> activeProviderWriteAppendPayloadSummariesForTesting() {
        return List.of();
    }

    default int activeProviderSurvivingWriteIntentCountForTesting() {
        return 0;
    }

    default List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting() {
        return List.of();
    }

    default int providerFirstWriteAppendCountForTesting() {
        return 0;
    }

    default int legacyWriteFrontShadowMutationCountForTesting() {
        return 0;
    }

    default int legacyWriteFrontShadowBypassCountForTesting() {
        return 0;
    }

    default boolean legacyWriteFrontShadowEnabledForTesting() {
        return false;
    }

    default int legacyWriteFrontQuarantineViolationCountForTesting() {
        return 0;
    }

    default int providerFirstWriteAppendFailureRollbackCountForTesting() {
        return 0;
    }

    default int transactionLocalWriteIntentReadCountForTesting() {
        return 0;
    }

    default int transactionLocalWriteIntentScanCountForTesting() {
        return 0;
    }

    default int transactionLocalPageBackedBaseReadCountForTesting() {
        return 0;
    }

    default int transactionLocalPageBackedBaseScanCountForTesting() {
        return 0;
    }

    default int pageBackedHistoricalSnapshotReadCountForTesting() {
        return 0;
    }

    default int pageBackedHistoricalSnapshotScanCountForTesting() {
        return 0;
    }

    default int legacySnapshotFallbackReadCountForTesting() {
        return 0;
    }

    default int legacySnapshotFallbackScanCountForTesting() {
        return 0;
    }

    default int pageBackedCandidateIndexRebuildCountForTesting() {
        return 0;
    }

    default int legacyCandidateIndexRebuildCountForTesting() {
        return 0;
    }

    long pageCountForTesting();

    long overflowPageCountForTesting();

    long reusablePageCountForTesting();

    long freeSpaceMapPageCountForTesting();

    int freeSpaceMapMaxFreeBytesForTesting();

    long freeSpaceMapLookupCountForTesting();

    long freeSpaceMapHitCountForTesting();

    long freeSpaceMapNonLastHitCountForTesting();

    long freeSpaceMapMissCountForTesting();

    long freeSpaceMapStaleEntryCountForTesting();

    long freeSpaceMapUpdateCountForTesting();

    long freeSpaceMapRebuildCountForTesting();

    default java.util.List<String> freeSpaceMapPageSummariesForTesting() {
        return java.util.List.of();
    }

    long visibilityMapPageCountForTesting();

    long visibilityMapOldVersionPageCountForTesting();

    long visibilityMapPrunablePageCountForTesting();

    long visibilityMapTombstonePageCountForTesting();

    long visibilityMapAllVisiblePageCountForTesting();

    long visibilityMapOverflowPageCountForTesting();

    long visibilityMapNeedsCheckerPageCountForTesting();

    long visibilityMapUpdateCountForTesting();

    long visibilityMapRebuildCountForTesting();

    default java.util.List<String> visibilityMapPageSummariesForTesting() {
        return java.util.List.of();
    }

    default long pageLocalPruneAttemptCountForTesting() {
        return 0L;
    }

    default long pageLocalPruneSuccessCountForTesting() {
        return 0L;
    }

    default long pageLocalPruneFallbackCountForTesting() {
        return 0L;
    }

    default long pageLocalPruneRemovedVersionCountForTesting() {
        return 0L;
    }

    default long pageMutationContextBeginCountForTesting() {
        return 0L;
    }

    default long pageMutationContextCommitCountForTesting() {
        return 0L;
    }

    default long pageMutationContextAbortCountForTesting() {
        return 0L;
    }

    default long pageMutationContextPageReservationCountForTesting() {
        return 0L;
    }

    default long pageMutationContextReservedBytesForTesting() {
        return 0L;
    }

    default long pageMutationContextPageWriteCountForTesting() {
        return 0L;
    }

    default long pageMutationContextFreeSpaceMapUpdateCountForTesting() {
        return 0L;
    }

    default long pageMutationContextReusableIndexUpdateCountForTesting() {
        return 0L;
    }

    default String lastPageMutationContextOperationForTesting() {
        return "none";
    }

    default long purgeQueuePendingCountForTesting() {
        return 0L;
    }

    default long purgeQueueEnqueueCountForTesting() {
        return 0L;
    }

    default long purgeQueueDrainCountForTesting() {
        return 0L;
    }

    default long purgeQueueLastDrainCountForTesting() {
        return 0L;
    }

    default List<String> purgeQueueEntrySummariesForTesting() {
        return List.of();
    }

    default long orderedIndexPageCountForTesting() {
        return 0L;
    }

    default long orderedIndexEntryCountForTesting() {
        return 0L;
    }

    default int orderedIndexDistinctKeyCountForTesting() {
        return 0;
    }

    default long orderedIndexRebuildCountForTesting() {
        return 0L;
    }

    default List<String> orderedIndexEntrySummariesForTesting() {
        return List.of();
    }

    default long orderedIndexLookupCountForTesting() {
        return 0L;
    }

    default long orderedIndexHitCountForTesting() {
        return 0L;
    }

    default long orderedIndexFallbackCountForTesting() {
        return 0L;
    }

    default long orderedIndexRowIdCountForTesting() {
        return 0L;
    }

    default int orderedIndexCandidateParityErrorCountForTesting() {
        return 0;
    }

    default List<String> orderedIndexCandidateParityErrorSummariesForTesting() {
        return List.of();
    }

    default DelosStorageOrderedIndexDiagnostics orderedIndexDiagnosticsForTesting() {
        return new DelosStorageOrderedIndexDiagnostics(
                orderedIndexPageCountForTesting(),
                orderedIndexEntryCountForTesting(),
                orderedIndexDistinctKeyCountForTesting(),
                orderedIndexRebuildCountForTesting());
    }

    long pageCacheMaxPageCountForTesting();

    long pageCacheSizeForTesting();

    long pageCacheHitCountForTesting();

    long pageCacheMissCountForTesting();

    long pageCacheWriteCountForTesting();

    long pageCacheEvictionCountForTesting();

    long pageCacheInvalidationCountForTesting();

    default long pageCachePinCountForTesting() {
        return 0L;
    }

    default long pageCacheUnpinCountForTesting() {
        return 0L;
    }

    default long pageCachePinnedPageCountForTesting() {
        return 0L;
    }

    default long pageCacheDirtyPageCountForTesting() {
        return 0L;
    }

    default long pageCacheFlushListPageCountForTesting() {
        return 0L;
    }

    default long pageCacheFlushCountForTesting() {
        return 0L;
    }

    default long pageCachePinnedEvictionSkipCountForTesting() {
        return 0L;
    }

    default long pageCacheLastPageGenerationForTesting() {
        return 0L;
    }

    int consistencyErrorCountForTesting();

    String consistencySummaryForTesting();

    void assertConsistentForTesting();

    default DelosStoragePageDiagnostics pageDiagnosticsForTesting() {
        return new DelosStoragePageDiagnostics(
                pageCountForTesting(),
                overflowPageCountForTesting(),
                reusablePageCountForTesting(),
                physicalVersionCountForTesting(),
                logicalRowCountForTesting());
    }

    default DelosStorageFreeSpaceDiagnostics freeSpaceDiagnosticsForTesting() {
        return new DelosStorageFreeSpaceDiagnostics(
                freeSpaceMapPageCountForTesting(),
                freeSpaceMapMaxFreeBytesForTesting(),
                freeSpaceMapLookupCountForTesting(),
                freeSpaceMapHitCountForTesting(),
                freeSpaceMapNonLastHitCountForTesting(),
                freeSpaceMapMissCountForTesting(),
                freeSpaceMapStaleEntryCountForTesting(),
                freeSpaceMapUpdateCountForTesting(),
                freeSpaceMapRebuildCountForTesting());
    }

    default DelosStorageVisibilityDiagnostics visibilityDiagnosticsForTesting() {
        return new DelosStorageVisibilityDiagnostics(
                visibilityMapPageCountForTesting(),
                visibilityMapOldVersionPageCountForTesting(),
                visibilityMapPrunablePageCountForTesting(),
                visibilityMapTombstonePageCountForTesting(),
                visibilityMapAllVisiblePageCountForTesting(),
                visibilityMapOverflowPageCountForTesting(),
                visibilityMapNeedsCheckerPageCountForTesting(),
                visibilityMapUpdateCountForTesting(),
                visibilityMapRebuildCountForTesting());
    }

    default DelosStoragePageCacheDiagnostics pageCacheDiagnosticsForTesting() {
        return new DelosStoragePageCacheDiagnostics(
                pageCacheMaxPageCountForTesting(),
                pageCacheSizeForTesting(),
                pageCacheHitCountForTesting(),
                pageCacheMissCountForTesting(),
                pageCacheWriteCountForTesting(),
                pageCacheEvictionCountForTesting(),
                pageCacheInvalidationCountForTesting(),
                pageCachePinCountForTesting(),
                pageCacheUnpinCountForTesting(),
                pageCachePinnedPageCountForTesting(),
                pageCacheDirtyPageCountForTesting(),
                pageCacheFlushListPageCountForTesting(),
                pageCacheFlushCountForTesting(),
                pageCachePinnedEvictionSkipCountForTesting(),
                pageCacheLastPageGenerationForTesting());
    }

    default DelosStorageConsistencyDiagnostics consistencyDiagnosticsForTesting() {
        return new DelosStorageConsistencyDiagnostics(
                consistencyErrorCountForTesting(),
                consistencySummaryForTesting());
    }

    DelosVacuumOutcome lastVacuumOutcomeForTesting();

    Path legacySnapshotFileForTesting();
}
