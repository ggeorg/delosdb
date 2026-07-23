/*

   Derby - Class org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics

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
import java.util.Locale;
import java.util.Objects;

/**
 * Test-facing diagnostics for the opt-in predicate pushdown planning gate.
 *
 * <p>The property makes the optimizer-side planning checkpoint consider the
 * storage pushdown/remainder model.  It still does not apply execution
 * pushdown, alter Derby remainder evaluation, or change SQL results.</p>
 */
public final class DelosOptimizerPredicatePushdownDiagnostics {
    public static final String PROPERTY_NAME = "delosdb.optimizer.predicatePushdown";
    public static final String CONSUMPTION_PROPERTY_NAME = "delosdb.optimizer.predicatePushdown.consume";

    private static final Object LOCK = new Object();
    private static final List<DelosOptimizerPredicatePushdownDecision> DECISIONS = new ArrayList<>();

    private DelosOptimizerPredicatePushdownDiagnostics() {
    }

    /**
     * Returns whether optimizer-side predicate pushdown planning is explicitly
     * enabled.  This is the production spelling; the historical ForTesting
     * method below delegates here for compatibility with earlier gates.
     */
    public static boolean planningEnabled() {
        return propertyEnabled(PROPERTY_NAME);
    }

    public static boolean enabledForTesting() {
        return planningEnabled();
    }

    /**
     * Returns whether the second, stricter metadata-consumption property is
     * explicitly enabled.  This still never removes Derby's remainder
     * predicate evaluation.
     */
    public static boolean consumptionEnabled() {
        return propertyEnabled(CONSUMPTION_PROPERTY_NAME);
    }

    public static boolean consumptionEnabledForTesting() {
        return consumptionEnabled();
    }

    /**
     * True when the production optimizer hook should spend work recording a
     * decision.  Planning opt-in is the root gate; the consumption property is
     * deliberately a second-stage gate and is ignored by production hooks when
     * planning itself is disabled.  Default Derby mode remains side-effect free.
     */
    public static boolean optimizerHookEnabled() {
        return planningEnabled();
    }

    /**
     * True when the production optimizer hook may mark storage-predicate
     * metadata as optimizer-consumed.  Both explicit opt-ins are required.
     */
    public static boolean optimizerConsumptionHookEnabled() {
        return planningEnabled() && consumptionEnabled();
    }

    private static boolean propertyEnabled(String propertyName) {
        String value = System.getProperty(propertyName, "");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("enabled")
                || normalized.equals("true")
                || normalized.equals("on")
                || normalized.equals("1");
    }

    public static DelosOptimizerPredicatePushdownDecision consider(
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(request, "request");
        DelosStoragePredicatePushdown plan = DelosStorageDiagnosticsRegistry.predicatePushdown(request);
        DelosOptimizerPredicatePushdownDecision decision =
                DelosOptimizerPredicatePushdownDecision.from(planningEnabled(), plan);
        record(decision);
        return decision;
    }

    public static DelosOptimizerPredicatePushdownDecision considerForTesting(
            DelosStoragePredicatePushdownRequest request) {
        return consider(request);
    }

    /**
     * Production optimizer hook for predicate-consumption metadata.  The
     * resulting decision may mark the storage-side candidate metadata as
     * consumed only when both explicit properties are enabled and the storage
     * plan itself is pushable.  It does not mutate Derby predicate lists and it
     * does not remove RowUtil/remainder evaluation.
     */
    public static DelosOptimizerPredicatePushdownDecision consumeFromOptimizer(
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(request, "request");
        DelosStoragePredicatePushdown plan = DelosStorageDiagnosticsRegistry.predicatePushdown(request);
        DelosOptimizerPredicatePushdownDecision decision =
                DelosOptimizerPredicatePushdownDecision.optimizerConsumed(
                        planningEnabled(),
                        consumptionEnabled(),
                        plan);
        record(decision);
        return decision;
    }


    /**
     * Production optimizer hook for metadata already selected by Derby's
     * optimizer path. This records the explicit consumption checkpoint without
     * requiring a registered runtime storage snapshot at compile time. It still
     * never mutates Derby predicate lists and never claims execution pushdown.
     */
    public static DelosOptimizerPredicatePushdownDecision consumeOptimizerMetadataFromProductionHook(
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(request, "request");
        DelosOptimizerPredicatePushdownDecision decision =
                DelosOptimizerPredicatePushdownDecision.optimizerConsumedFromRequest(
                        planningEnabled(),
                        consumptionEnabled(),
                        request);
        record(decision);
        return decision;
    }

    public static DelosOptimizerPredicatePushdownDecision consumeForTesting(
            DelosStoragePredicatePushdownRequest request) {
        return consumeFromOptimizer(request);
    }

    public static boolean recordExecutionIfEnabledForTesting(
            String providerId,
            int segment,
            long containerId,
            long rowIdCount) {
        if (!enabledForTesting()) {
            return false;
        }
        DelosOptimizerPredicatePushdownDecision decision =
                DelosOptimizerPredicatePushdownDecision.executionApplied(
                        providerId,
                        segment,
                        containerId,
                        rowIdCount);
        record(decision);
        return true;
    }

    private static void record(DelosOptimizerPredicatePushdownDecision decision) {
        synchronized (LOCK) {
            DECISIONS.add(decision);
        }
    }

    public static void clearForTesting() {
        synchronized (LOCK) {
            DECISIONS.clear();
        }
    }

    public static int decisionCountForTesting() {
        synchronized (LOCK) {
            return DECISIONS.size();
        }
    }

    public static int planningConsideredCountForTesting() {
        int count = 0;
        synchronized (LOCK) {
            for (DelosOptimizerPredicatePushdownDecision decision : DECISIONS) {
                if (decision.optimizerPlanningConsidered()) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int executionPushdownAppliedCountForTesting() {
        int count = 0;
        synchronized (LOCK) {
            for (DelosOptimizerPredicatePushdownDecision decision : DECISIONS) {
                if (decision.executionPushdownApplied()) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int optimizerConsumedCountForTesting() {
        int count = 0;
        synchronized (LOCK) {
            for (DelosOptimizerPredicatePushdownDecision decision : DECISIONS) {
                if (decision.consumedByDerbyOptimizer()) {
                    count++;
                }
            }
        }
        return count;
    }

    public static int storagePushableDecisionCountForTesting() {
        int count = 0;
        synchronized (LOCK) {
            for (DelosOptimizerPredicatePushdownDecision decision : DECISIONS) {
                if (decision.storagePlanPushable()) {
                    count++;
                }
            }
        }
        return count;
    }

    public static DelosOptimizerPredicatePushdownDecision lastDecisionForTesting() {
        synchronized (LOCK) {
            return DECISIONS.isEmpty() ? null : DECISIONS.get(DECISIONS.size() - 1);
        }
    }

    public static List<String> diagnosticLinesForTesting() {
        synchronized (LOCK) {
            return DECISIONS.stream()
                    .map(DelosOptimizerPredicatePushdownDecision::diagnosticLine)
                    .toList();
        }
    }
}
