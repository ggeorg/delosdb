/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageAccessDecisionKind

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

/**
 * Stable vocabulary for DelosDB storage access-path decisions.
 *
 * <p>This enum is deliberately diagnostic vocabulary only.  It does not change
 * Derby optimizer authority, heap scan behavior, MVCC visibility, or ordered
 * index selection.  Future diagnostics can record one of these values when a
 * heap/MVCC path is chosen, rejected, or kept as an explicit fallback.</p>
 */
public enum DelosStorageAccessDecisionKind {
    /** Derby-compatible heap table scan chosen through the inherited heap access path. */
    FULL_HEAP_SCAN,

    /** Derby-compatible heap B-tree/index scan chosen through the inherited access path. */
    HEAP_INDEX_SCAN,

    /** MVCC full scan where row/version visibility remains the authority. */
    MVCC_FULL_SCAN,

    /** Current-committed MVCC equality lookup through ordered MVCC index pages. */
    MVCC_ORDERED_EQUALITY_LOOKUP,

    /** Current-committed MVCC range lookup through ordered MVCC index pages. */
    MVCC_ORDERED_RANGE_SCAN,

    /** MVCC row-id point read or row-id narrowed read. */
    MVCC_ROW_ID_LOOKUP,

    /** MVCC index-narrowed read that must still apply row/version visibility filtering. */
    MVCC_VISIBILITY_FILTERED_INDEX_LOOKUP,

    /** Diagnostic candidate-index comparison or parity scan, not normal SQL authority. */
    DIAGNOSTIC_CANDIDATE_PARITY_SCAN,

    /** Deliberate compatibility or safety fallback from a shortcut to an authority path. */
    EXPLICIT_COMPATIBILITY_FALLBACK,

    /** Path exists only for tests, gates, or diagnostic assertions. */
    TEST_ONLY_PATH
}
