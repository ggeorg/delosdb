/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason

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

/** Root-cause classification for safe ordered-index lookup fallback. */
public enum DelosStorageOrderedIndexFallbackReason {
    /** The qualifier or store value cannot be represented as a safe ordered-index key. */
    UNSUPPORTED_KEY_OR_TYPE,

    /** The ordered-index sidecar exists but cannot be decoded or validated. */
    MALFORMED_ORDERED_INDEX_SIDECAR,

    /** The ordered-index sidecar is absent, stale, or unavailable for a populated table. */
    STALE_OR_MISSING_ORDERED_INDEX_SIDECAR,

    /** The read intentionally avoids the current-committed shortcut, for example for a stable snapshot or local writer. */
    INTENTIONAL_NON_SHORTCUT_READ,

    /** The scan uses the full committed-image path after the ordered shortcut declines to answer. */
    FULL_COMMITTED_SCAN_FALLBACK
}
