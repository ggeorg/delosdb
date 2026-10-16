/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageIndexDiagnostics

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

/** Ordered-index authority, lookup, fallback, and parity diagnostics. */
interface DelosStorageIndexDiagnostics {
    default long orderedIndexPageCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexEntryCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int orderedIndexDistinctKeyCountForTesting(int segment, long containerId) {
        return 0;
    }

    default long orderedIndexRebuildCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default List<String> orderedIndexEntrySummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long orderedIndexLookupCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexHitCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexFallbackCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default long orderedIndexFallbackReasonCountForTesting(
            int segment,
            long containerId,
            DelosStorageOrderedIndexFallbackReason reason) {
        return 0L;
    }

    default List<String> orderedIndexFallbackReasonSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default long orderedIndexRowIdCountForTesting(int segment, long containerId) {
        return 0L;
    }

    default int orderedIndexCandidateParityErrorCountForTesting(int segment, long containerId) {
        return 0;
    }

    default List<String> orderedIndexCandidateParityErrorSummariesForTesting(int segment, long containerId) {
        return List.of();
    }

    default DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting(
            int segment,
            long containerId) {
        return DelosStorageOrderedIndexDiagnostics.AuthorityMode.UNAVAILABLE;
    }

    default DelosStorageOrderedIndexDiagnostics orderedIndexDiagnosticsForTesting(int segment, long containerId) {
        return new DelosStorageOrderedIndexDiagnostics(
                orderedIndexAuthorityModeForTesting(segment, containerId),
                orderedIndexPageCountForTesting(segment, containerId),
                orderedIndexEntryCountForTesting(segment, containerId),
                orderedIndexDistinctKeyCountForTesting(segment, containerId),
                orderedIndexRebuildCountForTesting(segment, containerId),
                orderedIndexLookupCountForTesting(segment, containerId),
                orderedIndexHitCountForTesting(segment, containerId),
                orderedIndexFallbackCountForTesting(segment, containerId),
                orderedIndexRowIdCountForTesting(segment, containerId),
                orderedIndexCandidateParityErrorCountForTesting(segment, containerId));
    }
}
