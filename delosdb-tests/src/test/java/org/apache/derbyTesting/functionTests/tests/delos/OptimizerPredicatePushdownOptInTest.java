/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.OptimizerPredicatePushdownOptInTest

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

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDecision;
import org.apache.derby.iapi.store.types.DelosOptimizerPredicatePushdownDiagnostics;
import org.apache.derby.iapi.store.types.DelosStoragePredicatePushdownRequest;

/** SQL gate for opt-in optimizer predicate pushdown planning diagnostics. */
public final class OptimizerPredicatePushdownOptInTest extends MvccSqlTestSupport {
    public void testOptimizerPredicatePushdownPlanningIsExplicitlyOptIn() throws Exception {
        String databaseName = databaseName("optimizer-predicate-pushdown-optin-db");
        Path databaseDirectory = new File(databaseName).toPath();
        String oldMode = System.getProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);

        try {
            System.clearProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME);
            DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();

            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table opt_push_heap_t "
                        + "(id int primary key, name varchar(32), payload varchar(64))");
                executeUpdate(connection, "create index opt_push_heap_name_idx on opt_push_heap_t(name)");
                executeUpdate(connection, "create table opt_push_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into opt_push_heap_t values (1, 'heap-alpha', 'one')");
                executeUpdate(connection, "insert into opt_push_heap_t values (2, 'heap-beta', 'two')");
                executeUpdate(connection, "insert into opt_push_mvcc_t values (1, 'mvcc-alpha', 'one')");
                executeUpdate(connection, "insert into opt_push_mvcc_t values (2, 'mvcc-beta', 'two')");
                executeUpdate(connection, "insert into opt_push_mvcc_t values (3, 'mvcc-gamma', 'three')");
                connection.commit();

                long heapContainerId = baseContainerId(connection, "OPT_PUSH_HEAP_T", "heap");
                long mvccContainerId = mvccContainerId(connection, "OPT_PUSH_MVCC_T");

                DelosStoragePredicatePushdownRequest mvccRequest = new DelosStoragePredicatePushdownRequest(
                        "delos_mvcc",
                        null,
                        0,
                        mvccContainerId,
                        "id >= 1 and name like 'mvcc%'",
                        true,
                        false,
                        false,
                        List.of("id >= 1"),
                        List.of("name like 'mvcc%'"));

                assertFalse("optimizer predicate pushdown must be disabled by default",
                        DelosOptimizerPredicatePushdownDiagnostics.enabledForTesting());
                DelosOptimizerPredicatePushdownDecision disabledDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.considerForTesting(mvccRequest);
                assertFalse("default mode must not consider optimizer-side pushdown planning",
                        disabledDecision.optimizerPlanningConsidered());
                assertTrue("MVCC metadata model can still identify a storage-pushable candidate",
                        disabledDecision.storagePlanPushable());
                assertFalse("default mode must not apply execution pushdown",
                        disabledDecision.executionPushdownApplied());
                assertEquals("default mode records the review decision without consuming it",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.planningConsideredCountForTesting());

                System.setProperty(DelosOptimizerPredicatePushdownDiagnostics.PROPERTY_NAME, "enabled");
                DelosOptimizerPredicatePushdownDiagnostics.clearForTesting();
                assertTrue("explicit property should enable optimizer-side pushdown planning diagnostics",
                        DelosOptimizerPredicatePushdownDiagnostics.enabledForTesting());

                DelosOptimizerPredicatePushdownDecision enabledDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.considerForTesting(mvccRequest);
                assertTrue("opt-in mode should consider optimizer-side pushdown planning",
                        enabledDecision.optimizerPlanningConsidered());
                assertTrue("safe current-committed MVCC ordered predicate should remain storage-pushable",
                        enabledDecision.storagePlanPushable());
                assertEquals("storage candidate should be preserved",
                        List.of("id >= 1"),
                        enabledDecision.pushedPredicates());
                assertEquals("Derby remainder must be preserved",
                        List.of("name like 'mvcc%'"),
                        enabledDecision.remainderPredicates());
                assertFalse("this gate must not apply execution pushdown yet",
                        enabledDecision.executionPushdownApplied());
                assertFalse("this gate must not consume predicates in Derby optimizer yet",
                        enabledDecision.consumedByDerbyOptimizer());
                assertEquals("one opt-in planning decision should be recorded",
                        1,
                        DelosOptimizerPredicatePushdownDiagnostics.planningConsideredCountForTesting());
                assertEquals("execution pushdown must remain unused",
                        0,
                        DelosOptimizerPredicatePushdownDiagnostics.executionPushdownAppliedCountForTesting());
                assertTrue("diagnostic line should identify the planning path",
                        enabledDecision.diagnosticLine().contains("path=optimizer-predicate-pushdown"));
                assertTrue("diagnostic line should document that execution pushdown is still off",
                        enabledDecision.diagnosticLine().contains("executionPushdownApplied=false"));

                DelosStoragePredicatePushdownRequest heapRequest = new DelosStoragePredicatePushdownRequest(
                        "derby_heap",
                        databaseDirectory,
                        0,
                        heapContainerId,
                        "id >= 1 and name like 'heap%'",
                        true,
                        false,
                        false,
                        List.of("id >= 1"),
                        List.of("name like 'heap%'"));
                DelosOptimizerPredicatePushdownDecision heapDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.considerForTesting(heapRequest);
                assertTrue("heap request can be considered in opt-in planning diagnostics",
                        heapDecision.optimizerPlanningConsidered());
                assertFalse("heap must not expose Delos ordered storage pushdown yet",
                        heapDecision.storagePlanPushable());
                assertTrue("heap storage candidate must remain a Derby remainder",
                        heapDecision.remainderPredicates().contains("id >= 1"));

                DelosStoragePredicatePushdownRequest snapshotRequest = new DelosStoragePredicatePushdownRequest(
                        "delos_mvcc",
                        null,
                        0,
                        mvccContainerId,
                        "snapshot id >= 1",
                        false,
                        true,
                        false,
                        List.of("id >= 1"),
                        List.of());
                DelosOptimizerPredicatePushdownDecision snapshotDecision =
                        DelosOptimizerPredicatePushdownDiagnostics.considerForTesting(snapshotRequest);
                assertTrue("snapshot request can be reviewed by opt-in planning diagnostics",
                        snapshotDecision.optimizerPlanningConsidered());
                assertFalse("snapshot shortcut remains unproven and must not be storage-pushable",
                        snapshotDecision.storagePlanPushable());
                assertEquals("snapshot candidate must stay as Derby remainder",
                        List.of("id >= 1"),
                        snapshotDecision.remainderPredicates());

                assertRows(connection,
                        "select id, name from opt_push_heap_t where id >= 1 and name like 'heap%' order by id",
                        "1|heap-alpha",
                        "2|heap-beta");
                assertRows(connection,
                        "select id, name from opt_push_mvcc_t where id >= 1 and name like 'mvcc%' order by id",
                        "1|mvcc-alpha",
                        "2|mvcc-beta",
                        "3|mvcc-gamma");
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
