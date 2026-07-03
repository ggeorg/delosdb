/*

   Derby - Class org.apache.derby.iapi.store.types.DelosMvccStorageStatistics

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * MVCC-specific read-only storage statistics derived from existing diagnostic
 * counters.
 *
 * <p>This is a reporting boundary only. It does not change MVCC storage format,
 * page layout, recovery behavior, SQL execution, or optimizer costing.</p>
 */
public record DelosMvccStorageStatistics(String providerId,
                                         int segment,
                                         long containerId,
                                         boolean readOnly,
                                         long logicalRowCount,
                                         long physicalVersionCount,
                                         long rowPageCount,
                                         long overflowPageCount,
                                         long reusablePageCount,
                                         long orderedIndexPageCount,
                                         long orderedIndexEntryCount,
                                         int orderedIndexDistinctKeyCount,
                                         long orderedIndexLookupCount,
                                         long orderedIndexHitCount,
                                         long orderedIndexFallbackCount,
                                         long orderedIndexRowIdCount,
                                         int orderedIndexCandidateParityErrorCount,
                                         long freeSpaceMapPageCount,
                                         int freeSpaceMapMaxFreeBytes,
                                         long freeSpaceMapLookupCount,
                                         long freeSpaceMapHitCount,
                                         long freeSpaceMapMissCount,
                                         long freeSpaceMapStaleEntryCount,
                                         long freeSpaceMapUpdateCount,
                                         long freeSpaceMapRebuildCount,
                                         long visibilityMapPageCount,
                                         long visibilityMapOldVersionPageCount,
                                         long visibilityMapPrunablePageCount,
                                         long visibilityMapTombstonePageCount,
                                         long visibilityMapAllVisiblePageCount,
                                         long visibilityMapOverflowPageCount,
                                         long visibilityMapNeedsCheckerPageCount,
                                         long visibilityMapUpdateCount,
                                         long visibilityMapRebuildCount,
                                         long pageLocalPruneAttemptCount,
                                         long pageLocalPruneSuccessCount,
                                         long pageLocalPruneFallbackCount,
                                         long pageLocalPruneRemovedVersionCount,
                                         long purgeQueuePendingCount,
                                         long purgeQueueEnqueueCount,
                                         long purgeQueueDrainCount,
                                         long purgeQueueLastDrainCount,
                                         long pageCacheMaxPageCount,
                                         long pageCacheSize,
                                         long pageCacheHitCount,
                                         long pageCacheMissCount,
                                         long pageCacheWriteCount,
                                         long pageCacheEvictionCount,
                                         long pageCachePinCount,
                                         long pageCacheUnpinCount,
                                         long pageCachePinnedPageCount,
                                         long pageCacheDirtyPageCount,
                                         long pageCacheFlushListPageCount,
                                         long pageCacheFlushCount,
                                         long pageCachePinnedEvictionSkipCount,
                                         long pageCacheLastPageGeneration,
                                         long attributeOverflowWriteCount,
                                         long attributeOverflowReadCount,
                                         long attributeOverflowInlineRowBytes,
                                         long attributeOverflowValueBytes,
                                         long subsystemRecoveryRecordCount,
                                         long subsystemRecoveryLastSequence,
                                         long rowPageRedoRecordCount,
                                         long indexPageRedoRecordCount,
                                         long overflowPageRedoRecordCount,
                                         long freeSpaceMapRedoRecordCount,
                                         long transactionOutcomeRedoRecordCount,
                                         long checkpointRecoveryRecordCount,
                                         boolean candidateIndexAuthorityRemoved,
                                         long observedStorageBytes,
                                         List<String> observations) {
    public DelosMvccStorageStatistics {
        providerId = DelosStorageProviderIds.normalize(providerId);
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (!DelosStorageProviderIds.isMvcc(providerId)) {
            throw new IllegalArgumentException("MVCC storage statistics require provider "
                    + DelosStorageProviderIds.MVCC_PROVIDER_ID + ", got " + providerId);
        }
        if (logicalRowCount < 0L
                || physicalVersionCount < 0L
                || rowPageCount < 0L
                || overflowPageCount < 0L
                || reusablePageCount < 0L
                || orderedIndexPageCount < 0L
                || orderedIndexEntryCount < 0L
                || orderedIndexDistinctKeyCount < 0
                || orderedIndexLookupCount < 0L
                || orderedIndexHitCount < 0L
                || orderedIndexFallbackCount < 0L
                || orderedIndexRowIdCount < 0L
                || orderedIndexCandidateParityErrorCount < 0
                || freeSpaceMapPageCount < 0L
                || freeSpaceMapMaxFreeBytes < 0
                || freeSpaceMapLookupCount < 0L
                || freeSpaceMapHitCount < 0L
                || freeSpaceMapMissCount < 0L
                || freeSpaceMapStaleEntryCount < 0L
                || freeSpaceMapUpdateCount < 0L
                || freeSpaceMapRebuildCount < 0L
                || visibilityMapPageCount < 0L
                || visibilityMapOldVersionPageCount < 0L
                || visibilityMapPrunablePageCount < 0L
                || visibilityMapTombstonePageCount < 0L
                || visibilityMapAllVisiblePageCount < 0L
                || visibilityMapOverflowPageCount < 0L
                || visibilityMapNeedsCheckerPageCount < 0L
                || visibilityMapUpdateCount < 0L
                || visibilityMapRebuildCount < 0L
                || pageLocalPruneAttemptCount < 0L
                || pageLocalPruneSuccessCount < 0L
                || pageLocalPruneFallbackCount < 0L
                || pageLocalPruneRemovedVersionCount < 0L
                || purgeQueuePendingCount < 0L
                || purgeQueueEnqueueCount < 0L
                || purgeQueueDrainCount < 0L
                || purgeQueueLastDrainCount < 0L
                || pageCacheMaxPageCount < 0L
                || pageCacheSize < 0L
                || pageCacheHitCount < 0L
                || pageCacheMissCount < 0L
                || pageCacheWriteCount < 0L
                || pageCacheEvictionCount < 0L
                || pageCachePinCount < 0L
                || pageCacheUnpinCount < 0L
                || pageCachePinnedPageCount < 0L
                || pageCacheDirtyPageCount < 0L
                || pageCacheFlushListPageCount < 0L
                || pageCacheFlushCount < 0L
                || pageCachePinnedEvictionSkipCount < 0L
                || pageCacheLastPageGeneration < 0L
                || attributeOverflowWriteCount < 0L
                || attributeOverflowReadCount < 0L
                || attributeOverflowInlineRowBytes < 0L
                || attributeOverflowValueBytes < 0L
                || subsystemRecoveryRecordCount < 0L
                || subsystemRecoveryLastSequence < 0L
                || rowPageRedoRecordCount < 0L
                || indexPageRedoRecordCount < 0L
                || overflowPageRedoRecordCount < 0L
                || freeSpaceMapRedoRecordCount < 0L
                || transactionOutcomeRedoRecordCount < 0L
                || checkpointRecoveryRecordCount < 0L
                || observedStorageBytes < 0L) {
            throw new IllegalArgumentException("MVCC storage statistics counters must not be negative");
        }
    }

    public static DelosMvccStorageStatistics fromDiagnostics(
            DelosStorageDiagnostics diagnostics,
            int segment,
            long containerId) {
        Objects.requireNonNull(diagnostics, "diagnostics");
        DelosStorageStatistics base = diagnostics.storageStatisticsForTesting(segment, containerId);
        List<String> observations = new ArrayList<>(base.observations());
        observations.add("MVCC storage statistics are derived from provider diagnostics");
        observations.add("ordered index pages are the current-committed row-id shortcut authority");
        observations.add("candidate indexes are not normal SQL read authority");
        observations.add("optimizer/cost integration is not enabled by this report");

        return new DelosMvccStorageStatistics(
                diagnostics.providerId(),
                segment,
                containerId,
                true,
                diagnostics.logicalRowCountForTesting(segment, containerId),
                diagnostics.physicalVersionCountForTesting(segment, containerId),
                diagnostics.pageCountForTesting(segment, containerId),
                diagnostics.overflowPageCountForTesting(segment, containerId),
                diagnostics.reusablePageCountForTesting(segment, containerId),
                diagnostics.orderedIndexPageCountForTesting(segment, containerId),
                diagnostics.orderedIndexEntryCountForTesting(segment, containerId),
                diagnostics.orderedIndexDistinctKeyCountForTesting(segment, containerId),
                diagnostics.orderedIndexLookupCountForTesting(segment, containerId),
                diagnostics.orderedIndexHitCountForTesting(segment, containerId),
                diagnostics.orderedIndexFallbackCountForTesting(segment, containerId),
                diagnostics.orderedIndexRowIdCountForTesting(segment, containerId),
                diagnostics.orderedIndexCandidateParityErrorCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapPageCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapMaxFreeBytesForTesting(segment, containerId),
                diagnostics.freeSpaceMapLookupCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapHitCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapMissCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapStaleEntryCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapUpdateCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapRebuildCountForTesting(segment, containerId),
                diagnostics.visibilityMapPageCountForTesting(segment, containerId),
                diagnostics.visibilityMapOldVersionPageCountForTesting(segment, containerId),
                diagnostics.visibilityMapPrunablePageCountForTesting(segment, containerId),
                diagnostics.visibilityMapTombstonePageCountForTesting(segment, containerId),
                diagnostics.visibilityMapAllVisiblePageCountForTesting(segment, containerId),
                diagnostics.visibilityMapOverflowPageCountForTesting(segment, containerId),
                diagnostics.visibilityMapNeedsCheckerPageCountForTesting(segment, containerId),
                diagnostics.visibilityMapUpdateCountForTesting(segment, containerId),
                diagnostics.visibilityMapRebuildCountForTesting(segment, containerId),
                diagnostics.pageLocalPruneAttemptCountForTesting(segment, containerId),
                diagnostics.pageLocalPruneSuccessCountForTesting(segment, containerId),
                diagnostics.pageLocalPruneFallbackCountForTesting(segment, containerId),
                diagnostics.pageLocalPruneRemovedVersionCountForTesting(segment, containerId),
                diagnostics.purgeQueuePendingCountForTesting(segment, containerId),
                diagnostics.purgeQueueEnqueueCountForTesting(segment, containerId),
                diagnostics.purgeQueueDrainCountForTesting(segment, containerId),
                diagnostics.purgeQueueLastDrainCountForTesting(segment, containerId),
                diagnostics.pageCacheMaxPageCountForTesting(segment, containerId),
                diagnostics.pageCacheSizeForTesting(segment, containerId),
                diagnostics.pageCacheHitCountForTesting(segment, containerId),
                diagnostics.pageCacheMissCountForTesting(segment, containerId),
                diagnostics.pageCacheWriteCountForTesting(segment, containerId),
                diagnostics.pageCacheEvictionCountForTesting(segment, containerId),
                diagnostics.pageCachePinCountForTesting(segment, containerId),
                diagnostics.pageCacheUnpinCountForTesting(segment, containerId),
                diagnostics.pageCachePinnedPageCountForTesting(segment, containerId),
                diagnostics.pageCacheDirtyPageCountForTesting(segment, containerId),
                diagnostics.pageCacheFlushListPageCountForTesting(segment, containerId),
                diagnostics.pageCacheFlushCountForTesting(segment, containerId),
                diagnostics.pageCachePinnedEvictionSkipCountForTesting(segment, containerId),
                diagnostics.pageCacheLastPageGenerationForTesting(segment, containerId),
                diagnostics.attributeOverflowWriteCountForTesting(segment, containerId),
                diagnostics.attributeOverflowReadCountForTesting(segment, containerId),
                diagnostics.attributeOverflowInlineRowBytesForTesting(segment, containerId),
                diagnostics.attributeOverflowValueBytesForTesting(segment, containerId),
                diagnostics.subsystemRecoveryRecordCountForTesting(segment, containerId),
                diagnostics.subsystemRecoveryLastSequenceForTesting(segment, containerId),
                diagnostics.rowPageRedoRecordCountForTesting(segment, containerId),
                diagnostics.indexPageRedoRecordCountForTesting(segment, containerId),
                diagnostics.overflowPageRedoRecordCountForTesting(segment, containerId),
                diagnostics.freeSpaceMapRedoRecordCountForTesting(segment, containerId),
                diagnostics.transactionOutcomeRedoRecordCountForTesting(segment, containerId),
                diagnostics.checkpointRecoveryRecordCountForTesting(segment, containerId),
                true,
                base.observedStorageBytes(),
                observations);
    }

    public boolean hasOrderedIndexStatistics() {
        return orderedIndexPageCount > 0L || orderedIndexEntryCount > 0L;
    }

    public boolean hasFreeSpaceStatistics() {
        return freeSpaceMapPageCount > 0L || freeSpaceMapLookupCount > 0L || freeSpaceMapUpdateCount > 0L;
    }

    public boolean hasVisibilityStatistics() {
        return visibilityMapPageCount > 0L || visibilityMapUpdateCount > 0L;
    }

    public boolean hasPageCacheStatistics() {
        return pageCacheMaxPageCount > 0L || pageCacheSize > 0L || pageCacheWriteCount > 0L;
    }

    public boolean hasAttributeOverflowStatistics() {
        return attributeOverflowWriteCount > 0L || attributeOverflowValueBytes > 0L;
    }

    public boolean hasRecoveryStatistics() {
        return subsystemRecoveryRecordCount > 0L;
    }

    public boolean cachePinsBalanced() {
        return pageCachePinCount == pageCacheUnpinCount && pageCachePinnedPageCount == 0L;
    }

    public boolean dirtyStateClean() {
        return pageCacheDirtyPageCount == 0L && pageCacheFlushListPageCount == 0L;
    }

    public boolean recoveryBoundaryComplete() {
        return rowPageRedoRecordCount > 0L
                && indexPageRedoRecordCount > 0L
                && overflowPageRedoRecordCount > 0L
                && freeSpaceMapRedoRecordCount > 0L
                && transactionOutcomeRedoRecordCount > 0L
                && checkpointRecoveryRecordCount > 0L;
    }

    public String summary() {
        return providerId
                + " segment=" + segment
                + " container=" + containerId
                + " rows=" + logicalRowCount
                + " rowPages=" + rowPageCount
                + " orderedIndexPages=" + orderedIndexPageCount
                + " overflowPages=" + overflowPageCount
                + " cache=" + pageCacheSize + "/" + pageCacheMaxPageCount
                + " recoveryRecords=" + subsystemRecoveryRecordCount
                + " bytes=" + observedStorageBytes;
    }
}
