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
 * Provider-neutral storage diagnostics façade.
 *
 * <p>The façade preserves the stable diagnostics type used by tests and
 * inspection tools, while the inherited interfaces keep database lifecycle,
 * persistence, space management, maintenance, index, cache/recovery, and
 * operation concerns independently readable and maintainable.</p>
 */
public interface DelosStorageDiagnostics
        extends DelosStorageDatabaseDiagnostics,
                DelosStoragePersistenceDiagnostics,
                DelosStorageSpaceDiagnostics,
                DelosStorageMaintenanceDiagnostics,
                DelosStorageIndexDiagnostics,
                DelosStorageCacheRecoveryDiagnostics,
                DelosStorageOperationDiagnostics {
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
}
