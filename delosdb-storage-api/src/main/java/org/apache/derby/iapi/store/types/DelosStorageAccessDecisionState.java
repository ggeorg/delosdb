/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageAccessDecisionState

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
 * Diagnostic state for a DelosDB storage access-path decision.
 *
 * <p>This enum is an inert reporting vocabulary.  It must not be used to alter
 * Derby optimizer authority, heap compatibility behavior, MVCC visibility, or
 * candidate-index quarantine.  Runtime producers may later record one of these
 * states after an already-existing path has been chosen, rejected, or kept as a
 * diagnostic-only path.</p>
 */
public enum DelosStorageAccessDecisionState {
    /** The existing storage path was chosen by the normal authority for the operation. */
    CHOSEN,

    /** A shortcut or optional path was rejected before execution. */
    REJECTED,

    /** A shortcut declined and execution deliberately used a compatibility/authority path. */
    FALLBACK,

    /** The path exists only to produce a diagnostic or parity result. */
    DIAGNOSTIC_ONLY,

    /** The path exists only for tests, static gates, or proof harnesses. */
    TEST_ONLY
}
