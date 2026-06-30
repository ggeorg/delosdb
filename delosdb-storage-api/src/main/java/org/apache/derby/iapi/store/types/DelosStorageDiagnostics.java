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

    void clearRuntimeStateForTesting();

    int runtimeStateCountForTesting();

    Path pageVolumeStateFileForTesting(int segment, long containerId);

    Path rowDirectoryStateFileForTesting(int segment, long containerId);

    Path pageMutationLogFileForTesting(int segment, long containerId);

    Path writeAheadLogFileForTesting(int segment, long containerId);

    Path checkpointFileForTesting(int segment, long containerId);

    Path legacySnapshotFileForTesting(int segment, long containerId);

    String checkpointStatusForTesting(int segment, long containerId);

    int physicalVersionCountForTesting(int segment, long containerId);

    int logicalRowCountForTesting(int segment, long containerId);

    long pageCountForTesting(int segment, long containerId);

    long overflowPageCountForTesting(int segment, long containerId);

    int consistencyErrorCountForTesting(int segment, long containerId);

    String consistencySummaryForTesting(int segment, long containerId);

    void assertConsistentForTesting(int segment, long containerId);

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

    int candidateIndexRowIdCountForTesting();

    int candidateIndexVisibilityRejectCountForTesting();

    int candidateIndexQualifierRejectCountForTesting();

    void clearTransactionsForTesting();

    boolean isProviderScan(Object scanController);

    boolean hasLocatorHint(StoreRowLocation location);
}
