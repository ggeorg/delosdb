/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccConglomerateState

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
import java.util.Optional;
import java.util.ServiceLoader;

import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.DelosStorageCandidateIndex;
import org.apache.derby.iapi.store.types.DelosStorageCommittedRead;
import org.apache.derby.iapi.store.types.DelosStorageMaintenance;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexKey;
import org.apache.derby.iapi.store.types.DelosStorageProviderFactory;
import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageRowHead;
import org.apache.derby.iapi.store.types.DelosStorageRowLocator;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.DelosStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.DelosVacuumOutcome;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Shared state behind the inherited MVCC conglomerate provider.
 *
 * <p>MODULE17M keeps Derby access-method compatibility here, but routes the
 * actual MVCC storage operations through {@code delosdb-storage-api}.  The
 * bridge no longer imports native MVCC implementation classes.</p>
 */
final class MvccConglomerateState {
    private static final String MVCC_PROVIDER_NAME = "delos_mvcc";
    private final ContainerKey key;
    private final DelosStorageTable table;
    private final DelosStorageMaintenance maintenance;
    private final DelosStorageRowLocator rowLocator;
    private final DelosStorageCandidateIndex candidateIndex;
    private final DelosStorageCommittedRead committedRead;
    private final DelosStorageTableDiagnostics diagnostics;

    MvccConglomerateState(ContainerKey key, Path databaseDirectory) {
        this.key = key;
        DelosStorageStore store = providerFactory().openStore(databaseDirectory);
        this.table = store.openTable(new DelosStorageTableKey(key.getSegmentId(), key.getContainerId()));
        this.maintenance = requireCapability(table, DelosStorageMaintenance.class);
        this.rowLocator = requireCapability(table, DelosStorageRowLocator.class);
        this.candidateIndex = requireCapability(table, DelosStorageCandidateIndex.class);
        this.committedRead = requireCapability(table, DelosStorageCommittedRead.class);
        this.diagnostics = requireCapability(table, DelosStorageTableDiagnostics.class);
    }

    ContainerKey key() {
        return key;
    }

    DelosStorageTable table() {
        return table;
    }

    DelosStorageTransaction beginTransaction() {
        return table.beginTransaction();
    }

    DelosStorageTransaction beginReadOnlyTransaction() {
        return table.beginReadOnlyTransaction();
    }

    DelosStorageSnapshot snapshot(DelosStorageTransaction transaction) {
        return table.snapshot(transaction);
    }

    DelosStorageSnapshot snapshot(
            DelosStorageTransaction transaction,
            DelosStorageSnapshot visibilitySnapshot) {
        return table.snapshot(transaction, visibilitySnapshot);
    }

    DelosStorageScan openScan(DelosStorageSnapshot snapshot) throws StandardException {
        return table.openScan(snapshot);
    }

    boolean canReadCommittedImage(DelosStorageSnapshot snapshot) {
        return committedRead.canReadCommittedImage(snapshot);
    }

    DelosStorageScan openCommittedImageScan(DelosStorageSnapshot snapshot) throws StandardException {
        return committedRead.openCommittedImageScan(snapshot);
    }

    Optional<StoreDataValue[]> readCommittedImage(long rowId, DelosStorageSnapshot snapshot) {
        return committedRead.readCommittedImage(rowId, snapshot);
    }

    Optional<StoreDataValue[]> read(long rowId, DelosStorageSnapshot snapshot) {
        return table.read(rowId, snapshot);
    }

    void insert(long rowId, StoreDataValue[] row, DelosStorageTransaction transaction) {
        table.insert(rowId, row, transaction);
    }

    void update(
            long rowId,
            StoreDataValue[] replacement,
            DelosStorageTransaction transaction,
            DelosStorageSnapshot snapshot) {
        table.update(rowId, replacement, transaction, snapshot);
    }

    void delete(long rowId, DelosStorageTransaction transaction, DelosStorageSnapshot snapshot) {
        table.delete(rowId, transaction, snapshot);
    }

    void commit(DelosStorageTransaction transaction) {
        table.commit(transaction);
    }

