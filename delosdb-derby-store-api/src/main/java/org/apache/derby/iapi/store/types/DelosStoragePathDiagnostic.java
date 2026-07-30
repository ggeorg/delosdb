/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic

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
import java.util.Objects;

/**
 * Inert diagnostic record for a DelosDB storage access path.
 *
 * <p>The record is deliberately descriptive: it captures why an already-existing
 * path was chosen, rejected, or used as an explicit fallback.  Constructing or
 * recording this value must not change Derby optimizer authority, heap scan
 * behavior, MVCC visibility, ordered-index selection, or candidate-index
 * quarantine.</p>
 */
public record DelosStoragePathDiagnostic(DelosStorageAccessDecisionKind decisionKind,
                                         DelosStorageAccessDecisionState state,
                                         String providerId,
                                         int segment,
                                         long containerId,
                                         String reason,
                                         String readMode,
                                         boolean shortcutSafe,
                                         long rowIdCount,
                                         List<String> details) {
    /** Unknown row-id count for paths that do not produce a row-id candidate list. */
    public static final long UNKNOWN_ROW_ID_COUNT = -1L;

    public DelosStoragePathDiagnostic {
        decisionKind = Objects.requireNonNull(decisionKind, "decisionKind");
        state = Objects.requireNonNull(state, "state");
        providerId = DelosStorageProviderIds.normalize(providerId);
        reason = DelosStorageText.requireNonBlank(reason, "reason");
        readMode = DelosStorageText.requireNonBlank(readMode, "readMode");
        details = List.copyOf(Objects.requireNonNull(details, "details"));
        if (rowIdCount < UNKNOWN_ROW_ID_COUNT) {
            throw new IllegalArgumentException("rowIdCount must be non-negative or UNKNOWN_ROW_ID_COUNT");
        }
        if (state == DelosStorageAccessDecisionState.CHOSEN
                && decisionKind == DelosStorageAccessDecisionKind.EXPLICIT_COMPATIBILITY_FALLBACK) {
            throw new IllegalArgumentException("explicit compatibility fallback must be recorded as FALLBACK");
        }
        if (state == DelosStorageAccessDecisionState.DIAGNOSTIC_ONLY
                && decisionKind != DelosStorageAccessDecisionKind.DIAGNOSTIC_CANDIDATE_PARITY_SCAN) {
            throw new IllegalArgumentException("diagnostic-only state is reserved for diagnostic candidate parity scans");
        }
        if (state == DelosStorageAccessDecisionState.TEST_ONLY
                && decisionKind != DelosStorageAccessDecisionKind.TEST_ONLY_PATH) {
            throw new IllegalArgumentException("test-only state requires TEST_ONLY_PATH");
        }
        if (shortcutSafe
                && (state == DelosStorageAccessDecisionState.REJECTED
                || state == DelosStorageAccessDecisionState.FALLBACK)) {
            throw new IllegalArgumentException("rejected/fallback shortcut diagnostics cannot claim shortcutSafe=true");
        }
    }

    public static DelosStoragePathDiagnostic chosen(DelosStorageAccessDecisionKind decisionKind,
                                                    String providerId,
                                                    int segment,
                                                    long containerId,
                                                    String reason,
                                                    String readMode,
                                                    boolean shortcutSafe,
                                                    long rowIdCount,
                                                    List<String> details) {
        return new DelosStoragePathDiagnostic(decisionKind,
                DelosStorageAccessDecisionState.CHOSEN,
                providerId,
                segment,
                containerId,
                reason,
                readMode,
                shortcutSafe,
                rowIdCount,
                details);
    }

    public static DelosStoragePathDiagnostic rejected(DelosStorageAccessDecisionKind decisionKind,
                                                      String providerId,
                                                      int segment,
                                                      long containerId,
                                                      String reason,
                                                      String readMode,
                                                      List<String> details) {
        return new DelosStoragePathDiagnostic(decisionKind,
                DelosStorageAccessDecisionState.REJECTED,
                providerId,
                segment,
                containerId,
                reason,
                readMode,
                false,
                UNKNOWN_ROW_ID_COUNT,
                details);
    }

    public static DelosStoragePathDiagnostic fallback(String providerId,
                                                      int segment,
                                                      long containerId,
                                                      String reason,
                                                      String readMode,
                                                      List<String> details) {
        return new DelosStoragePathDiagnostic(DelosStorageAccessDecisionKind.EXPLICIT_COMPATIBILITY_FALLBACK,
                DelosStorageAccessDecisionState.FALLBACK,
                providerId,
                segment,
                containerId,
                reason,
                readMode,
                false,
                UNKNOWN_ROW_ID_COUNT,
                details);
    }

    public String diagnosticLine() {
        return "storagePath=" + decisionKind
                + " state=" + state
                + " provider=" + providerId
                + " segment=" + segment
                + " container=" + containerId
                + " readMode=" + readMode
                + " shortcutSafe=" + shortcutSafe
                + " rowIds=" + rowIdCount
                + " reason=" + reason;
    }

}
