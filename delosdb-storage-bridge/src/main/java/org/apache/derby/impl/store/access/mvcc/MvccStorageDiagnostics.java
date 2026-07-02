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
