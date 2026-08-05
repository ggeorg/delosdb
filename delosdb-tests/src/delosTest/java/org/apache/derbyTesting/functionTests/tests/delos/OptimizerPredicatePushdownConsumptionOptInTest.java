/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.OptimizerPredicatePushdownConsumptionOptInTest

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

import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDecision;
import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownRequest;

/** SQL gate for explicitly opt-in optimizer predicate-pushdown consumption metadata. */
public final class OptimizerPredicatePushdownConsumptionOptInTest extends MvccSqlTestSupport {
    public void testOptimizerPredicatePushdownConsumptionRequiresSecondOptIn() throws Exception {
        String databaseName = databaseName("optimizer-predicate-pushdown-consumption-db");
        String oldPlanningMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
        String oldConsumptionMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);

        try {
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();

            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table opt_consume_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into opt_consume_mvcc_t values (1, 'mvcc-alpha', 'one')");
                executeUpdate(connection, "insert into opt_consume_mvcc_t values (2, 'mvcc-beta', 'two')");
                executeUpdate(connection, "insert into opt_consume_mvcc_t values (3, 'other-gamma', 'three')");
                connection.commit();

                long mvccContainerId = mvccContainerId(connection, "OPT_CONSUME_MVCC_T");
                DelosStoragePredicatePushdownRequest request = new DelosStoragePredicatePushdownRequest(
                        "delos_mvcc",
                        databasePath(databaseName),
                        0,
                        mvccContainerId,
                        "name = 'mvcc-beta' and payload like 'two%'",
                        true,
                        false,
                        false,
                        List.of("name = 'mvcc-beta'"),
                        List.of("payload like 'two%'"));

                DelosOptimizerPredicatePushdownDecision defaultDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.consumeForTesting(request);
                assertFalse("default mode must not consider optimizer pushdown planning",
                        defaultDecision.optimizerPlanningConsidered());
                assertFalse("default mode must not consume predicate metadata",
                        defaultDecision.consumedByDerbyOptimizer());
                assertEquals("default mode must record no consumed decisions",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.optimizerConsumedCountForTesting());

                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, "enabled");
                System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME);
                DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
                DelosOptimizerPredicatePushdownDecision planningOnlyDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.consumeForTesting(request);
                assertTrue("planning opt-in should consider pushdown metadata",
                        planningOnlyDecision.optimizerPlanningConsidered());
                assertTrue("safe current-committed MVCC predicate remains storage-pushable",
                        planningOnlyDecision.storagePlanPushable());
                assertFalse("planning opt-in alone must not mark optimizer consumption",
                        planningOnlyDecision.consumedByDerbyOptimizer());
                assertEquals("planning-only mode must record no consumed decisions",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.optimizerConsumedCountForTesting());

                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.CONSUMPTION_PROPERTY_NAME, "enabled");
                DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
                DelosOptimizerPredicatePushdownDecision consumedDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.consumeForTesting(request);
                assertTrue("second explicit property should enable optimizer predicate metadata consumption",
                        DelosOptimizerPredicatePushdownDiagnostics.consumptionEnabledForTesting());
                assertTrue("optimizer should mark the storage-side predicate metadata as consumed",
                        consumedDecision.consumedByDerbyOptimizer());
                assertEquals("storage candidate must remain pushed metadata",
                        List.of("name = 'mvcc-beta'"),
                        consumedDecision.pushedPredicates());
                assertEquals("Derby remainder must still be preserved",
                        List.of("payload like 'two%'"),
                        consumedDecision.remainderPredicates());
                assertFalse("consumption marker must not claim execution pushdown by itself",
                        consumedDecision.executionPushdownApplied());
                assertTrue("diagnostics must document preserved Derby remainder evaluation",
                        consumedDecision.diagnosticLine().contains("derbyRemainderEvaluation=preserved"));
                assertEquals("one consumed optimizer decision should be recorded",
                        1,
                        DelosOptimizerPredicatePushdownDiagnostics.optimizerConsumedCountForTesting());

                DelosStoragePredicatePushdownRequest snapshotRequest = new DelosStoragePredicatePushdownRequest(
                        "delos_mvcc",
                        databasePath(databaseName),
                        0,
                        mvccContainerId,
                        "snapshot name = 'mvcc-beta'",
                        false,
                        true,
                        false,
                        List.of("name = 'mvcc-beta'"),
                        List.of());
                DelosOptimizerPredicatePushdownDecision snapshotDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.consumeForTesting(snapshotRequest);
                assertFalse("snapshot shortcut remains unproven and must not be optimizer-consumed",
                        snapshotDecision.consumedByDerbyOptimizer());
                assertEquals("snapshot candidate must stay as Derby remainder metadata",
                        List.of("name = 'mvcc-beta'"),
                        snapshotDecision.remainderPredicates());

                assertRows(connection,
                        "select id, name from opt_consume_mvcc_t where name = 'mvcc-beta' and payload like 'two%'",
                        "2|mvcc-beta");
                connection.commit();
            }
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
}
