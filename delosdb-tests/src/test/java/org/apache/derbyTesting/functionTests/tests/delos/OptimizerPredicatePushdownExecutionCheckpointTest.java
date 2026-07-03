/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.OptimizerPredicatePushdownExecutionCheckpointTest

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

import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDecision;
import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics;

/** SQL gate for the opt-in predicate-pushdown execution checkpoint. */
public final class OptimizerPredicatePushdownExecutionCheckpointTest extends MvccSqlTestSupport {
    public void testPredicatePushdownExecutionCheckpointIsExplicitlyOptIn() throws Exception {
        String databaseName = databaseName("optimizer-predicate-pushdown-execution-db");
        String oldMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);

        try {
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();

            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table opt_exec_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into opt_exec_mvcc_t values (1, 'mvcc-alpha', 'one')");
                executeUpdate(connection, "insert into opt_exec_mvcc_t values (2, 'mvcc-beta', 'two')");
                executeUpdate(connection, "insert into opt_exec_mvcc_t values (3, 'other-gamma', 'three')");
                connection.commit();

                // Use a non-primary ordered MVCC sidecar predicate here.
                // A primary-key equality may be represented by Derby as scan
                // start/stop keys or an inherited row-location path, bypassing
                // this checkpoint's qualifier-based ordered row-id shortcut.
                assertRows(connection,
                        "select id, name from opt_exec_mvcc_t where name = 'mvcc-beta' and payload like 'two%'",
                        "2|mvcc-beta");
                assertEquals("default mode must not record execution pushdown",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.executionPushdownAppliedCountForTesting());

                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, "enabled");
                DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();

                assertRows(connection,
                        "select id, name from opt_exec_mvcc_t where name = 'mvcc-beta' and payload like 'two%'",
                        "2|mvcc-beta");

                assertTrue("opt-in execution path should record an applied MVCC ordered row-id shortcut",
                        DelosOptimizerPredicatePushdownDiagnostics.executionPushdownAppliedCountForTesting() > 0);
                DelosOptimizerPredicatePushdownDecision lastDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.lastDecisionForTesting();
                assertNotNull("execution checkpoint should leave a diagnostic decision", lastDecision);
                assertTrue("execution checkpoint should be explicitly opt-in",
                        lastDecision.optInEnabled());
                assertTrue("execution checkpoint should mark the storage shortcut as applied",
                        lastDecision.executionPushdownApplied());
                assertFalse("execution checkpoint must not claim Derby optimizer predicate consumption yet",
                        lastDecision.consumedByDerbyOptimizer());
                assertTrue("Derby remainder evaluation must remain documented",
                        lastDecision.diagnosticLine().contains("derbyRemainderEvaluation=preserved"));

                DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                assertRows(connection,
                        "select id, name from opt_exec_mvcc_t where name = 'mvcc-beta' and payload like 'two%'",
                        "2|mvcc-beta");
                assertEquals("repeatable-read snapshots must not record current-committed execution pushdown",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.executionPushdownAppliedCountForTesting());
                connection.commit();
            }
        } finally {
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
            if (oldMode == null) {
                System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            } else {
                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, oldMode);
            }
        }
    }
}