    void abort(DelosStorageTransaction transaction) {
        table.abort(transaction);
    }

    synchronized long nextRowId() {
        return table.nextRowId();
    }

    synchronized void dropDurableState() {
        maintenance.dropDurableState();
    }

    Path pageVolumeStateFileForTesting() {
        return diagnostics.pageVolumeStateFileForTesting();
    }

    Path rowDirectoryStateFileForTesting() {
        return diagnostics.rowDirectoryStateFileForTesting();
    }

    Path reusablePageIndexFileForTesting() {
        return diagnostics.reusablePageIndexFileForTesting();
    }

    Path freeSpaceMapFileForTesting() {
        return diagnostics.freeSpaceMapFileForTesting();
    }

    Path visibilityMapFileForTesting() {
        return diagnostics.visibilityMapFileForTesting();
    }

    Path purgeQueueFileForTesting() {
        return diagnostics.purgeQueueFileForTesting();
    }

    Path orderedIndexPagesFileForTesting() {
        return diagnostics.orderedIndexPagesFileForTesting();
    }

    Path pageMutationLogFileForTesting() {
        return diagnostics.pageMutationLogFileForTesting();
    }

    Path writeAheadLogFileForTesting() {
        return diagnostics.writeAheadLogFileForTesting();
    }

    Path checkpointFileForTesting() {
        return diagnostics.checkpointFileForTesting();
    }

    Path subsystemRecoveryRecordsFileForTesting() {
        return diagnostics.subsystemRecoveryRecordsFileForTesting();
    }

    String checkpointStatusForTesting() {
        return diagnostics.checkpointStatusForTesting();
    }

    synchronized int physicalVersionCountForTesting() {
        return diagnostics.physicalVersionCountForTesting();
    }

    synchronized int logicalRowCountForTesting() {
        return diagnostics.logicalRowCountForTesting();
    }

    synchronized DelosStorageStatistics storageStatisticsSnapshot() {
        return DelosStorageStatistics.fromTableDiagnostics(
                MVCC_PROVIDER_NAME,
                (int) key.getSegmentId(),
                key.getContainerId(),
                diagnostics);
    }

    synchronized List<String> pageBackedVisibleRowSummariesForTesting() {
        return diagnostics.pageBackedVisibleRowSummariesForTesting();
    }

    synchronized int lastCommittedChangedRowCountForTesting() {
        return diagnostics.lastCommittedChangedRowCountForTesting();
    }

    synchronized int lastCommittedWriteIntentCountForTesting() {
        return diagnostics.lastCommittedWriteIntentCountForTesting();
    }

    synchronized List<String> lastCommittedWriteIntentPayloadSummariesForTesting() {
        return diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting();
    }

    synchronized int activeProviderWriteAppendCountForTesting() {
        return diagnostics.activeProviderWriteAppendCountForTesting();
    }

    synchronized List<String> activeProviderWriteAppendPayloadSummariesForTesting() {
        return diagnostics.activeProviderWriteAppendPayloadSummariesForTesting();
    }

    synchronized int activeProviderSurvivingWriteIntentCountForTesting() {
        return diagnostics.activeProviderSurvivingWriteIntentCountForTesting();
    }

    synchronized List<String> activeProviderSurvivingWriteIntentPayloadSummariesForTesting() {
        return diagnostics.activeProviderSurvivingWriteIntentPayloadSummariesForTesting();
    }

    synchronized int providerFirstWriteAppendCountForTesting() {
        return diagnostics.providerFirstWriteAppendCountForTesting();
    }

    synchronized int legacyWriteFrontShadowMutationCountForTesting() {
        return diagnostics.legacyWriteFrontShadowMutationCountForTesting();
    }

    synchronized int legacyWriteFrontShadowBypassCountForTesting() {
        return diagnostics.legacyWriteFrontShadowBypassCountForTesting();
    }

    synchronized boolean legacyWriteFrontShadowEnabledForTesting() {
        return diagnostics.legacyWriteFrontShadowEnabledForTesting();
    }

