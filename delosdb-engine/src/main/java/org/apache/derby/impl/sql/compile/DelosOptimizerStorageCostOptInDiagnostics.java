/*

   Derby - Class org.apache.derby.impl.sql.compile.DelosOptimizerStorageCostOptInDiagnostics

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
package org.apache.derby.impl.sql.compile;

import io.github.ggeorg.delosdb.engine.extension.cost.CostModelDiagnostics;
import io.github.ggeorg.delosdb.engine.extension.cost.CostModelMode;
import io.github.ggeorg.delosdb.engine.extension.cost.CostModelProbe;

import java.util.List;

/**
 * Test-facing diagnostics for the opt-in Derby optimizer storage-cost path.
 *
 * <p>The production path remains the existing StoreCostController wrapper. This
 * helper only exposes stable counters to DelosDB tests through a package already
 * exported to the Derby test module.</p>
 */
public final class DelosOptimizerStorageCostOptInDiagnostics {
    public static final String PROPERTY_NAME = CostModelMode.PROPERTY_NAME;

    private DelosOptimizerStorageCostOptInDiagnostics() {
    }

    public static void clearForTesting() {
        CostModelDiagnostics.clear();
    }

    public static boolean optimizerCostProviderEnabledForTesting() {
        return CostModelMode.fromSystemProperties().consumesProviderCost();
    }

    public static int probeCountForTesting() {
        return CostModelDiagnostics.probes().size();
    }

    public static int consumedProbeCountForTesting() {
        int consumed = 0;
        for (CostModelProbe probe : CostModelDiagnostics.probes()) {
            if (probe.consumed()) {
                consumed++;
            }
        }
        return consumed;
    }

    public static int safeProviderEstimateCountForTesting() {
        int safe = 0;
        for (CostModelProbe probe : CostModelDiagnostics.probes()) {
            if (probe.canSafelyReplaceDerbyCost()) {
                safe++;
            }
        }
        return safe;
    }

    public static boolean lastProbeConsumedForTesting() {
        CostModelProbe probe = CostModelDiagnostics.lastProbe();
        return probe != null && probe.consumed();
    }

    public static String lastDiagnosticLineForTesting() {
        CostModelProbe probe = CostModelDiagnostics.lastProbe();
        return probe == null ? "" : probe.diagnosticLine();
    }

    public static List<String> diagnosticLinesForTesting() {
        return CostModelDiagnostics.probes().stream()
                .map(CostModelProbe::diagnosticLine)
                .toList();
    }
}
