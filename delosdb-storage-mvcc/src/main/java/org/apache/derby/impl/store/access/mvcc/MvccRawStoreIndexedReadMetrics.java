/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreIndexedReadMetrics

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

/** Per-scan physical attribution for the RawStore MVCC ordered-index path. */
final class MvccRawStoreIndexedReadMetrics {
    static final Snapshot EMPTY = new Snapshot(
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    private long candidatesVisited;
    private long coveringCandidates;
    private long coveredCandidates;
    private long fallbackCandidates;
    private long directoryPageAcquisitions;
    private long directoryPageBatchCandidates;
    private long directoryPageReuseHits;
    private long directoryLogicalFallbacks;
    private long directoryHeadSummaryChecks;
    private long directoryHeadSummaryHits;
    private long directoryHeadSummaryFallbacks;
    private long versionPageAcquisitions;
    private long versionSlotFetches;
    private long visibilityChecks;
    private long versionChainSteps;
    private long versionLogicalFallbacks;
    private long currentRowAnchorChecks;
    private long currentRowAnchorHits;
    private long currentRowAnchorFallbacks;

    void candidateVisited(boolean coveringEligible) {
        candidatesVisited++;
        if (coveringEligible) {
            coveringCandidates++;
        }
    }

    void coveredCandidate() {
        coveredCandidates++;
    }

    void fallbackCandidate() {
        fallbackCandidates++;
    }

    void directoryPageAcquired() {
        directoryPageAcquisitions++;
    }

    void directoryPageBatchCandidate() {
        directoryPageBatchCandidates++;
    }

    void directoryPageReuseHit() {
        directoryPageReuseHits++;
    }

    void directoryLogicalFallback() {
        directoryLogicalFallbacks++;
    }

    void directoryHeadSummaryChecked() {
        directoryHeadSummaryChecks++;
    }

    void directoryHeadSummaryHit() {
        directoryHeadSummaryHits++;
    }

    void directoryHeadSummaryFallback() {
        directoryHeadSummaryFallbacks++;
    }

    void versionPageAcquired() {
        versionPageAcquisitions++;
    }

    void versionSlotFetched() {
        versionSlotFetches++;
    }

    void visibilityChecked() {
        visibilityChecks++;
    }

    void versionChainStep() {
        versionChainSteps++;
    }

    void versionLogicalFallback() {
        versionLogicalFallbacks++;
    }

    void currentRowAnchorChecked() {
        currentRowAnchorChecks++;
    }

    void currentRowAnchorHit() {
        currentRowAnchorHits++;
    }

    void currentRowAnchorFallback() {
        currentRowAnchorFallbacks++;
    }

    Snapshot snapshot() {
        return new Snapshot(
                candidatesVisited,
                coveringCandidates,
                coveredCandidates,
                fallbackCandidates,
                directoryPageAcquisitions,
                directoryPageBatchCandidates,
                directoryPageReuseHits,
                directoryLogicalFallbacks,
                directoryHeadSummaryChecks,
                directoryHeadSummaryHits,
                directoryHeadSummaryFallbacks,
                versionPageAcquisitions,
                versionSlotFetches,
                visibilityChecks,
                versionChainSteps,
                versionLogicalFallbacks,
                currentRowAnchorChecks,
                currentRowAnchorHits,
                currentRowAnchorFallbacks);
    }

    record Snapshot(
            long candidatesVisited,
            long coveringCandidates,
            long coveredCandidates,
            long fallbackCandidates,
            long directoryPageAcquisitions,
            long directoryPageBatchCandidates,
            long directoryPageReuseHits,
            long directoryLogicalFallbacks,
            long directoryHeadSummaryChecks,
            long directoryHeadSummaryHits,
            long directoryHeadSummaryFallbacks,
            long versionPageAcquisitions,
            long versionSlotFetches,
            long visibilityChecks,
            long versionChainSteps,
            long versionLogicalFallbacks,
            long currentRowAnchorChecks,
            long currentRowAnchorHits,
            long currentRowAnchorFallbacks) {
    }
}