    synchronized int legacyWriteFrontQuarantineViolationCountForTesting() {
        return diagnostics.legacyWriteFrontQuarantineViolationCountForTesting();
    }

    synchronized int providerFirstWriteAppendFailureRollbackCountForTesting() {
        return diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting();
    }

    synchronized int transactionLocalWriteIntentReadCountForTesting() {
        return diagnostics.transactionLocalWriteIntentReadCountForTesting();
    }

    synchronized int transactionLocalWriteIntentScanCountForTesting() {
        return diagnostics.transactionLocalWriteIntentScanCountForTesting();
    }

    synchronized int transactionLocalPageBackedBaseReadCountForTesting() {
        return diagnostics.transactionLocalPageBackedBaseReadCountForTesting();
    }

    synchronized int transactionLocalPageBackedBaseScanCountForTesting() {
        return diagnostics.transactionLocalPageBackedBaseScanCountForTesting();
    }

    synchronized int pageBackedHistoricalSnapshotReadCountForTesting() {
        return diagnostics.pageBackedHistoricalSnapshotReadCountForTesting();
    }

    synchronized int pageBackedHistoricalSnapshotScanCountForTesting() {
        return diagnostics.pageBackedHistoricalSnapshotScanCountForTesting();
    }

    synchronized int legacySnapshotFallbackReadCountForTesting() {
        return diagnostics.legacySnapshotFallbackReadCountForTesting();
    }

    synchronized int legacySnapshotFallbackScanCountForTesting() {
        return diagnostics.legacySnapshotFallbackScanCountForTesting();
    }

    synchronized int pageBackedCandidateIndexRebuildCountForTesting() {
        return diagnostics.pageBackedCandidateIndexRebuildCountForTesting();
    }

    synchronized int legacyCandidateIndexRebuildCountForTesting() {
        return diagnostics.legacyCandidateIndexRebuildCountForTesting();
    }

    synchronized long pageCountForTesting() {
        return diagnostics.pageCountForTesting();
    }

    synchronized long overflowPageCountForTesting() {
        return diagnostics.overflowPageCountForTesting();
    }

    synchronized long reusablePageCountForTesting() {
        return diagnostics.reusablePageCountForTesting();
    }

    synchronized long freeSpaceMapPageCountForTesting() {
        return diagnostics.freeSpaceMapPageCountForTesting();
    }

    synchronized int freeSpaceMapMaxFreeBytesForTesting() {
        return diagnostics.freeSpaceMapMaxFreeBytesForTesting();
    }

    synchronized long freeSpaceMapLookupCountForTesting() {
        return diagnostics.freeSpaceMapLookupCountForTesting();
    }

    synchronized long freeSpaceMapHitCountForTesting() {
        return diagnostics.freeSpaceMapHitCountForTesting();
    }

    synchronized long freeSpaceMapNonLastHitCountForTesting() {
        return diagnostics.freeSpaceMapNonLastHitCountForTesting();
    }

    synchronized long freeSpaceMapMissCountForTesting() {
        return diagnostics.freeSpaceMapMissCountForTesting();
    }

    synchronized long freeSpaceMapStaleEntryCountForTesting() {
        return diagnostics.freeSpaceMapStaleEntryCountForTesting();
    }

    synchronized long freeSpaceMapUpdateCountForTesting() {
        return diagnostics.freeSpaceMapUpdateCountForTesting();
    }

    synchronized long freeSpaceMapRebuildCountForTesting() {
        return diagnostics.freeSpaceMapRebuildCountForTesting();
    }

    synchronized List<String> freeSpaceMapPageSummariesForTesting() {
        return diagnostics.freeSpaceMapPageSummariesForTesting();
    }

    synchronized long visibilityMapPageCountForTesting() {
        return diagnostics.visibilityMapPageCountForTesting();
    }

    synchronized long visibilityMapOldVersionPageCountForTesting() {
        return diagnostics.visibilityMapOldVersionPageCountForTesting();
    }

