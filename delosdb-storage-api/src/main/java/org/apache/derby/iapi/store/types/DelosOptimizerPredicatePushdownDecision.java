/*

   Derby - Class org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDecision

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
 * Opt-in optimizer planning decision for the DelosDB predicate
 * pushdown/remainder model.
 *
 * <p>This is deliberately a planning diagnostic.  It proves that Derby's
 * optimizer side can explicitly consider the storage pushdown model, but it
 * does not change execution, consume predicates, or remove Derby remainder
 * evaluation.</p>
 */
public record DelosOptimizerPredicatePushdownDecision(String providerId,
                                                      int segment,
                                                      long containerId,
                                                      boolean optInEnabled,
                                                      boolean optimizerPlanningConsidered,
                                                      boolean storagePlanPushable,
                                                      boolean executionPushdownApplied,
                                                      boolean consumedByDerbyOptimizer,
                                                      List<String> pushedPredicates,
                                                      List<String> remainderPredicates,
                                                      String diagnosticLine) {
    public DelosOptimizerPredicatePushdownDecision {
        providerId = DelosStorageProviderIds.normalize(providerId);
        pushedPredicates = List.copyOf(Objects.requireNonNull(pushedPredicates, "pushedPredicates"));
        remainderPredicates = List.copyOf(Objects.requireNonNull(remainderPredicates, "remainderPredicates"));
        diagnosticLine = Objects.requireNonNull(diagnosticLine, "diagnosticLine").trim();
        if (diagnosticLine.isEmpty()) {
            throw new IllegalArgumentException("diagnostic line must not be blank");
        }
        if (executionPushdownApplied) {
            throw new IllegalArgumentException("execution predicate pushdown is not enabled by this gate");
        }
        if (consumedByDerbyOptimizer) {
            throw new IllegalArgumentException("optimizer predicate consumption is not enabled by this gate");
        }
        if (optimizerPlanningConsidered && !optInEnabled) {
            throw new IllegalArgumentException("optimizer planning cannot be considered when opt-in is disabled");
        }
    }

    public static DelosOptimizerPredicatePushdownDecision from(
            boolean optInEnabled,
            DelosStoragePredicatePushdown plan) {
        Objects.requireNonNull(plan, "plan");
        boolean planningConsidered = optInEnabled;
        String line = "path=optimizer-predicate-pushdown"
                + " provider=" + plan.providerId()
                + " container=" + plan.containerId()
                + " optIn=" + optInEnabled
                + " planningConsidered=" + planningConsidered
                + " storagePushable=" + plan.pushedToStorage()
                + " executionPushdownApplied=false"
                + " optimizerConsumed=false";
        return new DelosOptimizerPredicatePushdownDecision(
                plan.providerId(),
                plan.segment(),
                plan.containerId(),
                optInEnabled,
                planningConsidered,
                plan.pushedToStorage(),
                false,
                false,
                plan.pushedPredicates(),
                plan.remainderPredicates(),
                line);
    }
}
