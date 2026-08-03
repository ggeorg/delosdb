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
    static final Snapshot EMPTY = new Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

    private long candidatesVisited;
    private long coveringCandidates;
    private long coveredCandidates;
    private long fallbackCandidates;
    private long directoryPageAcquisitions;
    private long directoryLogicalFallbacks;
    private long versionPageAcquisitions;
    private long versionSlotFetches;
    private long visibilityChecks;
    private long versionChainSteps;
    private long versionLogicalFallbacks;

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

    void directoryLogicalFallback() {
        directoryLogicalFallbacks++;
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

    Snapshot snapshot() {
        return new Snapshot(
                candidatesVisited,
                coveringCandidates,
                coveredCandidates,
                fallbackCandidates,
                directoryPageAcquisitions,
                directoryLogicalFallbacks,
                versionPageAcquisitions,
                versionSlotFetches,
                visibilityChecks,
                versionChainSteps,
                versionLogicalFallbacks);
    }

    record Snapshot(
            long candidatesVisited,
            long coveringCandidates,
            long coveredCandidates,
            long fallbackCandidates,
            long directoryPageAcquisitions,
            long directoryLogicalFallbacks,
            long versionPageAcquisitions,
            long versionSlotFetches,
            long visibilityChecks,
            long versionChainSteps,
            long versionLogicalFallbacks) {
    }
}