    synchronized long visibilityMapPrunablePageCountForTesting() {
        return diagnostics.visibilityMapPrunablePageCountForTesting();
    }

    synchronized long visibilityMapTombstonePageCountForTesting() {
        return diagnostics.visibilityMapTombstonePageCountForTesting();
    }

    synchronized long visibilityMapAllVisiblePageCountForTesting() {
        return diagnostics.visibilityMapAllVisiblePageCountForTesting();
    }

    synchronized long visibilityMapOverflowPageCountForTesting() {
        return diagnostics.visibilityMapOverflowPageCountForTesting();
    }

    synchronized long visibilityMapNeedsCheckerPageCountForTesting() {
        return diagnostics.visibilityMapNeedsCheckerPageCountForTesting();
    }

    synchronized long visibilityMapUpdateCountForTesting() {
        return diagnostics.visibilityMapUpdateCountForTesting();
    }

    synchronized long visibilityMapRebuildCountForTesting() {
        return diagnostics.visibilityMapRebuildCountForTesting();
    }

    synchronized List<String> visibilityMapPageSummariesForTesting() {
        return diagnostics.visibilityMapPageSummariesForTesting();
    }

    synchronized long pageLocalPruneAttemptCountForTesting() {
        return diagnostics.pageLocalPruneAttemptCountForTesting();
    }

    synchronized long pageLocalPruneSuccessCountForTesting() {
        return diagnostics.pageLocalPruneSuccessCountForTesting();
    }

    synchronized long pageLocalPruneFallbackCountForTesting() {
        return diagnostics.pageLocalPruneFallbackCountForTesting();
    }

    synchronized long pageLocalPruneRemovedVersionCountForTesting() {
        return diagnostics.pageLocalPruneRemovedVersionCountForTesting();
    }

    synchronized long pageMutationContextBeginCountForTesting() {
        return diagnostics.pageMutationContextBeginCountForTesting();
    }

    synchronized long pageMutationContextCommitCountForTesting() {
        return diagnostics.pageMutationContextCommitCountForTesting();
    }

    synchronized long pageMutationContextAbortCountForTesting() {
        return diagnostics.pageMutationContextAbortCountForTesting();
    }

    synchronized long pageMutationContextPageReservationCountForTesting() {
        return diagnostics.pageMutationContextPageReservationCountForTesting();
    }

    synchronized long pageMutationContextReservedBytesForTesting() {
        return diagnostics.pageMutationContextReservedBytesForTesting();
    }

    synchronized long pageMutationContextPageWriteCountForTesting() {
        return diagnostics.pageMutationContextPageWriteCountForTesting();
    }

    synchronized long pageMutationContextFreeSpaceMapUpdateCountForTesting() {
        return diagnostics.pageMutationContextFreeSpaceMapUpdateCountForTesting();
    }

    synchronized long pageMutationContextReusableIndexUpdateCountForTesting() {
        return diagnostics.pageMutationContextReusableIndexUpdateCountForTesting();
    }

    synchronized String lastPageMutationContextOperationForTesting() {
        return diagnostics.lastPageMutationContextOperationForTesting();
    }

    synchronized long purgeQueuePendingCountForTesting() {
        return diagnostics.purgeQueuePendingCountForTesting();
    }

    synchronized long purgeQueueEnqueueCountForTesting() {
        return diagnostics.purgeQueueEnqueueCountForTesting();
    }

    synchronized long purgeQueueDrainCountForTesting() {
        return diagnostics.purgeQueueDrainCountForTesting();
    }

    synchronized long purgeQueueLastDrainCountForTesting() {
        return diagnostics.purgeQueueLastDrainCountForTesting();
    }

    synchronized List<String> purgeQueueEntrySummariesForTesting() {
        return diagnostics.purgeQueueEntrySummariesForTesting();
    }

    synchronized long orderedIndexPageCountForTesting() {
        return diagnostics.orderedIndexPageCountForTesting();
    }

    synchronized long orderedIndexEntryCountForTesting() {
        return diagnostics.orderedIndexEntryCountForTesting();
    }

