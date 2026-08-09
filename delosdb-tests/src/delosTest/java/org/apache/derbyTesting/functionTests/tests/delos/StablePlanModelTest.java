/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StablePlanModelTest

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.iapi.sql.execute.ExecPreparedStatement;

/** Phase 10.1 foundation proof for the immutable selected-plan model. */
public final class StablePlanModelTest extends MvccSqlTestSupport {
    public void testStablePlanCapturesHeapAndMvccIndexChoice() throws Exception {
        String databaseName = databaseName("stable-plan-storage-db");
        String heapSql = "select id from heap_plan_t --DERBY-PROPERTIES index=heap_plan_v_idx\n"
                + "where v = 20";
        String mvccSql = "select id from mvcc_plan_t --DERBY-PROPERTIES index=mvcc_plan_v_idx\n"
                + "where v = 20";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_plan_t (id int primary key, v int)");
            executeUpdate(connection, "create index heap_plan_v_idx on heap_plan_t(v)");
            executeUpdate(connection, "create table mvcc_plan_t (id int primary key, v int) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_plan_v_idx on mvcc_plan_t(v)");
            executeUpdate(connection, "insert into heap_plan_t values (1, 10), (2, 20), (3, 30)");
            executeUpdate(connection, "insert into mvcc_plan_t values (1, 10), (2, 20), (3, 30)");
            connection.commit();

            StablePlanModel heap = plan(connection, heapSql);
            StablePlanModel mvcc = plan(connection, mvccSql);
            connection.commit();
            executeUpdate(connection, "call syscs_util.syscs_empty_statement_cache()");
            connection.commit();
            StablePlanModel heapAgain = plan(connection, heapSql);

            assertPlanShape(heap, "SELECT");
            assertPlanShape(mvcc, "SELECT");
            assertEquals(heap.statementId(), heapAgain.statementId());
            assertEquals(heap.nodes(), heapAgain.nodes());
            assertFalse(heap.statementId().equals(mvcc.statementId()));
            assertScan(heap, "heap", "HEAP_PLAN_V_IDX", "HEAP_PLAN_T");
            assertScan(mvcc, "delos_mvcc", "MVCC_PLAN_V_IDX", "MVCC_PLAN_T");
            connection.commit();
        }
    }

    public void testStablePlanCapturesJoinStrategyAndDeterministicTree() throws Exception {
        String databaseName = databaseName("stable-plan-join-db");
        String sql = "select h.id, m.id from heap_join_t h, mvcc_join_t m "
                + "where h.v = m.v and h.v = 20";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_join_t (id int primary key, v int)");
            executeUpdate(connection, "create table mvcc_join_t (id int primary key, v int) using delos_mvcc");
            executeUpdate(connection, "create index heap_join_v_idx on heap_join_t(v)");
            executeUpdate(connection, "create index mvcc_join_v_idx on mvcc_join_t(v)");
            executeUpdate(connection, "insert into heap_join_t values (1, 10), (2, 20)");
            executeUpdate(connection, "insert into mvcc_join_t values (1, 10), (2, 20)");
            connection.commit();

            StablePlanModel first = plan(connection, sql);
            connection.commit();
            executeUpdate(connection, "call syscs_util.syscs_empty_statement_cache()");
            connection.commit();
            StablePlanModel second = plan(connection, sql);
            assertPlanShape(first, "SELECT");
            assertEquals(first.statementId(), second.statementId());
            assertEquals(first.nodes(), second.nodes());

            StablePlanModel.Node join = first.nodes().stream()
                    .filter(node -> "JOIN".equals(node.logicalOperation()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("selected plan must expose a JOIN node"));
            assertNotNull(join.joinStrategy());
            assertFalse(join.joinStrategy().isBlank());
            connection.commit();
        }
    }

    private static StablePlanModel plan(Connection connection, String sql) throws Exception {
        if (!(connection instanceof EmbedConnection embedded)) {
            throw new AssertionError("embedded connection required for stable-plan inspection");
        }
        ExecPreparedStatement prepared = (ExecPreparedStatement)
                embedded.getLanguageConnection().prepareInternalStatement(sql);
        StablePlanModel model = prepared.getStablePlanModel();
        if (model == null) {
            throw new AssertionError("compiled statement did not retain a stable plan model");
        }

        int rows = 0;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                rows++;
            }
        }
        assertEquals("plan inspection must not alter query results", 1, rows);
        return model;
    }

    private static void assertPlanShape(StablePlanModel model, String statementType) {
        assertEquals(StablePlanModel.CURRENT_SCHEMA_VERSION, model.schemaVersion());
        assertEquals(statementType, model.statementType());
        assertEquals("APP", model.compilationSchema());
        assertEquals("n0", model.rootNodeId());
        assertFalse(model.truncated());
        assertFalse(model.nodes().isEmpty());

        Map<String, StablePlanModel.Node> byId = new HashMap<>();
        for (int i = 0; i < model.nodes().size(); i++) {
            StablePlanModel.Node node = model.nodes().get(i);
            assertEquals("n" + i, node.id());
            assertNull(byId.put(node.id(), node));
            if (i == 0) {
                assertNull(node.parentId());
            } else {
                assertNotNull(node.parentId());
                assertTrue("parent must precede child", byId.containsKey(node.parentId()));
            }
        }
    }

    private static void assertScan(
            StablePlanModel model, String storageMode, String indexName, String relationName) {
        StablePlanModel.Node scan = model.nodes().stream()
                .filter(node -> storageMode.equals(node.storageMode()))
                .filter(node -> indexName.equals(node.accessPath()))
                .filter(node -> "INDEX_SCAN".equals(node.physicalOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing " + storageMode + " index scan through " + indexName));
        assertNotNull(scan.relation());
        assertTrue(scan.relation().contains(relationName));
        assertNotNull(scan.estimatedRows());
        assertNotNull(scan.estimatedCost());
        assertTrue(scan.estimatedRows() >= 0.0d);
        assertTrue(scan.estimatedCost() >= 0.0d);
        assertTrue(Double.isFinite(scan.estimatedCost()));
    }
}
