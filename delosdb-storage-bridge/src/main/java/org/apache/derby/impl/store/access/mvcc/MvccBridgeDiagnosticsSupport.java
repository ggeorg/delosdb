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

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageProviderIds;
import org.apache.derby.iapi.store.types.DelosStorageLifecycleJfr;
import org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic;

/** Database-owned MVCC bridge observations and bounded path history. */
final class MvccBridgeDiagnosticsSupport {
    static final int STORAGE_PATH_DIAGNOSTIC_CAPACITY = 256;

    private final AtomicInteger insertCount = new AtomicInteger();
    private final AtomicInteger deleteCount = new AtomicInteger();
    private final AtomicInteger updateCount = new AtomicInteger();
    private final AtomicInteger openCount = new AtomicInteger();
    private final AtomicInteger qualifierRejectCount = new AtomicInteger();
    private final AtomicInteger candidateIndexLookupCount = new AtomicInteger();
    private final AtomicInteger candidateIndexFallbackLookupCount = new AtomicInteger();
    private final AtomicInteger candidateIndexRowIdCount = new AtomicInteger();
    private final AtomicInteger candidateIndexVisibilityRejectCount = new AtomicInteger();
    private final AtomicInteger candidateIndexQualifierRejectCount = new AtomicInteger();
    private final AtomicInteger pageBackedCommittedScanCount = new AtomicInteger();
    private final AtomicInteger pageBackedCommittedReadCount = new AtomicInteger();
    private final AtomicInteger rowIdFastPathReadCount = new AtomicInteger();
    private final AtomicInteger rowIdFastPathHitCount = new AtomicInteger();
    private final AtomicLong snapshotSequence = new AtomicLong();
    private final AtomicLong droppedStoragePathDiagnosticCount = new AtomicLong();
    private final Object storagePathMonitor = new Object();
    private final Deque<DelosStoragePathDiagnostic> storagePathDiagnostics = new ArrayDeque<>();

    void resetMutationCountersForDiagnostics() {
        insertCount.set(0);
        deleteCount.set(0);
        updateCount.set(0);
    }

    void incrementInsertCount() {
        insertCount.incrementAndGet();
    }

    int insertCountForDiagnostics() {
        return insertCount.get();
    }

    void incrementDeleteCount() {
        deleteCount.incrementAndGet();
    }

    int deleteCountForDiagnostics() {
        return deleteCount.get();
    }

    void incrementUpdateCount() {
        updateCount.incrementAndGet();
    }

    int updateCountForDiagnostics() {
        return updateCount.get();
    }

    void resetScanCountersForDiagnostics() {
        openCount.set(0);
        qualifierRejectCount.set(0);
        candidateIndexLookupCount.set(0);
        candidateIndexFallbackLookupCount.set(0);
        candidateIndexRowIdCount.set(0);
        candidateIndexVisibilityRejectCount.set(0);
        candidateIndexQualifierRejectCount.set(0);
        pageBackedCommittedScanCount.set(0);
        pageBackedCommittedReadCount.set(0);
        rowIdFastPathReadCount.set(0);
        rowIdFastPathHitCount.set(0);
        resetStoragePathDiagnosticsForDiagnostics();
    }

    void incrementOpenCount() {
        openCount.incrementAndGet();
    }

    int openCountForDiagnostics() {
        return openCount.get();
    }

    void resetQualifierRejectCountForDiagnostics() {
        qualifierRejectCount.set(0);
    }

    void incrementQualifierRejectCount() {
        qualifierRejectCount.incrementAndGet();
    }

    int qualifierRejectCountForDiagnostics() {
        return qualifierRejectCount.get();
    }

    void resetCandidateIndexCountersForDiagnostics() {
        candidateIndexLookupCount.set(0);
        candidateIndexFallbackLookupCount.set(0);
        candidateIndexRowIdCount.set(0);
        candidateIndexVisibilityRejectCount.set(0);
        candidateIndexQualifierRejectCount.set(0);
    }

    void incrementCandidateIndexLookupCount() {
        candidateIndexLookupCount.incrementAndGet();
    }

    int candidateIndexLookupCountForDiagnostics() {
        return candidateIndexLookupCount.get();
    }

    void incrementCandidateIndexFallbackLookupCount() {
        candidateIndexFallbackLookupCount.incrementAndGet();
    }

    int candidateIndexFallbackLookupCountForDiagnostics() {
        return candidateIndexFallbackLookupCount.get();
    }

    void addCandidateIndexRowIdCount(int rowIds) {
        candidateIndexRowIdCount.addAndGet(rowIds);
    }

    int candidateIndexRowIdCountForDiagnostics() {
        return candidateIndexRowIdCount.get();
    }