    synchronized int orderedIndexDistinctKeyCountForTesting() {
        return diagnostics.orderedIndexDistinctKeyCountForTesting();
    }

    synchronized long orderedIndexRebuildCountForTesting() {
        return diagnostics.orderedIndexRebuildCountForTesting();
    }

    synchronized List<String> orderedIndexEntrySummariesForTesting() {
        return diagnostics.orderedIndexEntrySummariesForTesting();
    }

    synchronized long orderedIndexLookupCountForTesting() {
        return diagnostics.orderedIndexLookupCountForTesting();
    }

    synchronized long orderedIndexHitCountForTesting() {
        return diagnostics.orderedIndexHitCountForTesting();
    }

    synchronized long orderedIndexFallbackCountForTesting() {
        return diagnostics.orderedIndexFallbackCountForTesting();
    }

    synchronized long orderedIndexFallbackReasonCountForTesting(
            DelosStorageOrderedIndexFallbackReason reason) {
        return diagnostics.orderedIndexFallbackReasonCountForTesting(reason);
    }

    synchronized List<String> orderedIndexFallbackReasonSummariesForTesting() {
        return diagnostics.orderedIndexFallbackReasonSummariesForTesting();
    }

    synchronized long orderedIndexRowIdCountForTesting() {
        return diagnostics.orderedIndexRowIdCountForTesting();
    }

    synchronized int orderedIndexCandidateParityErrorCountForTesting() {
        return diagnostics.orderedIndexCandidateParityErrorCountForTesting();
    }

    synchronized List<String> orderedIndexCandidateParityErrorSummariesForTesting() {
        return diagnostics.orderedIndexCandidateParityErrorSummariesForTesting();
    }

    synchronized DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting() {
        return diagnostics.orderedIndexAuthorityModeForTesting();
    }

    synchronized long pageCacheMaxPageCountForTesting() {
        return diagnostics.pageCacheMaxPageCountForTesting();
    }

    synchronized long pageCacheSizeForTesting() {
        return diagnostics.pageCacheSizeForTesting();
    }

    synchronized long pageCacheHitCountForTesting() {
        return diagnostics.pageCacheHitCountForTesting();
    }

    synchronized long pageCacheMissCountForTesting() {
        return diagnostics.pageCacheMissCountForTesting();
    }

    synchronized long pageCacheWriteCountForTesting() {
        return diagnostics.pageCacheWriteCountForTesting();
    }

    synchronized long pageCacheEvictionCountForTesting() {
        return diagnostics.pageCacheEvictionCountForTesting();
    }

    synchronized long pageCacheInvalidationCountForTesting() {
        return diagnostics.pageCacheInvalidationCountForTesting();
    }

    synchronized long pageCachePinCountForTesting() {
        return diagnostics.pageCachePinCountForTesting();
    }

    synchronized long pageCacheUnpinCountForTesting() {
        return diagnostics.pageCacheUnpinCountForTesting();
    }

    synchronized long pageCachePinnedPageCountForTesting() {
        return diagnostics.pageCachePinnedPageCountForTesting();
    }

    synchronized long pageCacheDirtyPageCountForTesting() {
        return diagnostics.pageCacheDirtyPageCountForTesting();
    }

    synchronized long pageCacheFlushListPageCountForTesting() {
        return diagnostics.pageCacheFlushListPageCountForTesting();
    }

    synchronized long pageCacheFlushCountForTesting() {
        return diagnostics.pageCacheFlushCountForTesting();
    }

    synchronized long pageCachePinnedEvictionSkipCountForTesting() {
        return diagnostics.pageCachePinnedEvictionSkipCountForTesting();
    }

    synchronized long pageCacheLastPageGenerationForTesting() {
        return diagnostics.pageCacheLastPageGenerationForTesting();
    }

    synchronized long attributeOverflowWriteCountForTesting() {
        return diagnostics.attributeOverflowWriteCountForTesting();
    }

    synchronized long attributeOverflowReadCountForTesting() {
        return diagnostics.attributeOverflowReadCountForTesting();
    }

