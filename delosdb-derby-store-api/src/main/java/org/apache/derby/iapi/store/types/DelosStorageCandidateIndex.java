/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageCandidateIndex

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
import java.util.Optional;

/**
 * Candidate-row index diagnostics plus ordered-page lookup boundary.
 *
 * <p>The legacy candidate index is no longer SQL read authority. It remains
 * populated so parity diagnostics can compare it with ordered MVCC index pages.
 * Normal current-committed reads must use the ordered page-backed methods below
 * or fall back to a full committed-image scan.</p>
 */
public interface DelosStorageCandidateIndex {
    /**
     * Legacy diagnostic lookup. Normal SQL read paths must not use this as
     * row-id authority.
     */
    Optional<List<Long>> candidateRowIdsFor(int column, String value);

    /**
     * Atomically verifies that {@code snapshot} still names the current committed
     * image and derives equality candidates from that same image. Providers must
     * perform both actions under the same commit-exclusion boundary.
     *
     * <p>An empty optional means the provider cannot safely answer the lookup
     * and callers must use the authoritative MVCC scan. A present empty list
     * means the current committed index answered and found no matching rows.</p>
     */
    default Optional<List<Long>> orderedIndexRowIdsFor(
            DelosStorageSnapshot snapshot,
            int column,
            String value) {
        return Optional.empty();
    }

    /**
     * Atomically verifies that {@code snapshot} still names the current committed
     * image and derives range candidates from that same image. Bounds use the
     * same typed ordered-key envelope as equality lookup; a null bound is open.
     */
    default Optional<List<Long>> orderedIndexRowIdsInRangeFor(
            DelosStorageSnapshot snapshot,
            int column,
            String lowerValue,
            boolean lowerInclusive,
            String upperValue,
            boolean upperInclusive) {
        return Optional.empty();
    }

    /**
     * Records that the ordered page-backed lookup intentionally declined to
     * answer and the caller safely used the full scan path instead.
     */
    default void recordOrderedIndexFallbackForTesting(DelosStorageOrderedIndexFallbackReason reason) {
    }

    int candidateIndexKeyCountForTesting();
}
