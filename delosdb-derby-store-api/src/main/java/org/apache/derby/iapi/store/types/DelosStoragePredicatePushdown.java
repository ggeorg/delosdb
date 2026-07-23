/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStoragePredicatePushdown

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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Metadata-only storage predicate pushdown/remainder plan for one target.
 *
 * <p>The plan is deliberately optimizer-neutral. It records a safe split between
 * storage-consumable predicate fragments and Derby remainder fragments, but it
 * does not make Derby consume the split.</p>
 */
public record DelosStoragePredicatePushdown(String providerId,
                                            int segment,
                                            long containerId,
                                            boolean readOnly,
                                            String predicateDescription,
                                            String readMode,
                                            boolean storagePushdownSupported,
                                            boolean pushedToStorage,
                                            boolean requiresDerbyRemainder,
                                            boolean safeForCurrentCommittedShortcut,
                                            boolean safeForSnapshotShortcut,
                                            boolean consumedByDerbyOptimizer,
                                            List<String> pushedPredicates,
                                            List<String> remainderPredicates,
                                            List<String> observations) {
    public DelosStoragePredicatePushdown {
        providerId = DelosStorageProviderIds.normalize(providerId);
        predicateDescription = Objects.requireNonNull(predicateDescription, "predicateDescription").trim();
        readMode = Objects.requireNonNull(readMode, "readMode").trim();
        pushedPredicates = List.copyOf(Objects.requireNonNull(pushedPredicates, "pushedPredicates"));
        remainderPredicates = List.copyOf(Objects.requireNonNull(remainderPredicates, "remainderPredicates"));
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (!readOnly) {
            throw new IllegalArgumentException("predicate pushdown metadata must be read-only");
        }
        if (consumedByDerbyOptimizer) {
            throw new IllegalArgumentException("predicate pushdown metadata is not optimizer-consumed yet");
        }
        if (predicateDescription.isEmpty()) {
            throw new IllegalArgumentException("predicate description must not be blank");
        }
        if (readMode.isEmpty()) {
            throw new IllegalArgumentException("read mode must not be blank");
        }
        if (pushedToStorage != !pushedPredicates.isEmpty()) {
            throw new IllegalArgumentException("pushedToStorage must match pushed predicate list");
        }
        if (requiresDerbyRemainder != !remainderPredicates.isEmpty()) {
            throw new IllegalArgumentException("requiresDerbyRemainder must match remainder predicate list");
        }
    }

    public static DelosStoragePredicatePushdown plan(
            DelosStorageCapabilities capabilities,
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(request, "request");
        if (!DelosStorageProviderIds.matches(capabilities.providerId(), request.providerId())
                || capabilities.segment() != request.segment()
                || capabilities.containerId() != request.containerId()) {
            throw new IllegalArgumentException("capabilities and predicate request target do not match");
        }

        boolean currentCommittedSafe = request.currentCommittedRead()
                && !request.snapshotRead()
                && !request.writerBorrowedRead()
                && capabilities.supportsCurrentCommittedShortcut()
                && capabilities.supportsOrderedLookup();
        boolean snapshotSafe = request.snapshotRead()
                && capabilities.supportsSnapshotShortcut()
                && capabilities.supportsOrderedLookup();
        boolean canPush = currentCommittedSafe
                && request.hasStorageCandidates()
                && !capabilities.consumedByDerbyOptimizer();

        List<String> pushedPredicates = canPush
                ? request.storageCandidatePredicates()
                : List.of();
        List<String> remainderPredicates = new ArrayList<>();
        if (!canPush) {
            remainderPredicates.addAll(request.storageCandidatePredicates());
        }
        remainderPredicates.addAll(request.derbyRemainderPredicates());

        List<String> observations = new ArrayList<>();
        observations.add("predicate pushdown model is read-only");
        observations.add("Derby optimizer consumption remains disabled");
        observations.add("provider: " + capabilities.providerId());
        observations.add("read mode: " + request.readMode());
        if (canPush) {
            observations.add("storage candidates are safe for current-committed ordered lookup metadata");
        } else if (request.snapshotRead()) {
            observations.add("snapshot reads keep all candidates as Derby remainders until snapshot shortcut is proven");
        } else if (request.writerBorrowedRead()) {
            observations.add("writer-borrowed reads keep all candidates as Derby remainders");
        } else if (!capabilities.supportsOrderedLookup()) {
            observations.add("provider does not expose Delos ordered lookup capability");
        } else {
            observations.add("no storage predicate was pushed for this metadata request");
        }

        return new DelosStoragePredicatePushdown(
                capabilities.providerId(),
                capabilities.segment(),
                capabilities.containerId(),
                true,
                request.predicateDescription(),
                request.readMode(),
                capabilities.supportsOrderedLookup(),
                !pushedPredicates.isEmpty(),
                !remainderPredicates.isEmpty(),
                currentCommittedSafe,
                snapshotSafe,
                false,
                pushedPredicates,
                List.copyOf(remainderPredicates),
                observations);
    }

    public boolean optimizerSafe() {
        return !consumedByDerbyOptimizer;
    }

    public String summary() {
        return providerId
                + " segment=" + segment
                + " container=" + containerId
                + " readMode=" + readMode
                + " pushed=" + pushedPredicates.size()
                + " remainder=" + remainderPredicates.size()
                + " optimizerConsumed=" + consumedByDerbyOptimizer;
    }
}
