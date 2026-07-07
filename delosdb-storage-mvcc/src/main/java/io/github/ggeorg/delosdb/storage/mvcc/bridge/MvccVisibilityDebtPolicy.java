/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccVisibilityDebtPolicy

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
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.Objects;

/**
 * Visibility-debt policy used by the cooperative MVCC purge scheduler.
 *
 * <p>The policy keeps the daemon tied to observable MVCC cleanup pressure
 * instead of committed-row count alone.  Changed-row count remains a cheap
 * prefilter, but actual purge scheduling requires old/prunable/tombstone/pending
 * cleanup debt unless the policy is explicitly disabled for diagnostics.</p>
 */
final class MvccVisibilityDebtPolicy {
    static final String ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.visibilityDebt.enabled";
    static final String THRESHOLD_PROPERTY = "delosdb.mvcc.purgeDaemon.visibilityDebtThreshold";
    static final long DEFAULT_THRESHOLD = 1L;

    private MvccVisibilityDebtPolicy() {
    }

    static boolean enabled() {
        String value = System.getProperty(ENABLED_PROPERTY);
        return value == null
                || value.isBlank()
                || "true".equalsIgnoreCase(value)
                || "enabled".equalsIgnoreCase(value);
    }

    static long threshold() {
        String value = System.getProperty(THRESHOLD_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_THRESHOLD;
        }
    }

    static boolean eligible(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return !enabled() || snapshot.score() >= threshold();
    }

    record Snapshot(long prunablePages,
                    long oldVersionPages,
                    long tombstonePages,
                    long pendingPurgeEntries,
                    long obsoleteVersions) {
        Snapshot {
            prunablePages = Math.max(0L, prunablePages);
            oldVersionPages = Math.max(0L, oldVersionPages);
            tombstonePages = Math.max(0L, tombstonePages);
            pendingPurgeEntries = Math.max(0L, pendingPurgeEntries);
            obsoleteVersions = Math.max(0L, obsoleteVersions);
        }

        long score() {
            return prunablePages + pendingPurgeEntries + tombstonePages + obsoleteVersions;
        }

        String summary() {
            return "score=" + score()
                    + ",prunablePages=" + prunablePages
                    + ",oldVersionPages=" + oldVersionPages
                    + ",tombstonePages=" + tombstonePages
                    + ",pendingPurgeEntries=" + pendingPurgeEntries
                    + ",obsoleteVersions=" + obsoleteVersions;
        }
    }
}
