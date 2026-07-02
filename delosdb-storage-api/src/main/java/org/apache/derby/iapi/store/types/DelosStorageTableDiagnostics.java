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

    long pageCountForTesting();

    long overflowPageCountForTesting();

    long reusablePageCountForTesting();

    long pageCacheMaxPageCountForTesting();

    long pageCacheSizeForTesting();

    long pageCacheHitCountForTesting();

    long pageCacheMissCountForTesting();

    long pageCacheWriteCountForTesting();

    long pageCacheEvictionCountForTesting();

    long pageCacheInvalidationCountForTesting();

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

    default DelosStoragePageCacheDiagnostics pageCacheDiagnosticsForTesting() {
        return new DelosStoragePageCacheDiagnostics(
                pageCacheMaxPageCountForTesting(),
                pageCacheSizeForTesting(),
                pageCacheHitCountForTesting(),
                pageCacheMissCountForTesting(),
                pageCacheWriteCountForTesting(),
                pageCacheEvictionCountForTesting(),
                pageCacheInvalidationCountForTesting());
    }

    default DelosStorageConsistencyDiagnostics consistencyDiagnosticsForTesting() {
        return new DelosStorageConsistencyDiagnostics(
                consistencyErrorCountForTesting(),
                consistencySummaryForTesting());
    }

    DelosVacuumOutcome lastVacuumOutcomeForTesting();

    Path legacySnapshotFileForTesting();
}
