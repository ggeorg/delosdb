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

    private static final Object LOCK = new Object();
    private static final List<DelosOptimizerPredicatePushdownDecision> DECISIONS = new ArrayList<>();

    private DelosOptimizerPredicatePushdownDiagnostics() {
    }

    public static boolean enabledForTesting() {
        String value = System.getProperty(PROPERTY_NAME, "");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("enabled")
                || normalized.equals("true")
                || normalized.equals("on")
                || normalized.equals("1");
    }

    public static DelosOptimizerPredicatePushdownDecision considerForTesting(
            DelosStoragePredicatePushdownRequest request) {
        Objects.requireNonNull(request, "request");
        DelosStoragePredicatePushdown plan = DelosStorageDiagnosticsRegistry.predicatePushdown(request);
        DelosOptimizerPredicatePushdownDecision decision =
                DelosOptimizerPredicatePushdownDecision.from(enabledForTesting(), plan);
        synchronized (LOCK) {
            DECISIONS.add(decision);
        }
        return decision;
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
