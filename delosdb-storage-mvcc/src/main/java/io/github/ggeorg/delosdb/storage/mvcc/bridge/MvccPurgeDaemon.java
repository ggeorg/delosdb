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
 * <p>The default mode remains deterministic and commit-boundary driven.  An
 * explicit async property can move the actual purge work to a short-lived daemon
 * thread, but scheduling still happens at safe commit boundaries and still
 * rechecks retained readers/snapshots before vacuuming.</p>
 */
final class MvccPurgeDaemon {
    static final String ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.enabled";
    static final String CHANGED_ROWS_THRESHOLD_PROPERTY = "delosdb.mvcc.purgeDaemon.changedRowsThreshold";
    static final String ASYNC_ENABLED_PROPERTY = "delosdb.mvcc.purgeDaemon.async.enabled";
    static final int DEFAULT_CHANGED_ROWS_THRESHOLD = 8;

    private long scheduleCount;
    private long asyncScheduleCount;
    private long runCount;
    private long skipCount;
    private long lastTriggerChangedRows;
    private long lastVisibilityDebtScore;
    private String lastVisibilityDebtSummary = "none";
    private String lastDecision = "disabled";

    Optional<DelosVacuumOutcome> maybeRunAfterCommit(
            int changedRows,
            Supplier<MvccVisibilityDebtPolicy.Snapshot> debtSupplier,
            BooleanSupplier retainedReaderSupplier,
            Supplier<DelosVacuumOutcome> vacuumSupplier) {
        Objects.requireNonNull(debtSupplier, "debtSupplier");
        Objects.requireNonNull(retainedReaderSupplier, "retainedReaderSupplier");
        Objects.requireNonNull(vacuumSupplier, "vacuumSupplier");
        MvccVisibilityDebtPolicy.Snapshot debt = debtSupplier.get();
        if (!eligibleAfterCommit(changedRows, debt)) {
            return Optional.empty();
        }
        scheduleCount++;
        if (retainedReaderSupplier.getAsBoolean()) {
            skip("retained inherited MVCC transaction or scan");
            return Optional.empty();
        }
        DelosVacuumOutcome outcome = vacuumSupplier.get();
        runCount++;
        lastDecision = "ran: " + outcome.reason() + "; debt " + lastVisibilityDebtSummary;
        return Optional.of(outcome);
    }

    boolean asynchronousEnabled() {
        return enabled() && asyncEnabled();
    }

    boolean eligibleAfterCommit(int changedRows, MvccVisibilityDebtPolicy.Snapshot debt) {
        Objects.requireNonNull(debt, "debt");
        lastTriggerChangedRows = Math.max(0, changedRows);
        recordVisibilityDebt(debt);
        if (!enabled()) {
            skip("disabled");
            return false;
        }
        if (!eligibleChangedRows(changedRows)) {
            return false;
        }
        return eligibleVisibilityDebt(debt);
    }

    boolean eligibleChangedRows(int changedRows) {
        if (changedRows <= 0) {
            skip("no committed row changes");
            return false;
        }
        int threshold = changedRowsThreshold();
        if (changedRows < threshold) {
            skip("changed rows below threshold " + threshold);
            return false;
        }
        return true;
    }

    boolean eligibleVisibilityDebt(MvccVisibilityDebtPolicy.Snapshot debt) {
        Objects.requireNonNull(debt, "debt");
        recordVisibilityDebt(debt);
        if (!MvccVisibilityDebtPolicy.eligible(debt)) {
            skip("visibility debt below threshold "
                    + MvccVisibilityDebtPolicy.threshold()
                    + ": " + lastVisibilityDebtSummary);
            return false;
        }
        return true;
    }

    void recordAsyncScheduled(int changedRows, MvccVisibilityDebtPolicy.Snapshot debt) {
        lastTriggerChangedRows = Math.max(0, changedRows);
        recordVisibilityDebt(debt);
        scheduleCount++;
        asyncScheduleCount++;
        lastDecision = "scheduled async; debt " + lastVisibilityDebtSummary;
    }

    void recordAsyncRun(DelosVacuumOutcome outcome) {
        runCount++;
        lastDecision = "async ran: " + Objects.requireNonNull(outcome, "outcome").reason()
                + "; debt " + lastVisibilityDebtSummary;
    }

    void recordAsyncSkip(String reason) {
        skip(reason);
    }

    long scheduleCount() {
        return scheduleCount;
    }

    long asyncScheduleCount() {
        return asyncScheduleCount;
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

    long lastVisibilityDebtScore() {
        return lastVisibilityDebtScore;
    }

    String lastVisibilityDebtSummary() {
        return lastVisibilityDebtSummary;
    }

    String lastDecision() {
        return lastDecision;
    }

    private void recordVisibilityDebt(MvccVisibilityDebtPolicy.Snapshot debt) {
        Objects.requireNonNull(debt, "debt");
        lastVisibilityDebtScore = debt.score();
        lastVisibilityDebtSummary = debt.summary();
    }

    private void skip(String reason) {
        skipCount++;
        lastDecision = reason;
    }

    private static boolean enabled() {
        String value = System.getProperty(ENABLED_PROPERTY);
        return "true".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value);
    }

    private static boolean asyncEnabled() {
        String value = System.getProperty(ASYNC_ENABLED_PROPERTY);
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
