/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccPurgeDaemon

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
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.apache.derby.iapi.store.types.DelosVacuumOutcome;

/**
 * Deterministic cooperative purge-daemon scheduler for inherited MVCC tables.
 *
 * <p>This is intentionally not a free-running background thread.  The scheduler
 * is triggered at safe commit boundaries, checks for retained readers/snapshots,
 * and then invokes the existing provider-owned vacuum path.  That gives DelosDB
 * a daemon-style automatic purge boundary without making tests race a timer.</p>
 */
final class MvccPurgeDaemon {
    static final String ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.enabled";
    static final String CHANGED_ROWS_THRESHOLD_PROPERTY = "delosdb.mvcc.purgeDaemon.changedRowsThreshold";
    static final int DEFAULT_CHANGED_ROWS_THRESHOLD = 8;

    private long scheduleCount;
    private long runCount;
    private long skipCount;
    private long lastTriggerChangedRows;
    private String lastDecision = "disabled";

    Optional<DelosVacuumOutcome> maybeRunAfterCommit(
            int changedRows,
            BooleanSupplier retainedReaderSupplier,
            Supplier<DelosVacuumOutcome> vacuumSupplier) {
        Objects.requireNonNull(retainedReaderSupplier, "retainedReaderSupplier");
        Objects.requireNonNull(vacuumSupplier, "vacuumSupplier");
        lastTriggerChangedRows = Math.max(0, changedRows);
        if (!enabled()) {
            skip("disabled");
            return Optional.empty();
        }
        if (changedRows <= 0) {
            skip("no committed row changes");
            return Optional.empty();
        }
        int threshold = changedRowsThreshold();
        if (changedRows < threshold) {
            skip("changed rows below threshold " + threshold);
            return Optional.empty();
        }
        scheduleCount++;
        if (retainedReaderSupplier.getAsBoolean()) {
            skip("retained inherited MVCC transaction or scan");
            return Optional.empty();
        }
        DelosVacuumOutcome outcome = vacuumSupplier.get();
        runCount++;
        lastDecision = "ran: " + outcome.reason();
        return Optional.of(outcome);
    }

    long scheduleCount() {
        return scheduleCount;
    }

    long runCount() {
        return runCount;
    }

    long skipCount() {
        return skipCount;
    }

    long lastTriggerChangedRows() {
        return lastTriggerChangedRows;
    }

    String lastDecision() {
        return lastDecision;
    }

    private void skip(String reason) {
        skipCount++;
        lastDecision = reason;
    }

    private static boolean enabled() {
        String value = System.getProperty(ENABLED_PROPERTY);
        return "true".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value);
    }

    private static int changedRowsThreshold() {
        String value = System.getProperty(CHANGED_ROWS_THRESHOLD_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_CHANGED_ROWS_THRESHOLD;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_CHANGED_ROWS_THRESHOLD;
        }
    }
}
