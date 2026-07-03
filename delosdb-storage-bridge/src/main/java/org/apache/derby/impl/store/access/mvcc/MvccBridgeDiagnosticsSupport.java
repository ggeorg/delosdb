/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccBridgeDiagnosticsSupport

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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Package-local owner for MVCC bridge diagnostics counters.
 *
 * <p>Production bridge classes update these counters, while storage-api
 * diagnostics expose them to smoke fixtures. The older public bridge
 * {@code *ForTesting()} methods remain only as compatibility shims for
 * historical dev smokes.</p>
 */
final class MvccBridgeDiagnosticsSupport {
    private static final AtomicInteger INSERT_COUNT = new AtomicInteger();
    private static final AtomicInteger DELETE_COUNT = new AtomicInteger();
    private static final AtomicInteger UPDATE_COUNT = new AtomicInteger();
    private static final AtomicInteger OPEN_COUNT = new AtomicInteger();
    private static final AtomicInteger QUALIFIER_REJECT_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_INDEX_LOOKUP_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_INDEX_FALLBACK_LOOKUP_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_INDEX_ROWID_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_INDEX_VISIBILITY_REJECT_COUNT = new AtomicInteger();
    private static final AtomicInteger CANDIDATE_INDEX_QUALIFIER_REJECT_COUNT = new AtomicInteger();
    private static final AtomicInteger PAGE_BACKED_COMMITTED_SCAN_COUNT = new AtomicInteger();
    private static final AtomicInteger PAGE_BACKED_COMMITTED_READ_COUNT = new AtomicInteger();
    private static final AtomicInteger ROW_ID_FAST_PATH_READ_COUNT = new AtomicInteger();
    private static final AtomicInteger ROW_ID_FAST_PATH_HIT_COUNT = new AtomicInteger();

    private MvccBridgeDiagnosticsSupport() {
    }

    static void resetMutationCountersForDiagnostics() {
        resetInsertCountForDiagnostics();
        resetDeleteCountForDiagnostics();
        resetUpdateCountForDiagnostics();
    }

    static void resetInsertCountForDiagnostics() {
        INSERT_COUNT.set(0);
    }

    static void incrementInsertCount() {
        INSERT_COUNT.incrementAndGet();
    }

    static int insertCountForDiagnostics() {
        return INSERT_COUNT.get();
    }

    static void resetDeleteCountForDiagnostics() {
        DELETE_COUNT.set(0);
    }

    static void incrementDeleteCount() {
        DELETE_COUNT.incrementAndGet();
    }

    static int deleteCountForDiagnostics() {
        return DELETE_COUNT.get();
    }

    static void resetUpdateCountForDiagnostics() {
        UPDATE_COUNT.set(0);
    }

    static void incrementUpdateCount() {
        UPDATE_COUNT.incrementAndGet();
    }

    static int updateCountForDiagnostics() {
        return UPDATE_COUNT.get();
    }

    static void resetScanCountersForDiagnostics() {
        OPEN_COUNT.set(0);
        resetQualifierRejectCountForDiagnostics();
        resetCandidateIndexCountersForDiagnostics();
        resetPageBackedCommittedReadCountersForDiagnostics();
    }

    static void resetOpenCountForDiagnostics() {
        OPEN_COUNT.set(0);
    }

    static void incrementOpenCount() {
        OPEN_COUNT.incrementAndGet();
    }

    static int openCountForDiagnostics() {
        return OPEN_COUNT.get();
    }

    static void resetQualifierRejectCountForDiagnostics() {
        QUALIFIER_REJECT_COUNT.set(0);
    }

    static void incrementQualifierRejectCount() {
        QUALIFIER_REJECT_COUNT.incrementAndGet();
    }

    static int qualifierRejectCountForDiagnostics() {
        return QUALIFIER_REJECT_COUNT.get();
    }

    static void resetCandidateIndexCountersForDiagnostics() {
        CANDIDATE_INDEX_LOOKUP_COUNT.set(0);
        CANDIDATE_INDEX_FALLBACK_LOOKUP_COUNT.set(0);
        CANDIDATE_INDEX_ROWID_COUNT.set(0);
        CANDIDATE_INDEX_VISIBILITY_REJECT_COUNT.set(0);
        CANDIDATE_INDEX_QUALIFIER_REJECT_COUNT.set(0);
    }

    static void incrementCandidateIndexLookupCount() {
        CANDIDATE_INDEX_LOOKUP_COUNT.incrementAndGet();
    }

    static int candidateIndexLookupCountForDiagnostics() {
        return CANDIDATE_INDEX_LOOKUP_COUNT.get();
    }

    static void incrementCandidateIndexFallbackLookupCount() {
        CANDIDATE_INDEX_FALLBACK_LOOKUP_COUNT.incrementAndGet();
    }

    static int candidateIndexFallbackLookupCountForDiagnostics() {
        return CANDIDATE_INDEX_FALLBACK_LOOKUP_COUNT.get();
    }

    static void addCandidateIndexRowIdCount(int rowIds) {
        CANDIDATE_INDEX_ROWID_COUNT.addAndGet(rowIds);
    }

    static int candidateIndexRowIdCountForDiagnostics() {
        return CANDIDATE_INDEX_ROWID_COUNT.get();
    }

    static void incrementCandidateIndexVisibilityRejectCount() {
        CANDIDATE_INDEX_VISIBILITY_REJECT_COUNT.incrementAndGet();
    }

    static int candidateIndexVisibilityRejectCountForDiagnostics() {
        return CANDIDATE_INDEX_VISIBILITY_REJECT_COUNT.get();
    }

    static void incrementCandidateIndexQualifierRejectCount() {
        CANDIDATE_INDEX_QUALIFIER_REJECT_COUNT.incrementAndGet();
    }

    static int candidateIndexQualifierRejectCountForDiagnostics() {
        return CANDIDATE_INDEX_QUALIFIER_REJECT_COUNT.get();
    }

    static void resetPageBackedCommittedReadCountersForDiagnostics() {
        PAGE_BACKED_COMMITTED_SCAN_COUNT.set(0);
        PAGE_BACKED_COMMITTED_READ_COUNT.set(0);
        resetRowIdFastPathCountersForDiagnostics();
    }

    static void incrementPageBackedCommittedScanCount() {
        PAGE_BACKED_COMMITTED_SCAN_COUNT.incrementAndGet();
    }

    static int pageBackedCommittedScanCountForDiagnostics() {
        return PAGE_BACKED_COMMITTED_SCAN_COUNT.get();
    }

    static void incrementPageBackedCommittedReadCount() {
        PAGE_BACKED_COMMITTED_READ_COUNT.incrementAndGet();
    }

    static int pageBackedCommittedReadCountForDiagnostics() {
        return PAGE_BACKED_COMMITTED_READ_COUNT.get();
    }

    static void resetRowIdFastPathCountersForDiagnostics() {
        ROW_ID_FAST_PATH_READ_COUNT.set(0);
        ROW_ID_FAST_PATH_HIT_COUNT.set(0);
    }

    static void incrementRowIdFastPathReadCount() {
        ROW_ID_FAST_PATH_READ_COUNT.incrementAndGet();
    }

    static int rowIdFastPathReadCountForDiagnostics() {
        return ROW_ID_FAST_PATH_READ_COUNT.get();
    }

    static void incrementRowIdFastPathHitCount() {
        ROW_ID_FAST_PATH_HIT_COUNT.incrementAndGet();
    }

    static int rowIdFastPathHitCountForDiagnostics() {
        return ROW_ID_FAST_PATH_HIT_COUNT.get();
    }

}
