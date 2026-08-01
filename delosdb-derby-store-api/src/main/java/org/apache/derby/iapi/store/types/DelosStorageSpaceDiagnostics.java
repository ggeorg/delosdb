/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageSpaceDiagnostics

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

/** Page, free-space, visibility, and local-pruning diagnostics. */
interface DelosStorageSpaceDiagnostics {
    long pageCountForTesting(int segment, long containerId);

    long overflowPageCountForTesting(int segment, long containerId);

    long reusablePageCountForTesting(int segment, long containerId);

    default long freeSpaceMapPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int freeSpaceMapMaxFreeBytesForTesting(int segment, long containerId) {
        return 0;
    }

    default long freeSpaceMapLookupCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapNonLastHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapMissCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapStaleEntryCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long freeSpaceMapRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> freeSpaceMapPageSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long visibilityMapPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapOldVersionPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapPrunablePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapTombstonePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapAllVisiblePageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapOverflowPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapNeedsCheckerPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapUpdateCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long visibilityMapRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> visibilityMapPageSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long pageLocalPruneAttemptCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneSuccessCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneFallbackCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long pageLocalPruneRemovedVersionCountForTesting(int segment, long containerId) {
        return 0L;
    }
}
