/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccStorageDiagnostics

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

package org.apache.derby.impl.store.access.mvcc;

import java.nio.file.Path;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
import org.apache.derby.iapi.store.types.StoreRowLocation;

/**
 * MVCC diagnostics adapter exposed through the storage-api diagnostics surface.
 *
 * <p>The implementation delegates to the existing bridge-owned counters and
 * state observations while keeping smoke fixtures from importing bridge classes
 * directly. It is not used by production storage execution.</p>
 */
public final class MvccStorageDiagnostics implements DelosStorageDiagnostics {
    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        MvccConglomerate.clearStatesForDiagnostics();
        clearTransactionsForTesting();
    }

    @Override
    public int runtimeStateCountForTesting() {
        return MvccConglomerate.stateCountForDiagnostics();
    }

    @Override
    public Path pageVolumeStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageVolumeStateFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path rowDirectoryStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.rowDirectoryStateFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path reusablePageIndexFileForTesting(int segment, long containerId) {
        return MvccConglomerate.reusablePageIndexFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path freeSpaceMapFileForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path visibilityMapFileForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path purgeQueueFileForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path orderedIndexPagesFileForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexPagesFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path pageMutationLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationLogFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path writeAheadLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.writeAheadLogFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path checkpointFileForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path subsystemRecoveryRecordsFileForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordsFileForDiagnostics(segment, containerId);
    }

    @Override
    public Path legacySnapshotFileForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFileForDiagnostics(segment, containerId);
    }

    @Override
    public String checkpointStatusForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointStatusForDiagnostics(segment, containerId);
    }

    @Override
    public int physicalVersionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.physicalVersionCountForDiagnostics(segment, containerId);
    }

    @Override
    public int logicalRowCountForTesting(int segment, long containerId) {
        return MvccConglomerate.logicalRowCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> pageBackedVisibleRowSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedVisibleRowSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public int lastCommittedChangedRowCountForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedChangedRowCountForDiagnostics(segment, containerId);
    }

    @Override
    public int lastCommittedWriteIntentCountForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedWriteIntentCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> lastCommittedWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedWriteIntentPayloadSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public int activeProviderWriteAppendCountForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderWriteAppendCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> activeProviderWriteAppendPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderWriteAppendPayloadSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public int activeProviderSurvivingWriteIntentCountForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderSurvivingWriteIntentCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderSurvivingWriteIntentPayloadSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public int providerFirstWriteAppendCountForTesting(int segment, long containerId) {
        return MvccConglomerate.providerFirstWriteAppendCountForDiagnostics(segment, containerId);
    }

    @Override
    public int legacyWriteFrontShadowMutationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowMutationCountForDiagnostics(segment, containerId);
    }

    @Override
    public int legacyWriteFrontShadowBypassCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowBypassCountForDiagnostics(segment, containerId);
    }

    @Override
    public boolean legacyWriteFrontShadowEnabledForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowEnabledForDiagnostics(segment, containerId);
    }

    @Override
    public int legacyWriteFrontQuarantineViolationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontQuarantineViolationCountForDiagnostics(segment, containerId);
    }

    @Override
    public int providerFirstWriteAppendFailureRollbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.providerFirstWriteAppendFailureRollbackCountForDiagnostics(segment, containerId);
    }

    @Override
    public int transactionLocalWriteIntentReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalWriteIntentReadCountForDiagnostics(segment, containerId);
    }

    @Override
    public int transactionLocalWriteIntentScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalWriteIntentScanCountForDiagnostics(segment, containerId);
    }

    @Override
    public int transactionLocalPageBackedBaseReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalPageBackedBaseReadCountForDiagnostics(segment, containerId);
    }

    @Override
    public int transactionLocalPageBackedBaseScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalPageBackedBaseScanCountForDiagnostics(segment, containerId);
    }

    @Override
    public int pageBackedHistoricalSnapshotReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedHistoricalSnapshotReadCountForDiagnostics(segment, containerId);
    }

    @Override
    public int pageBackedHistoricalSnapshotScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedHistoricalSnapshotScanCountForDiagnostics(segment, containerId);
    }

    @Override
    public int legacySnapshotFallbackReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFallbackReadCountForDiagnostics(segment, containerId);
    }

    @Override
    public int legacySnapshotFallbackScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFallbackScanCountForDiagnostics(segment, containerId);
    }

    @Override
    public int pageBackedCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedCandidateIndexRebuildCountForDiagnostics(segment, containerId);
    }

    @Override
    public int legacyCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyCandidateIndexRebuildCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long overflowPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.overflowPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long reusablePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.reusablePageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public int freeSpaceMapMaxFreeBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapMaxFreeBytesForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapLookupCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapLookupCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapHitCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapNonLastHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapNonLastHitCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapMissCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapMissCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapStaleEntryCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapStaleEntryCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapUpdateCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapRebuildCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> freeSpaceMapPageSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapPageSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapOldVersionPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapOldVersionPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapPrunablePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPrunablePageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapTombstonePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapTombstonePageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapAllVisiblePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapAllVisiblePageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapOverflowPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapOverflowPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapNeedsCheckerPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapNeedsCheckerPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapUpdateCountForDiagnostics(segment, containerId);
    }

    @Override
    public long visibilityMapRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapRebuildCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> visibilityMapPageSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPageSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public long pageLocalPruneAttemptCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneAttemptCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageLocalPruneSuccessCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneSuccessCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageLocalPruneFallbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneFallbackCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageLocalPruneRemovedVersionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneRemovedVersionCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextBeginCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextBeginCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextCommitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextCommitCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextAbortCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextAbortCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextPageReservationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextPageReservationCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextReservedBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextReservedBytesForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextPageWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextPageWriteCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextFreeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextFreeSpaceMapUpdateCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageMutationContextReusableIndexUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextReusableIndexUpdateCountForDiagnostics(segment, containerId);
    }

    @Override
    public String lastPageMutationContextOperationForTesting(int segment, long containerId) {
        return MvccConglomerate.lastPageMutationContextOperationForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeQueuePendingCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueuePendingCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeQueueEnqueueCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueEnqueueCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeQueueDrainCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueDrainCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeQueueLastDrainCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueLastDrainCountForDiagnostics(segment, containerId);
    }

    @Override
    public java.util.List<String> purgeQueueEntrySummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueEntrySummariesForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeDaemonScheduleCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonScheduleCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeDaemonRunCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonRunCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeDaemonSkipCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonSkipCountForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeDaemonLastTriggerChangedRowsForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastTriggerChangedRowsForDiagnostics(segment, containerId);
    }

    @Override
    public String purgeDaemonLastDecisionForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastDecisionForDiagnostics(segment, containerId);
    }

    @Override
    public long purgeDaemonLastVisibilityDebtScoreForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastVisibilityDebtScoreForDiagnostics(segment, containerId);
    }

    @Override
    public String purgeDaemonLastVisibilityDebtSummaryForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastVisibilityDebtSummaryForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexEntryCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexEntryCountForDiagnostics(segment, containerId);
    }

    @Override
    public int orderedIndexDistinctKeyCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexDistinctKeyCountForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexRebuildCountForDiagnostics(segment, containerId);
    }

    @Override
    public java.util.List<String> orderedIndexEntrySummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexEntrySummariesForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexLookupCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexLookupCountForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexHitCountForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexFallbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexFallbackCountForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexFallbackReasonCountForTesting(
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return MvccConglomerate.orderedIndexFallbackReasonCountForDiagnostics(segment, containerId, reason);
    }

    @Override
    public List<String> orderedIndexFallbackReasonSummariesForTesting(
            int segment, long containerId) {
        return MvccConglomerate.orderedIndexFallbackReasonSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public long orderedIndexRowIdCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexRowIdCountForDiagnostics(segment, containerId);
    }

    @Override
    public int orderedIndexCandidateParityErrorCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexCandidateParityErrorCountForDiagnostics(segment, containerId);
    }

    @Override
    public java.util.List<String> orderedIndexCandidateParityErrorSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexCandidateParityErrorSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting(
            int segment,
            long containerId) {
        return MvccConglomerate.orderedIndexAuthorityModeForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheMaxPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheMaxPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheSizeForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheSizeForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheHitCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheMissCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheMissCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheWriteCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheEvictionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheEvictionCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheInvalidationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheInvalidationCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCachePinCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheUnpinCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheUnpinCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCachePinnedPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinnedPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheDirtyPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheDirtyPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheFlushListPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheFlushListPageCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheFlushCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheFlushCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCachePinnedEvictionSkipCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinnedEvictionSkipCountForDiagnostics(segment, containerId);
    }

    @Override
    public long pageCacheLastPageGenerationForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheLastPageGenerationForDiagnostics(segment, containerId);
    }

    @Override
    public long attributeOverflowWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowWriteCountForDiagnostics(segment, containerId);
    }

    @Override
    public long attributeOverflowReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowReadCountForDiagnostics(segment, containerId);
    }

    @Override
    public long attributeOverflowInlineRowBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowInlineRowBytesForDiagnostics(segment, containerId);
    }

    @Override
    public long attributeOverflowValueBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowValueBytesForDiagnostics(segment, containerId);
    }

    @Override
    public long subsystemRecoveryRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long subsystemRecoveryLastSequenceForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryLastSequenceForDiagnostics(segment, containerId);
    }

    @Override
    public long rowPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.rowPageRedoRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long indexPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.indexPageRedoRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long overflowPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.overflowPageRedoRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long freeSpaceMapRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapRedoRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long transactionOutcomeRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionOutcomeRedoRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public long checkpointRecoveryRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointRecoveryRecordCountForDiagnostics(segment, containerId);
    }

    @Override
    public List<String> subsystemRecoveryRecordSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordSummariesForDiagnostics(segment, containerId);
    }

    @Override
    public int consistencyErrorCountForTesting(int segment, long containerId) {
        return MvccConglomerate.consistencyErrorCountForDiagnostics(segment, containerId);
    }

    @Override
    public String consistencySummaryForTesting(int segment, long containerId) {
        return MvccConglomerate.consistencySummaryForDiagnostics(segment, containerId);
    }

    @Override
    public void assertConsistentForTesting(int segment, long containerId) {
        MvccConglomerate.assertConsistentForDiagnostics(segment, containerId);
    }

    @Override
    public boolean lastVacuumSkippedForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumSkippedForDiagnostics(segment, containerId);
    }

    @Override
    public String lastVacuumReasonForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumReasonForDiagnostics(segment, containerId);
    }

    @Override
    public int lastVacuumRemovedVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemovedVersionsForDiagnostics(segment, containerId);
    }

    @Override
    public int lastVacuumRemainingVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemainingVersionsForDiagnostics(segment, containerId);
    }

    @Override
    public void resetMutationCountersForTesting() {
        MvccBridgeDiagnosticsSupport.resetMutationCountersForDiagnostics();
    }

    @Override
    public int insertCountForTesting() {
        return MvccBridgeDiagnosticsSupport.insertCountForDiagnostics();
    }

    @Override
    public int updateCountForTesting() {
        return MvccBridgeDiagnosticsSupport.updateCountForDiagnostics();
    }

    @Override
    public int deleteCountForTesting() {
        return MvccBridgeDiagnosticsSupport.deleteCountForDiagnostics();
    }

    @Override
    public void resetScanCountersForTesting() {
        MvccBridgeDiagnosticsSupport.resetScanCountersForDiagnostics();
    }

    @Override
    public int scanOpenCountForTesting() {
        return MvccBridgeDiagnosticsSupport.openCountForDiagnostics();
    }

    @Override
    public void resetQualifierRejectCountForTesting() {
        MvccBridgeDiagnosticsSupport.resetQualifierRejectCountForDiagnostics();
    }

    @Override
    public int qualifierRejectCountForTesting() {
        return MvccBridgeDiagnosticsSupport.qualifierRejectCountForDiagnostics();
    }

    @Override
    public void resetCandidateIndexCountersForTesting() {
        MvccBridgeDiagnosticsSupport.resetCandidateIndexCountersForDiagnostics();
    }

    @Override
    public int candidateIndexLookupCountForTesting() {
        return MvccBridgeDiagnosticsSupport.candidateIndexLookupCountForDiagnostics();
    }

    @Override
    public int candidateIndexFallbackLookupCountForTesting() {
        return MvccBridgeDiagnosticsSupport.candidateIndexFallbackLookupCountForDiagnostics();
    }

    @Override
    public boolean candidateIndexDiagnosticFallbackEnabledForTesting() {
        return MvccConglomerate.candidateIndexDiagnosticFallbackEnabledForDiagnostics();
    }

    @Override
    public int candidateIndexRowIdCountForTesting() {
        return MvccBridgeDiagnosticsSupport.candidateIndexRowIdCountForDiagnostics();
    }

    @Override
    public int candidateIndexKeyCountForTesting(int segment, long containerId) {
        return MvccConglomerate.candidateIndexKeyCountForDiagnostics(segment, containerId);
    }

    @Override
    public int candidateIndexVisibilityRejectCountForTesting() {
        return MvccBridgeDiagnosticsSupport.candidateIndexVisibilityRejectCountForDiagnostics();
    }

    @Override
    public int candidateIndexQualifierRejectCountForTesting() {
        return MvccBridgeDiagnosticsSupport.candidateIndexQualifierRejectCountForDiagnostics();
    }

    @Override
    public int pageBackedCommittedScanCountForTesting() {
        return MvccBridgeDiagnosticsSupport.pageBackedCommittedScanCountForDiagnostics();
    }

    @Override
    public int pageBackedCommittedReadCountForTesting() {
        return MvccBridgeDiagnosticsSupport.pageBackedCommittedReadCountForDiagnostics();
    }

    @Override
    public int rowIdFastPathReadCountForTesting() {
        return MvccBridgeDiagnosticsSupport.rowIdFastPathReadCountForDiagnostics();
    }

    @Override
    public int rowIdFastPathHitCountForTesting() {
        return MvccBridgeDiagnosticsSupport.rowIdFastPathHitCountForDiagnostics();
    }

    @Override
    public void resetStoragePathDiagnosticsForTesting() {
        MvccBridgeDiagnosticsSupport.resetStoragePathDiagnosticsForDiagnostics();
    }

    @Override
    public List<DelosStoragePathDiagnostic> storagePathDiagnosticsForTesting() {
        return MvccBridgeDiagnosticsSupport.storagePathDiagnosticsForDiagnostics();
    }

    @Override
    public List<String> storagePathDiagnosticLinesForTesting() {
        return MvccBridgeDiagnosticsSupport.storagePathDiagnosticLinesForDiagnostics();
    }

    @Override
    public void clearTransactionsForTesting() {
        DelosStorageTransactionRegistry.clearForTesting();
    }

    @Override
    public boolean isProviderScan(Object scanController) {
        return scanController instanceof MvccScanController;
    }

    @Override
    public boolean hasLocatorHint(StoreRowLocation location) {
        return MvccRowLocation.from(location).hasLocatorHint();
    }
}