    void incrementCandidateIndexVisibilityRejectCount() {
        candidateIndexVisibilityRejectCount.incrementAndGet();
    }

    int candidateIndexVisibilityRejectCountForDiagnostics() {
        return candidateIndexVisibilityRejectCount.get();
    }

    void incrementCandidateIndexQualifierRejectCount() {
        candidateIndexQualifierRejectCount.incrementAndGet();
    }

    int candidateIndexQualifierRejectCountForDiagnostics() {
        return candidateIndexQualifierRejectCount.get();
    }

    void incrementPageBackedCommittedScanCount() {
        pageBackedCommittedScanCount.incrementAndGet();
    }

    int pageBackedCommittedScanCountForDiagnostics() {
        return pageBackedCommittedScanCount.get();
    }

    void incrementPageBackedCommittedReadCount() {
        pageBackedCommittedReadCount.incrementAndGet();
    }

    int pageBackedCommittedReadCountForDiagnostics() {
        return pageBackedCommittedReadCount.get();
    }

    void resetRowIdFastPathCountersForDiagnostics() {
        rowIdFastPathReadCount.set(0);
        rowIdFastPathHitCount.set(0);
    }

    void incrementRowIdFastPathReadCount() {
        rowIdFastPathReadCount.incrementAndGet();
    }

    int rowIdFastPathReadCountForDiagnostics() {
        return rowIdFastPathReadCount.get();
    }

    void incrementRowIdFastPathHitCount() {
        rowIdFastPathHitCount.incrementAndGet();
    }

    int rowIdFastPathHitCountForDiagnostics() {
        return rowIdFastPathHitCount.get();
    }

    void resetStoragePathDiagnosticsForDiagnostics() {
        synchronized (storagePathMonitor) {
            storagePathDiagnostics.clear();
            droppedStoragePathDiagnosticCount.set(0L);
        }
    }

    void recordStoragePathDiagnostic(DelosStoragePathDiagnostic diagnostic) {
        synchronized (storagePathMonitor) {
            if (storagePathDiagnostics.size() == STORAGE_PATH_DIAGNOSTIC_CAPACITY) {
                storagePathDiagnostics.removeFirst();
                droppedStoragePathDiagnosticCount.incrementAndGet();
            }
            storagePathDiagnostics.addLast(diagnostic);
        }
        DelosStorageLifecycleJfr.recordStoragePathDecision(diagnostic);
    }

    List<DelosStoragePathDiagnostic> storagePathDiagnosticsForDiagnostics() {
        synchronized (storagePathMonitor) {
            return List.copyOf(storagePathDiagnostics);
        }
    }

    List<String> storagePathDiagnosticLinesForDiagnostics() {
        List<DelosStoragePathDiagnostic> diagnostics = storagePathDiagnosticsForDiagnostics();
        List<String> lines = new ArrayList<>(diagnostics.size());
        for (DelosStoragePathDiagnostic diagnostic : diagnostics) {
            lines.add(diagnostic.diagnosticLine());
        }
        return List.copyOf(lines);
    }

    DelosDatabaseStorageSnapshot snapshot(
            Path databaseDirectory,
            boolean runtimeActive,
            int tableStateCount,
            DelosDatabaseCommitTimingSnapshot commitTiming) {
        List<DelosStoragePathDiagnostic> pathDiagnostics = storagePathDiagnosticsForDiagnostics();
        return new DelosDatabaseStorageSnapshot(
                DelosDatabaseStorageSnapshot.CURRENT_SCHEMA_VERSION,
                DelosStorageProviderIds.MVCC_PROVIDER_ID,
                databaseDirectory.toAbsolutePath().normalize().toString(),
                DelosDatabaseStorageSnapshot.WEAKLY_CONSISTENT_COLLECTION,
                snapshotSequence.incrementAndGet(),
                System.currentTimeMillis(),
                runtimeActive,
                tableStateCount,
                insertCount.get(),
                updateCount.get(),
                deleteCount.get(),
                openCount.get(),
                qualifierRejectCount.get(),
                candidateIndexLookupCount.get(),
                candidateIndexFallbackLookupCount.get(),
                candidateIndexRowIdCount.get(),
                candidateIndexVisibilityRejectCount.get(),
                candidateIndexQualifierRejectCount.get(),
                pageBackedCommittedScanCount.get(),
                pageBackedCommittedReadCount.get(),
                rowIdFastPathReadCount.get(),
                rowIdFastPathHitCount.get(),
                STORAGE_PATH_DIAGNOSTIC_CAPACITY,
                droppedStoragePathDiagnosticCount.get(),
                pathDiagnostics,
                commitTiming);
    }
}