    synchronized long attributeOverflowInlineRowBytesForTesting() {
        return diagnostics.attributeOverflowInlineRowBytesForTesting();
    }

    synchronized long attributeOverflowValueBytesForTesting() {
        return diagnostics.attributeOverflowValueBytesForTesting();
    }

    synchronized long subsystemRecoveryRecordCountForTesting() {
        return diagnostics.subsystemRecoveryRecordCountForTesting();
    }

    synchronized long subsystemRecoveryLastSequenceForTesting() {
        return diagnostics.subsystemRecoveryLastSequenceForTesting();
    }

    synchronized long rowPageRedoRecordCountForTesting() {
        return diagnostics.rowPageRedoRecordCountForTesting();
    }

    synchronized long indexPageRedoRecordCountForTesting() {
        return diagnostics.indexPageRedoRecordCountForTesting();
    }

    synchronized long overflowPageRedoRecordCountForTesting() {
        return diagnostics.overflowPageRedoRecordCountForTesting();
    }

    synchronized long freeSpaceMapRedoRecordCountForTesting() {
        return diagnostics.freeSpaceMapRedoRecordCountForTesting();
    }

    synchronized long transactionOutcomeRedoRecordCountForTesting() {
        return diagnostics.transactionOutcomeRedoRecordCountForTesting();
    }

    synchronized long checkpointRecoveryRecordCountForTesting() {
        return diagnostics.checkpointRecoveryRecordCountForTesting();
    }

    synchronized List<String> subsystemRecoveryRecordSummariesForTesting() {
        return diagnostics.subsystemRecoveryRecordSummariesForTesting();
    }

    synchronized int consistencyErrorCountForTesting() {
        return diagnostics.consistencyErrorCountForTesting();
    }

    synchronized String consistencySummaryForTesting() {
        return diagnostics.consistencySummaryForTesting();
    }

    synchronized void assertConsistentForTesting() {
        diagnostics.assertConsistentForTesting();
    }

    synchronized DelosVacuumOutcome lastVacuumOutcomeForTesting() {
        return diagnostics.lastVacuumOutcomeForTesting();
    }

    synchronized long purgeDaemonScheduleCountForTesting() {
        return diagnostics.purgeDaemonScheduleCountForTesting();
    }

    synchronized long purgeDaemonRunCountForTesting() {
        return diagnostics.purgeDaemonRunCountForTesting();
    }

    synchronized long purgeDaemonSkipCountForTesting() {
        return diagnostics.purgeDaemonSkipCountForTesting();
    }

    synchronized long purgeDaemonLastTriggerChangedRowsForTesting() {
        return diagnostics.purgeDaemonLastTriggerChangedRowsForTesting();
    }

    synchronized String purgeDaemonLastDecisionForTesting() {
        return diagnostics.purgeDaemonLastDecisionForTesting();
    }

    synchronized long purgeDaemonLastVisibilityDebtScoreForTesting() {
        return diagnostics.purgeDaemonLastVisibilityDebtScoreForTesting();
    }

    synchronized String purgeDaemonLastVisibilityDebtSummaryForTesting() {
        return diagnostics.purgeDaemonLastVisibilityDebtSummaryForTesting();
    }

    synchronized DelosVacuumOutcome vacuumSafely() {
        return maintenance.vacuumSafely();
    }

    Path legacySnapshotFileForTesting() {
        return diagnostics.legacySnapshotFileForTesting();
    }

    synchronized MvccRowLocation rowLocationFor(long rowId) {
        DelosStorageRowHead head = rowLocator.rowHeadFor(rowId);
        if (head.present()) {
            return new MvccRowLocation(rowId, head.pageId(), head.slotId());
        }
        return new MvccRowLocation(rowId);
    }

