/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStoragePersistenceDiagnostics

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

/** Table persistence, row-version, and write-path diagnostics. */
interface DelosStoragePersistenceDiagnostics {
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
}
