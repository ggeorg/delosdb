/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.OptimizerPredicatePushdownProductionHookTest

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
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics;

/** SQL gate for the production optimizer predicate-consumption hook. */
public final class OptimizerPredicatePushdownProductionHookTest extends MvccSqlTestSupport {
    public void testProductionOptimizerPredicateConsumptionHookIsOptInAndRemainderPreserving() throws Exception {
        String oldPlanningMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
        String oldConsumptionMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);

        try {
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            runHookQuery(databaseName("optimizer-predicate-pushdown-production-hook-default-db"));
            assertEquals("default Derby mode must not record production optimizer predicate decisions",
                    0,
                    DelosOptimizerPredicatePushdownDiagnostics.decisionCountForTesting());

            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME, "enabled");
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            runHookQuery(databaseName("optimizer-predicate-pushdown-production-hook-consumption-only-db"));
            assertEquals("consumption property alone must not activate the production optimizer hook",
                    0,
                    DelosOptimizerPredicatePushdownDiagnostics.decisionCountForTesting());

            System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, "enabled");
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            runHookQuery(databaseName("optimizer-predicate-pushdown-production-hook-planning-only-db"));
            assertTrue("planning opt-in should record production optimizer predicate metadata",
                    DelosOptimizerPredicatePushdownDiagnostics.planningConsideredCountForTesting() > 0);
            assertEquals("planning opt-in alone must not mark optimizer consumption",
                    0,
                    DelosOptimizerPredicatePushdownDiagnostics.optimizerConsumedCountForTesting());
            assertTrue("planning-only diagnostics should use the planning path",
                    DelosOptimizerPredicatePushdownDiagnostics.diagnosticLinesForTesting().stream()
                            .anyMatch(line -> line.contains("path=optimizer-predicate-pushdown ")));

            System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, "enabled");
            System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME, "enabled");
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            runHookQuery(databaseName("optimizer-predicate-pushdown-production-hook-consumption-db"));
            assertTrue("second opt-in should mark production optimizer predicate metadata consumed",
                    DelosOptimizerPredicatePushdownDiagnostics.optimizerConsumedCountForTesting() > 0);
            List<String> diagnosticLines = DelosOptimizerPredicatePushdownDiagnostics.diagnosticLinesForTesting();
            assertTrue("production hook diagnostics should use the consumption path",
                    diagnosticLines.stream().anyMatch(line -> line.contains(
                            "path=optimizer-predicate-pushdown-consumption")));
            assertTrue("production hook must document that Derby remainder evaluation is preserved",
                    diagnosticLines.stream().anyMatch(line -> line.contains(
                            "derbyRemainderEvaluation=preserved")));
            assertTrue("production hook must not claim execution pushdown from consumption metadata alone",
                    diagnosticLines.stream()
                            .filter(line -> line.contains("path=optimizer-predicate-pushdown-consumption"))
                            .allMatch(line -> line.contains("executionPushdownApplied=false")));
        } finally {
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            if (oldPlanningMode == null) {
                System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            } else {
                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, oldPlanningMode);
            }
            if (oldConsumptionMode == null) {
                System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);
            } else {
                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME, oldConsumptionMode);
            }
        }
    }

    private static void runHookQuery(String databaseName) throws Exception {
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table opt_hook_mvcc_t "
                    + "(id int primary key, name varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into opt_hook_mvcc_t values (1, 'mvcc-alpha', 'one')");
            executeUpdate(connection, "insert into opt_hook_mvcc_t values (2, 'mvcc-beta', 'two')");
            executeUpdate(connection, "insert into opt_hook_mvcc_t values (3, 'other-gamma', 'three')");
            connection.commit();
            assertRows(connection,
                    "select id, name from opt_hook_mvcc_t "
                            + "where name = 'mvcc-beta' and payload like 't%' and id > 0",
                    "2|mvcc-beta");
            connection.commit();
        }
    }

}
