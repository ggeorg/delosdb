/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCacheRecoveryDiagnostics

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

import java.util.List;

/** Page-cache, overflow, recovery, and consistency diagnostics. */
interface DelosStorageCacheRecoveryDiagnostics {
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
}
