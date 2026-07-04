/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics

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

import java.util.Objects;

/**
 * Immutable diagnostic snapshot for MVCC ordered-index authority.
 *
 * <p>The ordered index is no longer only a shadow page skeleton. In
 * {@code delos_mvcc} it is the preferred row-id narrowing authority for
 * covered current-committed equality/range reads. Candidate indexes remain
 * populated for parity diagnostics and safe fallback only.</p>
 */
public record DelosStorageOrderedIndexDiagnostics(
        AuthorityMode authorityMode,
        long pageCount,
        long entryCount,
        int distinctKeyCount,
        long rebuildCount,
        long lookupCount,
        long hitCount,
        long fallbackCount,
        long rowIdCount,
        int candidateParityErrorCount) {
    public DelosStorageOrderedIndexDiagnostics {
        Objects.requireNonNull(authorityMode, "authorityMode");
        if (pageCount < 0L || entryCount < 0L || distinctKeyCount < 0
                || rebuildCount < 0L || lookupCount < 0L || hitCount < 0L
                || fallbackCount < 0L || rowIdCount < 0L
                || candidateParityErrorCount < 0) {
            throw new IllegalArgumentException("ordered index diagnostics counts must not be negative");
        }
        if (hitCount > lookupCount) {
            throw new IllegalArgumentException("ordered index hits cannot exceed lookups");
        }
    }

    /**
     * Compatibility constructor for older diagnostics callers that only exposed
     * the page skeleton counts.
     */
    public DelosStorageOrderedIndexDiagnostics(
            long pageCount,
            long entryCount,
            int distinctKeyCount,
            long rebuildCount) {
        this(AuthorityMode.UNAVAILABLE,
                pageCount,
                entryCount,
                distinctKeyCount,
                rebuildCount,
                0L,
                0L,
                0L,
                0L,
                0);
    }

    public static DelosStorageOrderedIndexDiagnostics unavailable() {
        return new DelosStorageOrderedIndexDiagnostics(
                AuthorityMode.UNAVAILABLE,
                0L,
                0L,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0);
    }

    public boolean currentCommittedAuthorityEnabled() {
        return authorityMode == AuthorityMode.CURRENT_COMMITTED_ROW_ID_AUTHORITY;
    }

    public enum AuthorityMode {
        /** Ordered-index authority is unavailable for this storage provider/table. */
        UNAVAILABLE,

        /**
         * Covered current-committed reads may use ordered-index row-id narrowing,
         * with full-scan fallback for unsupported, stale, or malformed sidecars.
         */
        CURRENT_COMMITTED_ROW_ID_AUTHORITY
    }
}
