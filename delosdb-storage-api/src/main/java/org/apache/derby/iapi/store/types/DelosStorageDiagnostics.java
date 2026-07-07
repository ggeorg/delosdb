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
     * Return a diagnostics view bound to an explicit request context.
     *
     * <p>Implementations that need filesystem context, such as the Derby heap
     * compatibility diagnostics, should prefer this method over mutable
     * set/clear hooks. Providers which do not need context may return
     * {@code this}.</p>
     */
    default DelosStorageDiagnostics withContext(DelosStorageDiagnosticsContext context) {
        return this;
    }

    default void clearRuntimeStateForTesting() {
    }

    default int runtimeStateCountForTesting() {
        return 0;
    }

    Path pageVolumeStateFileForTesting(int segment, long containerId);

    Path rowDirectoryStateFileForTesting(int segment, long containerId);

    Path reusablePageIndexFileForTesting(int segment, long containerId);

    default Path freeSpaceMapFileForTesting(int segment, long containerId) {
        return null;
    }

    default Path visibilityMapFileForTesting(int segment, long containerId) {
        return null;
    }

    default Path purgeQueueFileForTesting(int segment, long containerId) {
        return null;
    }

    default Path orderedIndexPagesFileForTesting(int segment, long containerId) {
        return null;
    }

    Path pageMutationLogFileForTesting(int segment, long containerId);

    Path writeAheadLogFileForTesting(int segment, long containerId);

    Path checkpointFileForTesting(int segment, long containerId);

    default Path subsystemRecoveryRecordsFileForTesting(int segment, long containerId) {
        return null;
    }

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

    default int activeProviderWriteAppendCountForTesting(int segment, long containerId) {
        return 0;
    }

    default List<String> activeProviderWriteAppendPayloadSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default int activeProviderSurvivingWriteIntentCountForTesting(int segment, long containerId) {
        return 0;
    }

    default List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default int providerFirstWriteAppendCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int legacyWriteFrontShadowMutationCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int legacyWriteFrontShadowBypassCountForTesting(int segment, long containerId) {
        return 0;
    }

    default boolean legacyWriteFrontShadowEnabledForTesting(int segment, long containerId) {
        return false;
    }

    default int legacyWriteFrontQuarantineViolationCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int providerFirstWriteAppendFailureRollbackCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int transactionLocalWriteIntentReadCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int transactionLocalWriteIntentScanCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int transactionLocalPageBackedBaseReadCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int transactionLocalPageBackedBaseScanCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int pageBackedHistoricalSnapshotReadCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int pageBackedHistoricalSnapshotScanCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int legacySnapshotFallbackReadCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int legacySnapshotFallbackScanCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int pageBackedCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return 0;
    }

    default int legacyCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return 0;
    }

    long pageCountForTesting(int segment, long containerId);

    long overflowPageCountForTesting(int segment, long containerId);

    long reusablePageCountForTesting(int segment, long containerId);

    default long freeSpaceMapPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int freeSpaceMapMaxFreeBytesForTesting(int segment, long containerId) {
        return 0;
    }

    default long freeSpaceMapLookupCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapNonLastHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapMissCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapStaleEntryCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> freeSpaceMapPageSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long visibilityMapPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapOldVersionPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapPrunablePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapTombstonePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapAllVisiblePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapOverflowPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapNeedsCheckerPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> visibilityMapPageSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long pageLocalPruneAttemptCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneSuccessCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneFallbackCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneRemovedVersionCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextBeginCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextCommitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextAbortCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextPageReservationCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextReservedBytesForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextPageWriteCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextFreeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageMutationContextReusableIndexUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default String lastPageMutationContextOperationForTesting(int segment, long containerId) {
        return "none";
    }

    default long purgeQueuePendingCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueEnqueueCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueDrainCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeQueueLastDrainCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> purgeQueueEntrySummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long purgeDaemonScheduleCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonRunCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonSkipCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long purgeDaemonLastTriggerChangedRowsForTesting(int segment, long containerId) {
        return 0L;
    }

    default String purgeDaemonLastDecisionForTesting(int segment, long containerId) {
        return "disabled";
    }

    default long purgeDaemonLastVisibilityDebtScoreForTesting(int segment, long containerId) {
        return 0L;
    }

    default String purgeDaemonLastVisibilityDebtSummaryForTesting(int segment, long containerId) {
        return "none";
    }

    default long orderedIndexPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexEntryCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int orderedIndexDistinctKeyCountForTesting(int segment, long containerId) {
        return 0;
    }

    default long orderedIndexRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> orderedIndexEntrySummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long orderedIndexLookupCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexFallbackCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexFallbackReasonCountForTesting(
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return 0L;
    }

    default List<String> orderedIndexFallbackReasonSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long orderedIndexRowIdCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int orderedIndexCandidateParityErrorCountForTesting(int segment, long containerId) {
        return 0;
    }

    default List<String> orderedIndexCandidateParityErrorSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting(
            int segment,
            long containerId) {
        return DelosStorageOrderedIndexDiagnostics.AuthorityMode.UNAVAILABLE;
    }

    default DelosStorageOrderedIndexDiagnostics orderedIndexDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageOrderedIndexDiagnostics(
                orderedIndexAuthorityModeForTesting(segment, containerId),
                orderedIndexPageCountForTesting(segment, containerId),
                orderedIndexEntryCountForTesting(segment, containerId),
                orderedIndexDistinctKeyCountForTesting(segment, containerId),
                orderedIndexRebuildCountForTesting(segment, containerId),
                orderedIndexLookupCountForTesting(segment, containerId),
                orderedIndexHitCountForTesting(segment, containerId),
                orderedIndexFallbackCountForTesting(segment, containerId),
                orderedIndexRowIdCountForTesting(segment, containerId),
                orderedIndexCandidateParityErrorCountForTesting(segment, containerId));
    }

    long pageCacheMaxPageCountForTesting(int segment, long containerId);

    long pageCacheSizeForTesting(int segment, long containerId);

    long pageCacheHitCountForTesting(int segment, long containerId);

    long pageCacheMissCountForTesting(int segment, long containerId);

    long pageCacheWriteCountForTesting(int segment, long containerId);

    long pageCacheEvictionCountForTesting(int segment, long containerId);

    long pageCacheInvalidationCountForTesting(int segment, long containerId);

    default long pageCachePinCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCacheUnpinCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCachePinnedPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCacheDirtyPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCacheFlushListPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCacheFlushCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCachePinnedEvictionSkipCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageCacheLastPageGenerationForTesting(int segment, long containerId) {
        return 0L;
    }

    default long attributeOverflowWriteCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long attributeOverflowReadCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long attributeOverflowInlineRowBytesForTesting(int segment, long containerId) {
        return 0L;
    }

    default long attributeOverflowValueBytesForTesting(int segment, long containerId) {
        return 0L;
    }

    default long subsystemRecoveryRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long subsystemRecoveryLastSequenceForTesting(int segment, long containerId) {
        return 0L;
    }

    default long rowPageRedoRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long indexPageRedoRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long overflowPageRedoRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapRedoRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long transactionOutcomeRedoRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long checkpointRecoveryRecordCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> subsystemRecoveryRecordSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

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

    default DelosStorageFreeSpaceDiagnostics freeSpaceDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageFreeSpaceDiagnostics(
                freeSpaceMapPageCountForTesting(segment, containerId),
                freeSpaceMapMaxFreeBytesForTesting(segment, containerId),
                freeSpaceMapLookupCountForTesting(segment, containerId),
                freeSpaceMapHitCountForTesting(segment, containerId),
                freeSpaceMapNonLastHitCountForTesting(segment, containerId),
                freeSpaceMapMissCountForTesting(segment, containerId),
                freeSpaceMapStaleEntryCountForTesting(segment, containerId),
                freeSpaceMapUpdateCountForTesting(segment, containerId),
                freeSpaceMapRebuildCountForTesting(segment, containerId));
    }

    default DelosStorageVisibilityDiagnostics visibilityDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageVisibilityDiagnostics(
                visibilityMapPageCountForTesting(segment, containerId),
                visibilityMapOldVersionPageCountForTesting(segment, containerId),
                visibilityMapPrunablePageCountForTesting(segment, containerId),
                visibilityMapTombstonePageCountForTesting(segment, containerId),
                visibilityMapAllVisiblePageCountForTesting(segment, containerId),
                visibilityMapOverflowPageCountForTesting(segment, containerId),
                visibilityMapNeedsCheckerPageCountForTesting(segment, containerId),
                visibilityMapUpdateCountForTesting(segment, containerId),
                visibilityMapRebuildCountForTesting(segment, containerId));
    }

    default DelosStoragePageCacheDiagnostics pageCacheDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStoragePageCacheDiagnostics(
                pageCacheMaxPageCountForTesting(segment, containerId),
                pageCacheSizeForTesting(segment, containerId),
                pageCacheHitCountForTesting(segment, containerId),
                pageCacheMissCountForTesting(segment, containerId),
                pageCacheWriteCountForTesting(segment, containerId),
                pageCacheEvictionCountForTesting(segment, containerId),
                pageCacheInvalidationCountForTesting(segment, containerId),
                pageCachePinCountForTesting(segment, containerId),
                pageCacheUnpinCountForTesting(segment, containerId),
                pageCachePinnedPageCountForTesting(segment, containerId),
                pageCacheDirtyPageCountForTesting(segment, containerId),
                pageCacheFlushListPageCountForTesting(segment, containerId),
                pageCacheFlushCountForTesting(segment, containerId),
                pageCachePinnedEvictionSkipCountForTesting(segment, containerId),
                pageCacheLastPageGenerationForTesting(segment, containerId));
    }

    default DelosStorageRecoveryDiagnostics recoveryDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageRecoveryDiagnostics(
                subsystemRecoveryRecordsFileForTesting(segment, containerId),
                subsystemRecoveryRecordCountForTesting(segment, containerId),
                subsystemRecoveryLastSequenceForTesting(segment, containerId),
                rowPageRedoRecordCountForTesting(segment, containerId),
                indexPageRedoRecordCountForTesting(segment, containerId),
                overflowPageRedoRecordCountForTesting(segment, containerId),
                freeSpaceMapRedoRecordCountForTesting(segment, containerId),
                transactionOutcomeRedoRecordCountForTesting(segment, containerId),
                checkpointRecoveryRecordCountForTesting(segment, containerId),
                subsystemRecoveryRecordSummariesForTesting(segment, containerId));
    }

    default DelosStorageConsistencyDiagnostics consistencyDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageConsistencyDiagnostics(
                consistencyErrorCountForTesting(segment, containerId),
                consistencySummaryForTesting(segment, containerId));
    }

    default DelosStorageStatistics storageStatisticsForTesting(int segment, long containerId) {
        return DelosStorageStatistics.fromDiagnostics(this, segment, containerId);
    }

    default DelosMvccStorageStatistics mvccStorageStatisticsForTesting(int segment, long containerId) {
        return DelosMvccStorageStatistics.fromDiagnostics(this, segment, containerId);
    }

    default DelosHeapSanityDiagnostics heapSanityDiagnosticsForTesting(int segment, long containerId) {
        DelosStorageConsistencyDiagnostics consistency = consistencyDiagnosticsForTesting(segment, containerId);
        Path containerFile = pageVolumeStateFileForTesting(segment, containerId);
        Path segmentDirectory = containerFile == null || containerFile.getParent() == null
                ? Path.of(".")
                : containerFile.getParent();
        Path file = containerFile == null ? segmentDirectory.resolve("unknown-container") : containerFile;
        return new DelosHeapSanityDiagnostics(
                providerId(),
                segment,
                containerId,
                segmentDirectory,
                file,
                true,
                consistency.errorCount() == 0,
                consistency.errorCount() == 0,
                0L,
                pageCountForTesting(segment, containerId),
                overflowPageCountForTesting(segment, containerId),
                reusablePageCountForTesting(segment, containerId),
                consistency.errorCount(),
                java.util.List.of(consistency.summary()),
                consistency.errorCount() == 0 ? java.util.List.of() : java.util.List.of(consistency.summary()));
    }


    default DelosHeapRawStoreBoundaryDiagnostics heapRawStoreBoundaryDiagnosticsForTesting(
            int segment,
            long containerId) {
        Path file = pageVolumeStateFileForTesting(segment, containerId);
        Path segmentDirectory = file == null ? Path.of(".") : file.getParent();
        return new DelosHeapRawStoreBoundaryDiagnostics(
                providerId(),
                segment,
                containerId,
                segmentDirectory == null ? Path.of(".") : segmentDirectory,
                file == null ? Path.of("missing") : file,
                true,
                false,
                0L,
                0L,
                0L,
                false,
                false,
                false,
                List.of("heap raw-store boundary diagnostics are unavailable for provider " + providerId()));
    }

    default DelosHeapStorageStatistics heapStorageStatisticsForTesting(
            int segment,
            long containerId,
            long... indexContainerIds) {
        return DelosHeapStorageStatistics.fromDiagnostics(this, segment, containerId, indexContainerIds);
    }

    default DelosHeapStorageDiagnostics heapStorageDiagnosticsForTesting(
            int segment,
            long containerId,
            long... indexContainerIds) {
        Path containerFile = pageVolumeStateFileForTesting(segment, containerId);
        Path segmentDirectory = containerFile == null || containerFile.getParent() == null
                ? Path.of(".")
                : containerFile.getParent();
        Path file = containerFile == null ? segmentDirectory.resolve("unknown-container") : containerFile;
        long pageCount = pageCountForTesting(segment, containerId);
        long reusablePages = reusablePageCountForTesting(segment, containerId);
        long freePages = Math.min(pageCount, reusablePages);
        long beforeBytes = Math.max(0L, pageCount) * 4096L;
        long afterBytes = Math.max(0L, beforeBytes - (freePages * 4096L));
        return new DelosHeapStorageDiagnostics(
                providerId(),
                segment,
                containerId,
                segmentDirectory,
                file,
                java.util.List.of(),
                java.util.List.of(),
                true,
                consistencyErrorCountForTesting(segment, containerId) == 0,
                beforeBytes,
                0L,
                beforeBytes,
                pageCount,
                pageCount,
                freePages,
                overflowPageCountForTesting(segment, containerId),
                reusablePages,
                beforeBytes,
                afterBytes,
                consistencySummaryForTesting(segment, containerId),
                java.util.List.of("heap storage diagnostics are read-only"));
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

    default int candidateIndexFallbackLookupCountForTesting() {
        return 0;
    }

    default boolean candidateIndexDiagnosticFallbackEnabledForTesting() {
        return false;
    }

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

    default int rowIdFastPathReadCountForTesting() {
        return 0;
    }

    default int rowIdFastPathHitCountForTesting() {
        return 0;
    }

    void clearTransactionsForTesting();

    boolean isProviderScan(Object scanController);

    boolean hasLocatorHint(StoreRowLocation location);
}