    synchronized Optional<List<Long>> orderedIndexRowIdsFor(Qualifier[][] qualifiers) {
        Optional<ColumnValueKey> key = equalityOrderedIndexKey(qualifiers);
        if (key.isPresent()) {
            ColumnValueKey columnValueKey = key.get();
            return candidateIndex.orderedIndexRowIdsFor(
                    columnValueKey.column(), columnValueKey.value());
        }

        Optional<ColumnRangeKey> range = rangeOrderedIndexKey(qualifiers);
        if (range.isEmpty()) {
            if (hasIndexQualifiers(qualifiers)) {
                recordOrderedIndexFallbackForDiagnostics(
                        DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE);
            }
            return Optional.empty();
        }
        ColumnRangeKey columnRangeKey = range.get();
        return candidateIndex.orderedIndexRowIdsInRangeFor(
                columnRangeKey.column(),
                columnRangeKey.lowerValue(),
                columnRangeKey.lowerInclusive(),
                columnRangeKey.upperValue(),
                columnRangeKey.upperInclusive());
    }

    synchronized void recordOrderedIndexFallbackForDiagnostics(
            DelosStorageOrderedIndexFallbackReason reason) {
        candidateIndex.recordOrderedIndexFallbackForTesting(reason);
    }

    static boolean hasIndexQualifiers(Qualifier[][] qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return false;
        }
        for (Qualifier[] andTerm : qualifiers) {
            if (andTerm == null) {
                continue;
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier != null) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean candidateIndexDiagnosticFallbackEnabledForTesting() {
        return false;
    }

    synchronized int candidateIndexKeyCountForTesting() {
        return candidateIndex.candidateIndexKeyCountForTesting();
    }

    synchronized void close() {
        table.close();
    }

    private static Optional<ColumnValueKey> equalityOrderedIndexKey(Qualifier[][] qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return Optional.empty();
        }
        for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
            Qualifier[] andTerm = qualifiers[andTermIndex];
            if (andTerm == null || andTerm.length == 0) {
                continue;
            }
            if (andTermIndex > 0 && andTerm.length != 1) {
                // Additional Derby qualifier groups are OR terms.  A group
                // with more than one alternative cannot be used as a safe
                // single equality narrowing predicate; keep the full scan path.
                return Optional.empty();
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier == null
                        || qualifier.getColumnId() < 0
                        || qualifier.getOperator() != StoreOrderable.ORDER_OP_EQUALS
                        || qualifier.negateCompareResult()) {
                    continue;
                }
                try {
                    StoreDataValue orderable = qualifier.getOrderable();
                    if (orderable == null) {
                        return Optional.empty();
                    }
                    return Optional.of(new ColumnValueKey(qualifier.getColumnId(), valueKey(orderable)));
                } catch (StandardException | RuntimeException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<ColumnRangeKey> rangeOrderedIndexKey(Qualifier[][] qualifiers) {
        if (qualifiers == null || qualifiers.length == 0) {
            return Optional.empty();
        }
        int column = -1;
        String lowerValue = null;
        boolean lowerInclusive = true;
        String upperValue = null;
        boolean upperInclusive = true;
        boolean sawRangeBound = false;

        for (int andTermIndex = 0; andTermIndex < qualifiers.length; andTermIndex++) {
            Qualifier[] andTerm = qualifiers[andTermIndex];
            if (andTerm == null || andTerm.length == 0) {
                return Optional.empty();
            }
            if (andTermIndex > 0 && andTerm.length != 1) {
                // Additional Derby qualifier groups are OR terms. A group with
                // more than one alternative is not a simple single-column range,
                // so keep the full committed-image scan path.
                return Optional.empty();
            }
            for (Qualifier qualifier : andTerm) {
                if (qualifier == null || qualifier.getColumnId() < 0) {
                    return Optional.empty();
                }
                int operator = normalizedRangeOperator(qualifier.getOperator(), qualifier.negateCompareResult());
                if (operator == Integer.MIN_VALUE) {
                    return Optional.empty();
                }
                if (column == -1) {
                    column = qualifier.getColumnId();
                } else if (column != qualifier.getColumnId()) {
                    return Optional.empty();
                }
                String value;
                try {
                    StoreDataValue orderable = qualifier.getOrderable();
                    if (orderable == null) {
                        return Optional.empty();
                    }
                    value = valueKey(orderable);
                } catch (StandardException | RuntimeException e) {
                    return Optional.empty();
                }
                switch (operator) {
                    case StoreOrderable.ORDER_OP_GREATERTHAN -> {
                        BoundChoice choice = chooseLowerBound(lowerValue, lowerInclusive, value, false);
                        lowerValue = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_GREATEROREQUALS -> {
                        BoundChoice choice = chooseLowerBound(lowerValue, lowerInclusive, value, true);
                        lowerValue = choice.value();
                        lowerInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSTHAN -> {
                        BoundChoice choice = chooseUpperBound(upperValue, upperInclusive, value, false);
                        upperValue = choice.value();
                        upperInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    case StoreOrderable.ORDER_OP_LESSOREQUALS -> {
                        BoundChoice choice = chooseUpperBound(upperValue, upperInclusive, value, true);
                        upperValue = choice.value();
                        upperInclusive = choice.inclusive();
                        sawRangeBound = true;
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        }

        if (!sawRangeBound || column < 0) {
            return Optional.empty();
        }
        return Optional.of(new ColumnRangeKey(
                column, lowerValue, lowerInclusive, upperValue, upperInclusive));
    }

    private static int normalizedRangeOperator(int operator, boolean negateCompareResult) {
        if (!negateCompareResult) {
            return operator;
        }
        return switch (operator) {
            case StoreOrderable.ORDER_OP_LESSTHAN -> StoreOrderable.ORDER_OP_GREATEROREQUALS;
            case StoreOrderable.ORDER_OP_LESSOREQUALS -> StoreOrderable.ORDER_OP_GREATERTHAN;
            case StoreOrderable.ORDER_OP_GREATERTHAN -> StoreOrderable.ORDER_OP_LESSOREQUALS;
            case StoreOrderable.ORDER_OP_GREATEROREQUALS -> StoreOrderable.ORDER_OP_LESSTHAN;
            default -> Integer.MIN_VALUE;
        };
    }

    private static BoundChoice chooseLowerBound(
            String currentValue, boolean currentInclusive, String candidateValue, boolean candidateInclusive) {
        if (currentValue == null) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        int comparison = DelosStorageOrderedIndexKey.compare(candidateValue, currentValue);
        if (comparison > 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        return new BoundChoice(currentValue, currentInclusive);
    }

    private static BoundChoice chooseUpperBound(
            String currentValue, boolean currentInclusive, String candidateValue, boolean candidateInclusive) {
        if (currentValue == null) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        int comparison = DelosStorageOrderedIndexKey.compare(candidateValue, currentValue);
        if (comparison < 0 || (comparison == 0 && currentInclusive && !candidateInclusive)) {
            return new BoundChoice(candidateValue, candidateInclusive);
        }
        return new BoundChoice(currentValue, currentInclusive);
    }

    private static String valueKey(StoreDataValue value) {
        try {
            return DelosStorageOrderedIndexKey.encode(value);
        } catch (StandardException e) {
            throw new IllegalStateException("Cannot derive typed ordered-index key from "
                    + value.getClass().getName(), e);
        }
    }

    private static <T> T requireCapability(DelosStorageTable table, Class<T> capability) {
        if (capability.isInstance(table)) {
            return capability.cast(table);
        }
        throw new IllegalStateException("Storage table " + table.getClass().getName()
                + " does not implement required capability " + capability.getName());
    }

    private static DelosStorageProviderFactory providerFactory() {
        for (DelosStorageProviderFactory factory : ServiceLoader.load(DelosStorageProviderFactory.class)) {
            if (MVCC_PROVIDER_NAME.equals(factory.providerName())) {
                return factory;
            }
        }
        throw new IllegalStateException("No storage-api provider registered for " + MVCC_PROVIDER_NAME);
    }

    private record ColumnValueKey(int column, String value) {
    }

    private record ColumnRangeKey(
            int column,
            String lowerValue,
            boolean lowerInclusive,
            String upperValue,
            boolean upperInclusive) {
    }

    private record BoundChoice(String value, boolean inclusive) {
    }
}
