/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCapabilities

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
 * Provider-neutral, read-only description of what a storage target can safely do.
 *
 * <p>This is a capability report, not an optimizer instruction.  Capabilities
 * are deliberately conservative and must be proven by gates before future
 * optimizer or predicate-pushdown code may consume them.</p>
 */
public record DelosStorageCapabilities(String providerId,
                                       int segment,
                                       long containerId,
                                       boolean readOnly,
                                       boolean supportsRowIdLookup,
                                       boolean supportsOrderedEqualityLookup,
                                       boolean supportsOrderedRangeScan,
                                       boolean supportsStableKeyOrder,
                                       boolean supportsCurrentCommittedShortcut,
                                       boolean supportsSnapshotShortcut,
                                       boolean supportsAttributeOverflow,
                                       boolean candidateIndexAuthorityRemoved,
                                       boolean supportsStorageStatistics,
                                       boolean supportsCostEstimate,
                                       boolean consumedByDerbyOptimizer,
                                       List<String> observations) {
    public DelosStorageCapabilities {
        providerId = DelosStorageProviderIds.normalize(providerId);
        observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (!readOnly) {
            throw new IllegalArgumentException("storage capabilities must be read-only");
        }
        if (consumedByDerbyOptimizer) {
            throw new IllegalArgumentException("storage capabilities are not optimizer-consumed yet");
        }
    }

    public static DelosStorageCapabilities fromStatistics(
            DelosStorageStatistics statistics,
            DelosStorageCostEstimate costEstimate) {
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(costEstimate, "costEstimate");
        if (!DelosStorageProviderIds.matches(statistics.providerId(), costEstimate.providerId())
                || statistics.segment() != costEstimate.segment()
                || statistics.containerId() != costEstimate.containerId()) {
            throw new IllegalArgumentException("statistics and cost estimate target do not match");
        }

        String providerId = statistics.providerId();
        boolean mvcc = DelosStorageProviderIds.isMvcc(providerId);
        boolean heap = DelosStorageProviderIds.isHeap(providerId);
        List<String> observations = new ArrayList<>();
        observations.add("storage capabilities are read-only");
        observations.add("provider: " + providerId);
        observations.add("capabilities are not consumed by Derby optimizer");

        if (mvcc) {
            observations.add("MVCC row-id lookup is available through the DelosDB row-id fast path");
            observations.add("MVCC ordered equality/range capabilities are backed by ordered index pages");
            observations.add("MVCC current-committed shortcuts are available for covered safe predicates");
            observations.add("MVCC snapshot shortcut remains disabled until separately proven");
            observations.add("MVCC candidate-index authority has been removed from normal reads");
        } else if (heap) {
            observations.add("Derby heap keeps inherited compatibility behavior");
            observations.add("Derby heap ordered/index shortcuts are not exposed through DelosDB metadata yet");
            observations.add("Derby heap capability reporting is diagnostic-only");
        } else {
            observations.add("unknown provider uses conservative capability defaults");
        }

        return new DelosStorageCapabilities(
                providerId,
                statistics.segment(),
                statistics.containerId(),
                true,
                mvcc,
                mvcc,
                mvcc,
                mvcc,
                mvcc,
                false,
                mvcc,
                mvcc,
                statistics.readOnly(),
                costEstimate.readOnly(),
                false,
                observations);
    }

    public boolean supportsOrderedLookup() {
        return supportsOrderedEqualityLookup || supportsOrderedRangeScan;
    }

    public boolean optimizerSafe() {
        return !consumedByDerbyOptimizer;
    }

    public String summary() {
        return providerId
                + " segment=" + segment
                + " container=" + containerId
                + " rowId=" + supportsRowIdLookup
                + " orderedEquality=" + supportsOrderedEqualityLookup
                + " orderedRange=" + supportsOrderedRangeScan
                + " currentCommitted=" + supportsCurrentCommittedShortcut
                + " snapshotShortcut=" + supportsSnapshotShortcut
                + " optimizerConsumed=" + consumedByDerbyOptimizer;
    }
}
