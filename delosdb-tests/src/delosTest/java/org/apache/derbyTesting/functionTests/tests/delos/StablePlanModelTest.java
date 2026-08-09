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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.jdbc.EnginePreparedStatement;

/** Phase 10.1 proof for the immutable selected-plan model and stable semantics. */
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
            assertEquals("COST_SELECTED_JOIN_STRATEGY", join.decisionReason());
            connection.commit();
        }
    }

    public void testStablePlanCapturesPredicatePlacementOrderingAndDecisionReasons() throws Exception {
        String databaseName = databaseName("stable-plan-semantics-db");
        String forcedIndexSql = "select id from plan_semantics_t --DERBY-PROPERTIES index=plan_semantics_v_idx\n"
                + "where v = 20 and upper(note) = 'B'";
        String forcedTableSortSql = "select id from plan_semantics_t --DERBY-PROPERTIES index=NULL\n"
                + "where v >= 10 order by v desc";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table plan_semantics_t (id int primary key, v int, note varchar(16))");
            executeUpdate(connection, "create index plan_semantics_v_idx on plan_semantics_t(v)");
            executeUpdate(connection,
                    "insert into plan_semantics_t values (1, 10, 'A'), (2, 20, 'B'), (3, 30, 'C')");
            connection.commit();

            StablePlanModel forcedIndex = plan(connection, forcedIndexSql);
            StablePlanModel forcedTableSort = plan(connection, forcedTableSortSql, 3);

            StablePlanModel.Node indexScan = node(forcedIndex, "INDEX_SCAN");
            assertEquals("FORCED_INDEX", indexScan.decisionReason());
            assertTrue(indexScan.ordering().contains("INDEX:COLUMN(V):ASC"));
            assertTrue("forced non-covering index must explain the base-row fetch",
                    forcedIndex.nodes().stream()
                            .anyMatch(n -> "INDEX_NOT_COVERING".equals(n.decisionReason())));
            assertTrue("index equality must be placed at the store",
                    allPredicates(forcedIndex).stream()
                            .anyMatch(p -> p.startsWith("STORE")
                                    && p.contains("V)")
                                    && p.contains("LITERAL(INTEGER)")));
            assertTrue("method predicate must remain above the store",
                    allPredicates(forcedIndex).stream()
                            .anyMatch(p -> (p.startsWith("RESIDUAL") || p.startsWith("FILTER"))
                                    && p.contains("EXPRESSION")));

            StablePlanModel.Node tableScan = node(forcedTableSort, "TABLE_SCAN");
            assertEquals("FORCED_TABLE_SCAN", tableScan.decisionReason());
            StablePlanModel.Node sort = node(forcedTableSort, "ORDER_BY");
            assertEquals("SORT_REQUIRED", sort.decisionReason());
            assertTrue("ORDER BY semantics must be stable",
                    sort.ordering().stream().anyMatch(order ->
                            order.startsWith("ORDER_BY:")
                                    && order.contains("V)")
                                    && order.endsWith(":DESC:NULLS_HIGH")));
            connection.commit();
        }
    }

    public void testStablePlanCoversCoreStatementShapesAndRejectionBoundary() throws Exception {
        String databaseName = databaseName("stable-plan-shapes-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table plan_shape_t (id int primary key, v int, note varchar(16))");
            executeUpdate(connection,
                    "insert into plan_shape_t values (1, 10, 'A'), (2, 20, 'B'), (3, 30, 'C')");
            connection.commit();

            StablePlanModel values = plan(connection, "values (1), (2)", 2);
            assertPlanShape(values, "SELECT");
            assertNode(values, "ROW_VALUES");

            StablePlanModel aggregate = plan(
                    connection, "select v, count(*) from plan_shape_t group by v", 3);
            assertPlanShape(aggregate, "SELECT");
            assertNode(aggregate, "GROUP_BY");

            StablePlanModel distinct = plan(connection, "select distinct note from plan_shape_t", 3);
            assertPlanShape(distinct, "SELECT");
            assertNode(distinct, "DISTINCT");

            StablePlanModel union = plan(connection,
                    "select id from plan_shape_t where id = 1 "
                            + "union select id from plan_shape_t where id = 2",
                    2);
            assertPlanShape(union, "SELECT");
            assertNode(union, "UNION");

            StablePlanModel limited = plan(
                    connection, "select id from plan_shape_t fetch first 1 rows only", 1);
            assertPlanShape(limited, "SELECT");
            assertNode(limited, "ROW_COUNT");

            StablePlanModel derived = plan(connection,
                    "select d.v from (select v, count(*) as c from plan_shape_t group by v) d "
                            + "where d.c > 0",
                    3);
            assertPlanShape(derived, "SELECT");
            assertNode(derived, "GROUP_BY");
            assertTrue("derived plan must retain its underlying table access",
                    derived.nodes().stream().anyMatch(node ->
                            "TABLE_SCAN".equals(node.physicalOperation())
                                    || "INDEX_SCAN".equals(node.physicalOperation())));

            StablePlanModel insert = updatePlan(
                    connection, "insert into plan_shape_t values (4, 40, 'D')", 1);
            assertPlanShape(insert, "INSERT");
            assertNoGenericNodes(insert);

            StablePlanModel update = updatePlan(
                    connection, "update plan_shape_t set v = v + 1 where id = 4", 1);
            assertPlanShape(update, "UPDATE");
            assertNoGenericNodes(update);

            StablePlanModel delete = updatePlan(
                    connection, "delete from plan_shape_t where id = 4", 1);
            assertPlanShape(delete, "DELETE");
            assertNoGenericNodes(delete);

            StablePlanModel ddl = preparedPlan(
                    connection, "create table plan_shape_ddl_t (id int)");
            assertEquals("CREATE TABLE", ddl.statementType());
            assertEquals("APP", ddl.compilationSchema());
            assertNull("DDL has no optimizer-selected result-set root", ddl.rootNodeId());
            assertTrue("DDL has no optimizer-selected result-set nodes", ddl.nodes().isEmpty());

            try {
                connection.prepareStatement("select missing_column from plan_shape_t");
                fail("invalid statement should be rejected before a stable selected plan exists");
            } catch (SQLException expected) {
                assertEquals("42X04", expected.getSQLState());
            }
            connection.rollback();
        }
    }

    private static StablePlanModel plan(Connection connection, String sql) throws Exception {
        return plan(connection, sql, 1);
    }

    private static StablePlanModel plan(Connection connection, String sql, int expectedRows)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            StablePlanModel model = stablePlan(statement);

            int rows = 0;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows++;
                }
            }
            assertEquals("plan inspection must not alter query results", expectedRows, rows);
            return model;
        }
    }

    private static StablePlanModel updatePlan(
            Connection connection, String sql, int expectedUpdateCount) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            StablePlanModel model = stablePlan(statement);
            assertEquals("plan inspection must not alter update count",
                    expectedUpdateCount, statement.executeUpdate());
            return model;
        }
    }

    private static StablePlanModel preparedPlan(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return stablePlan(statement);
        }
    }

    private static StablePlanModel stablePlan(PreparedStatement statement) throws Exception {
        if (!(statement instanceof EnginePreparedStatement engineStatement)) {
            throw new AssertionError("engine prepared statement required for stable-plan inspection");
        }
        StablePlanModel model = engineStatement.getStablePlanModel();
        if (model == null) {
            throw new AssertionError("compiled statement did not retain a stable plan model");
        }
        return model;
    }

    private static void assertNode(StablePlanModel model, String physicalOperation) {
        node(model, physicalOperation);
        assertNoGenericNodes(model);
    }

    private static void assertNoGenericNodes(StablePlanModel model) {
        assertFalse("supported plan shape must not fall back to GENERIC: " + model.nodes(),
                model.nodes().stream().anyMatch(node ->
                        "GENERIC".equals(node.physicalOperation())
                                || "UNCLASSIFIED_RESULT_SET".equals(node.decisionReason())));
    }

    private static StablePlanModel.Node node(StablePlanModel model, String physicalOperation) {
        return model.nodes().stream()
                .filter(candidate -> physicalOperation.equals(candidate.physicalOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing plan node " + physicalOperation + " in " + model.nodes()));
    }

    private static java.util.List<String> allPredicates(StablePlanModel model) {
        return model.nodes().stream().flatMap(node -> node.predicates().stream()).toList();
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
