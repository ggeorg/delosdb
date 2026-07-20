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

import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;
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
    private final Path databaseDirectory;

    public MvccStorageDiagnostics() {
        this(null);
    }

    private MvccStorageDiagnostics(Path databaseDirectory) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
    }

    @Override
    public DelosStorageDiagnostics withContext(DelosStorageDiagnosticsContext context) {
        if (context == null || !context.hasDatabaseDirectory()) {
            return this;
        }
        return new MvccStorageDiagnostics(context.databaseDirectory());
    }

    @Override
    public String providerId() {
        return DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID;
    }

    @Override
    public void clearRuntimeStateForTesting() {
        if (databaseDirectory == null) {
            MvccRuntimeDiagnosticsDirectory.clearAllForTesting();
            MvccRawStoreDiagnosticsDirectory.clearAllForTesting();
            clearTransactionsForTesting();
        } else {
            MvccRuntimeDiagnosticsDirectory.clearForTesting(databaseDirectory);
            MvccRawStoreDiagnosticsDirectory.clearForTesting(databaseDirectory);
        }
    }

    @Override
    public int runtimeStateCountForTesting() {
        return databaseDirectory == null
                ? MvccConglomerate.stateCountForDiagnostics()
                : MvccRuntimeDiagnosticsDirectory.stateCount(databaseDirectory);
    }

    @Override
    public boolean runtimeActiveForTesting() {
        return databaseDirectory == null
                ? MvccRuntimeDiagnosticsDirectory.runtimeCount() > 0
                        || MvccRawStoreDiagnosticsDirectory.runtimeCount() > 0
                : MvccRuntimeDiagnosticsDirectory.isActive(databaseDirectory)
                        || MvccRawStoreDiagnosticsDirectory.isActive(databaseDirectory);
    }

    @Override
    public DelosStorageMaintenanceSnapshot databaseMaintenanceSnapshot() {
        MvccRawStoreRuntime rawStoreRuntime = databaseDirectory == null
                ? MvccRawStoreDiagnosticsDirectory.requireSingle()
                : MvccRawStoreDiagnosticsDirectory.require(databaseDirectory);
        return rawStoreRuntime.maintenanceSnapshot();
    }

    @Override
    public DelosDatabaseStorageSnapshot databaseStorageSnapshot() {
        return runtime().databaseStorageSnapshotForDiagnostics();
    }

    @Override
    public DelosDatabaseCommitTimingSnapshot databaseCommitTimingSnapshotForTesting() {
        return databaseStorageSnapshot().commitTiming();
    }

    @Override
    public void resetDatabaseCommitTimingForTesting() {
        runtime().resetDatabaseCommitTimingForDiagnostics();
    }

    @Override
    public Path pageVolumeStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageVolumeStateFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path rowDirectoryStateFileForTesting(int segment, long containerId) {
        return MvccConglomerate.rowDirectoryStateFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path reusablePageIndexFileForTesting(int segment, long containerId) {
        return MvccConglomerate.reusablePageIndexFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path freeSpaceMapFileForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path visibilityMapFileForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path purgeQueueFileForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path orderedIndexPagesFileForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexPagesFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path pageMutationLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationLogFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path writeAheadLogFileForTesting(int segment, long containerId) {
        return MvccConglomerate.writeAheadLogFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path checkpointFileForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path subsystemRecoveryRecordsFileForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordsFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public Path legacySnapshotFileForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFileForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String checkpointStatusForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointStatusForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int physicalVersionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.physicalVersionCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int logicalRowCountForTesting(int segment, long containerId) {
        return MvccConglomerate.logicalRowCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> pageBackedVisibleRowSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedVisibleRowSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int lastCommittedChangedRowCountForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedChangedRowCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int lastCommittedWriteIntentCountForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedWriteIntentCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> lastCommittedWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.lastCommittedWriteIntentPayloadSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int activeProviderWriteAppendCountForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderWriteAppendCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> activeProviderWriteAppendPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderWriteAppendPayloadSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int activeProviderSurvivingWriteIntentCountForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderSurvivingWriteIntentCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.activeProviderSurvivingWriteIntentPayloadSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int providerFirstWriteAppendCountForTesting(int segment, long containerId) {
        return MvccConglomerate.providerFirstWriteAppendCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacyWriteFrontShadowMutationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowMutationCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacyWriteFrontShadowBypassCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowBypassCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public boolean legacyWriteFrontShadowEnabledForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontShadowEnabledForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacyWriteFrontQuarantineViolationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyWriteFrontQuarantineViolationCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int providerFirstWriteAppendFailureRollbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.providerFirstWriteAppendFailureRollbackCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int transactionLocalWriteIntentReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalWriteIntentReadCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int transactionLocalWriteIntentScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalWriteIntentScanCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int transactionLocalPageBackedBaseReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalPageBackedBaseReadCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int transactionLocalPageBackedBaseScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionLocalPageBackedBaseScanCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int pageBackedHistoricalSnapshotReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedHistoricalSnapshotReadCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int pageBackedHistoricalSnapshotScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedHistoricalSnapshotScanCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacySnapshotFallbackReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFallbackReadCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacySnapshotFallbackScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacySnapshotFallbackScanCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int pageBackedCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageBackedCandidateIndexRebuildCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int legacyCandidateIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.legacyCandidateIndexRebuildCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long overflowPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.overflowPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long reusablePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.reusablePageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int freeSpaceMapMaxFreeBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapMaxFreeBytesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapLookupCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapLookupCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapHitCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapNonLastHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapNonLastHitCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapMissCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapMissCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapStaleEntryCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapStaleEntryCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapUpdateCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapRebuildCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> freeSpaceMapPageSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapPageSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapOldVersionPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapOldVersionPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapPrunablePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPrunablePageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapTombstonePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapTombstonePageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapAllVisiblePageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapAllVisiblePageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapOverflowPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapOverflowPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapNeedsCheckerPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapNeedsCheckerPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapUpdateCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long visibilityMapRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapRebuildCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> visibilityMapPageSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.visibilityMapPageSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageLocalPruneAttemptCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneAttemptCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageLocalPruneSuccessCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneSuccessCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageLocalPruneFallbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneFallbackCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageLocalPruneRemovedVersionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageLocalPruneRemovedVersionCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextBeginCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextBeginCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextCommitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextCommitCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextAbortCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextAbortCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextPageReservationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextPageReservationCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextReservedBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextReservedBytesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextPageWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextPageWriteCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextFreeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextFreeSpaceMapUpdateCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageMutationContextReusableIndexUpdateCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageMutationContextReusableIndexUpdateCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String lastPageMutationContextOperationForTesting(int segment, long containerId) {
        return MvccConglomerate.lastPageMutationContextOperationForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeQueuePendingCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueuePendingCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeQueueEnqueueCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueEnqueueCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeQueueDrainCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueDrainCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeQueueLastDrainCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueLastDrainCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public java.util.List<String> purgeQueueEntrySummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeQueueEntrySummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeDaemonScheduleCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonScheduleCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeDaemonRunCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonRunCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeDaemonSkipCountForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonSkipCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeDaemonLastTriggerChangedRowsForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastTriggerChangedRowsForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String purgeDaemonLastDecisionForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastDecisionForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long purgeDaemonLastVisibilityDebtScoreForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastVisibilityDebtScoreForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String purgeDaemonLastVisibilityDebtSummaryForTesting(int segment, long containerId) {
        return MvccConglomerate.purgeDaemonLastVisibilityDebtSummaryForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int databaseMaintenanceWorkerCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceWorkerCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int databaseMaintenanceRegisteredTableCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceRegisteredTableCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int databaseMaintenanceQueuedTaskCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceQueuedTaskCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long databaseMaintenanceCommitWakeupCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceCommitWakeupCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long databaseMaintenancePeriodicScanCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenancePeriodicScanCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long databaseMaintenanceRunCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceRunCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long databaseMaintenanceFailureCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceFailureCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int databaseMaintenanceMaximumActiveWorkerCountForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceMaximumActiveWorkerCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public boolean databaseMaintenanceAcceptingForTesting(int segment, long containerId) {
        return MvccConglomerate.databaseMaintenanceAcceptingForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexEntryCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexEntryCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int orderedIndexDistinctKeyCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexDistinctKeyCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexRebuildCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexRebuildCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public java.util.List<String> orderedIndexEntrySummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexEntrySummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexLookupCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexLookupCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexHitCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexFallbackCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexFallbackCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexFallbackReasonCountForTesting(
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return MvccConglomerate.orderedIndexFallbackReasonCountForDiagnostics(runtime(), segment, containerId, reason);
    }

    @Override
    public List<String> orderedIndexFallbackReasonSummariesForTesting(
            int segment, long containerId) {
        return MvccConglomerate.orderedIndexFallbackReasonSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long orderedIndexRowIdCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexRowIdCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int orderedIndexCandidateParityErrorCountForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexCandidateParityErrorCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public java.util.List<String> orderedIndexCandidateParityErrorSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.orderedIndexCandidateParityErrorSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting(
            int segment,
            long containerId) {
        return MvccConglomerate.orderedIndexAuthorityModeForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheMaxPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheMaxPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheSizeForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheSizeForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheHitCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheHitCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheMissCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheMissCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheWriteCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheEvictionCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheEvictionCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheInvalidationCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheInvalidationCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCachePinCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheUnpinCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheUnpinCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCachePinnedPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinnedPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheDirtyPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheDirtyPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheFlushListPageCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheFlushListPageCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheFlushCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheFlushCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCachePinnedEvictionSkipCountForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCachePinnedEvictionSkipCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long pageCacheLastPageGenerationForTesting(int segment, long containerId) {
        return MvccConglomerate.pageCacheLastPageGenerationForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long attributeOverflowWriteCountForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowWriteCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long attributeOverflowReadCountForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowReadCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long attributeOverflowInlineRowBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowInlineRowBytesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long attributeOverflowValueBytesForTesting(int segment, long containerId) {
        return MvccConglomerate.attributeOverflowValueBytesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long subsystemRecoveryRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long subsystemRecoveryLastSequenceForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryLastSequenceForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long rowPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.rowPageRedoRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long indexPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.indexPageRedoRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long overflowPageRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.overflowPageRedoRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long freeSpaceMapRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.freeSpaceMapRedoRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long transactionOutcomeRedoRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.transactionOutcomeRedoRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public long checkpointRecoveryRecordCountForTesting(int segment, long containerId) {
        return MvccConglomerate.checkpointRecoveryRecordCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public List<String> subsystemRecoveryRecordSummariesForTesting(int segment, long containerId) {
        return MvccConglomerate.subsystemRecoveryRecordSummariesForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int consistencyErrorCountForTesting(int segment, long containerId) {
        return MvccConglomerate.consistencyErrorCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String consistencySummaryForTesting(int segment, long containerId) {
        return MvccConglomerate.consistencySummaryForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public void assertConsistentForTesting(int segment, long containerId) {
        MvccConglomerate.assertConsistentForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public boolean lastVacuumSkippedForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumSkippedForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public String lastVacuumReasonForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumReasonForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int lastVacuumRemovedVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemovedVersionsForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int lastVacuumRemainingVersionsForTesting(int segment, long containerId) {
        return MvccConglomerate.lastVacuumRemainingVersionsForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public void resetMutationCountersForTesting() {
        runtime().diagnosticsForBridge().resetMutationCountersForDiagnostics();
    }

    @Override
    public int insertCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().insertCount());
    }

    @Override
    public int updateCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().updateCount());
    }

    @Override
    public int deleteCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().deleteCount());
    }

    @Override
    public void resetScanCountersForTesting() {
        runtime().diagnosticsForBridge().resetScanCountersForDiagnostics();
    }

    @Override
    public int scanOpenCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().scanOpenCount());
    }

    @Override
    public void resetQualifierRejectCountForTesting() {
        runtime().diagnosticsForBridge().resetQualifierRejectCountForDiagnostics();
    }

    @Override
    public int qualifierRejectCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().qualifierRejectCount());
    }

    @Override
    public void resetCandidateIndexCountersForTesting() {
        runtime().diagnosticsForBridge().resetCandidateIndexCountersForDiagnostics();
    }

    @Override
    public int candidateIndexLookupCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().candidateIndexLookupCount());
    }

    @Override
    public int candidateIndexFallbackLookupCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().candidateIndexFallbackLookupCount());
    }

    @Override
    public boolean candidateIndexDiagnosticFallbackEnabledForTesting() {
        return MvccConglomerate.candidateIndexDiagnosticFallbackEnabledForDiagnostics(runtime());
    }

    @Override
    public int candidateIndexRowIdCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().candidateIndexRowIdCount());
    }

    @Override
    public int candidateIndexKeyCountForTesting(int segment, long containerId) {
        return MvccConglomerate.candidateIndexKeyCountForDiagnostics(runtime(), segment, containerId);
    }

    @Override
    public int candidateIndexVisibilityRejectCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().candidateIndexVisibilityRejectCount());
    }

    @Override
    public int candidateIndexQualifierRejectCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().candidateIndexQualifierRejectCount());
    }

    @Override
    public int pageBackedCommittedScanCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().pageBackedCommittedScanCount());
    }

    @Override
    public int pageBackedCommittedReadCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().pageBackedCommittedReadCount());
    }

    @Override
    public int rowIdFastPathReadCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().rowIdFastPathReadCount());
    }

    @Override
    public int rowIdFastPathHitCountForTesting() {
        return Math.toIntExact(databaseStorageSnapshot().rowIdFastPathHitCount());
    }

    @Override
    public void resetStoragePathDiagnosticsForTesting() {
        runtime().diagnosticsForBridge().resetStoragePathDiagnosticsForDiagnostics();
    }

    @Override
    public List<DelosStoragePathDiagnostic> storagePathDiagnosticsForTesting() {
        return databaseStorageSnapshot().storagePathDiagnostics();
    }

    @Override
    public List<String> storagePathDiagnosticLinesForTesting() {
        return databaseStorageSnapshot().storagePathDiagnosticLines();
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

    private MvccDatabaseRuntime runtime() {
        return databaseDirectory == null
                ? MvccRuntimeDiagnosticsDirectory.requireSingle()
                : MvccRuntimeDiagnosticsDirectory.require(databaseDirectory);
    }

}
