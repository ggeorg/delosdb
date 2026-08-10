/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.ExplainTest

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
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.apache.derby.iapi.jdbc.EnginePreparedStatement;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.compile.StablePlanRenderer;
import org.apache.derby.iapi.sql.compile.StablePlanExecutionRenderer;
import org.apache.derby.iapi.sql.execute.StablePlanExecutionEvidence;

/** Phase 10.2/10.3 proof for deterministic EXPLAIN and query execution evidence. */
public final class ExplainTest extends MvccSqlTestSupport {
    public void testExplainRendersSameHeapAndMvccPreparedPlan() throws Exception {
        String databaseName = databaseName("explain-plan-db");
        String heap = "select id from explain_heap_t --DERBY-PROPERTIES index=explain_heap_v_idx\n"
                + "where v = 20";
        String mvcc = "select id from explain_mvcc_t --DERBY-PROPERTIES index=explain_mvcc_v_idx\n"
                + "where v = 20";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table explain_heap_t (id int primary key, v int)");
            executeUpdate(connection, "create index explain_heap_v_idx on explain_heap_t(v)");
            executeUpdate(connection,
                    "create table explain_mvcc_t (id int primary key, v int) using delos_mvcc");
            executeUpdate(connection, "create index explain_mvcc_v_idx on explain_mvcc_t(v)");
            executeUpdate(connection, "insert into explain_heap_t values (1, 10), (2, 20)");
            executeUpdate(connection, "insert into explain_mvcc_t values (1, 10), (2, 20)");
            connection.commit();

            assertExplainMatchesDirectPlan(connection, heap, "heap", "EXPLAIN_HEAP_V_IDX");
            assertExplainMatchesDirectPlan(connection, mvcc, "delos_mvcc", "EXPLAIN_MVCC_V_IDX");
            connection.commit();
        }
    }

    public void testExplainDoesNotExecuteMutationOrDdl() throws Exception {
        String databaseName = databaseName("explain-no-execute-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table explain_mutation_t (id int primary key, v int)");
            executeUpdate(connection, "insert into explain_mutation_t values (1, 10)");
            connection.commit();

            ExplainOutput update = explain(connection,
                    "update explain_mutation_t set v = 99 where id = 1");
            assertTrue(update.text().contains("statementType=UPDATE"));
            assertTrue(update.json().contains("\"statementType\":\"UPDATE\""));
            try (Statement query = connection.createStatement();
                    ResultSet rs = query.executeQuery(
                            "select v from explain_mutation_t where id = 1")) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt(1));
            }

            ExplainOutput ddl = explain(connection,
                    "create table explain_never_created_t (id int)");
            assertTrue(ddl.text().contains("statementType=CREATE TABLE"));
            assertTrue(ddl.json().contains("\"nodes\":[]"));
            try (Statement query = connection.createStatement();
                    ResultSet rs = query.executeQuery(
                            "select count(*) from sys.systables "
                                    + "where tablename = 'EXPLAIN_NEVER_CREATED_T'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }

            try {
                connection.prepareStatement("explain select missing_column from explain_mutation_t");
                fail("EXPLAIN must preserve target compilation rejection");
            } catch (SQLException expected) {
                assertEquals("42X04", expected.getSQLState());
            }
            connection.rollback();
        }
    }

    public void testExplainFormatAndRecompileDeterminism() throws Exception {
        StablePlanModel format = new StablePlanModel(
                StablePlanModel.CURRENT_SCHEMA_VERSION,
                "stmt-1",
                "SELECT",
                "APP",
                "n0",
                List.of(new StablePlanModel.Node(
                        "n0", null, "SCAN", "INDEX_SCAN", "\"APP\".\"T\"",
                        "heap", "T_V_IDX", "NESTEDLOOP", 2.5d, 3.75d,
                        List.of("STORE:COLUMN(T.V) = PARAMETER(1)"),
                        List.of("INDEX:COLUMN(V):ASC"), "FORCED_INDEX")),
                false);
        assertEquals(
                "PLAN schemaVersion=1 statementId=stmt-1 statementType=SELECT schema=APP "
                        + "root=n0 nodes=1 truncated=false\n"
                        + "n0 SCAN/INDEX_SCAN relation=\"APP\".\"T\" storage=heap "
                        + "access=T_V_IDX join=NESTEDLOOP rows=2.5 cost=3.75 "
                        + "reason=FORCED_INDEX "
                        + "predicates=[STORE:COLUMN(T.V) = PARAMETER(1)] "
                        + "ordering=[INDEX:COLUMN(V):ASC]\n",
                StablePlanRenderer.text(format));
        assertEquals(
                "{\"schemaVersion\":1,\"statementId\":\"stmt-1\","
                        + "\"statementType\":\"SELECT\",\"compilationSchema\":\"APP\","
                        + "\"rootNodeId\":\"n0\",\"nodes\":[{"
                        + "\"id\":\"n0\",\"parentId\":null,"
                        + "\"logicalOperation\":\"SCAN\","
                        + "\"physicalOperation\":\"INDEX_SCAN\","
                        + "\"relation\":\"\\\"APP\\\".\\\"T\\\"\","
                        + "\"storageMode\":\"heap\",\"accessPath\":\"T_V_IDX\","
                        + "\"joinStrategy\":\"NESTEDLOOP\",\"estimatedRows\":2.5,"
                        + "\"estimatedCost\":3.75,"
                        + "\"predicates\":[\"STORE:COLUMN(T.V) = PARAMETER(1)\"],"
                        + "\"ordering\":[\"INDEX:COLUMN(V):ASC\"],"
                        + "\"decisionReason\":\"FORCED_INDEX\"}],\"truncated\":false}",
                StablePlanRenderer.json(format));

        StablePlanExecutionEvidence evidence = new StablePlanExecutionEvidence(
                StablePlanExecutionEvidence.CURRENT_SCHEMA_VERSION,
                "stmt-1",
                2,
                List.of(new StablePlanExecutionEvidence.Node(
                        "n0",
                        true,
                        1,
                        2L,
                        3,
                        1,
                        9,
                        2,
                        6,
                        1,
                        null,
                        List.of(
                                new StablePlanExecutionEvidence.Metric("ROWS_VISITED", 3),
                                new StablePlanExecutionEvidence.Metric("ROWS_QUALIFIED", 2)))),
                false);
        assertEquals(
                StablePlanRenderer.text(format)
                        + "EXECUTION schemaVersion=6 statementId=stmt-1 "
                        + "rootRowsReturned=2 nodes=1 truncated=false\n"
                        + "n0 observed=true opens=1 estimatedRows=2.5 actualRows=2 estimateComparison=OVER_ESTIMATE rowsSeen=3 rowsFiltered=1 "
                        + "elapsedMillis=9 openMillis=2 nextMillis=6 closeMillis=1 "
                        + "storage=[ROWS_VISITED=3,ROWS_QUALIFIED=2]\n",
                StablePlanExecutionRenderer.text(format, evidence));
        assertEquals(
                "{\"plan\":" + StablePlanRenderer.json(format)
                        + ",\"execution\":{\"schemaVersion\":6,"
                        + "\"statementId\":\"stmt-1\",\"rootRowsReturned\":2,"
                        + "\"nodes\":[{\"nodeId\":\"n0\",\"observed\":true,"
                        + "\"opens\":1,\"estimatedRows\":2.5,\"actualRows\":2,"
                        + "\"estimateComparison\":\"OVER_ESTIMATE\",\"rowsSeen\":3,\"rowsFiltered\":1,"
                        + "\"elapsedMillis\":9,\"openMillis\":2,\"nextMillis\":6,"
                        + "\"closeMillis\":1,"
                        + "\"storageMetrics\":[{\"name\":\"ROWS_VISITED\",\"value\":3},"
                        + "{\"name\":\"ROWS_QUALIFIED\",\"value\":2}]}],"
                        + "\"truncated\":false}}",
                StablePlanExecutionRenderer.json(format, evidence));

        StablePlanExecutionEvidence unknownRows = new StablePlanExecutionEvidence(
                StablePlanExecutionEvidence.CURRENT_SCHEMA_VERSION,
                "stmt-1",
                2,
                List.of(new StablePlanExecutionEvidence.Node(
                        "n0", true, 1, null, 3, 1, 9, 2, 6, 1, null, List.of())),
                false);
        assertTrue(StablePlanExecutionRenderer.text(format, unknownRows)
                .contains("estimatedRows=2.5 actualRows=null estimateComparison=UNKNOWN"));
        assertTrue(StablePlanExecutionRenderer.json(format, unknownRows)
                .contains("\"estimatedRows\":2.5,\"actualRows\":null,\"estimateComparison\":\"UNKNOWN\""));

        StablePlanModel mvccFormat = new StablePlanModel(
                StablePlanModel.CURRENT_SCHEMA_VERSION,
                "stmt-mvcc",
                "SELECT",
                "APP",
                "n0",
                List.of(new StablePlanModel.Node(
                        "n0", null, "SCAN", "INDEX_SCAN", "\"APP\".\"M\"",
                        "delos_mvcc", "M_V_IDX", null, 4.0d, 1.0d,
                        List.of(), List.of(), "FORCED_INDEX")),
                false);
        assertMvccDiagnosis(mvccFormat, 4, 3, 1, 2, "MIXED", "HISTORICAL");
        assertMvccDiagnosis(mvccFormat, 2, 0, 2, 2, "FALLBACK", "HEAD_ONLY");
        assertMvccDiagnosis(mvccFormat, 2, 2, 0, 0, "COVERED", "NONE");
        assertMvccDiagnosis(mvccFormat, 0, 0, 0, 0, "NO_CANDIDATES", "NONE");

        String databaseName = databaseName("explain-recompile-db");
        String sql = "select id from explain_recompile_t where v >= 10 order by v";
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table explain_recompile_t (id int primary key, v int)");
            executeUpdate(connection, "create index explain_recompile_v_idx on explain_recompile_t(v)");
            connection.commit();

            ExplainOutput first = explain(connection, sql);
            executeUpdate(connection, "call syscs_util.syscs_empty_statement_cache()");
            connection.commit();
            ExplainOutput second = explain(connection, sql);
            assertEquals(first.model(), second.model());
            assertEquals(first.text(), second.text());
            assertEquals(first.json(), second.json());
            connection.rollback();
        }
    }

    public void testExplainAnalyzeExecutesSelectWithHeapAndMvccEvidence() throws Exception {
        String databaseName = databaseName("explain-analyze-storage-db");
        String heap = "select id from explain_analyze_heap_t --DERBY-PROPERTIES index=null\n"
                + "where v >= 20";
        String mvcc = "select id from explain_analyze_mvcc_t --DERBY-PROPERTIES index=null\n"
                + "where v >= 20";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table explain_analyze_heap_t (id int primary key, v int)");
            executeUpdate(connection,
                    "create table explain_analyze_mvcc_t (id int, v int) "
                            + "using delos_mvcc");
            executeUpdate(connection,
                    "insert into explain_analyze_heap_t values (1, 10), (2, 20), (3, 30)");
            executeUpdate(connection,
                    "insert into explain_analyze_mvcc_t values (1, 10), (2, 20), (3, 30)");
            connection.commit();

            ExplainOutput heapOutput = analyze(connection, heap);
            assertTrue(heapOutput.text().contains("rootRowsReturned=2"));
            assertTrue(heapOutput.text().contains("estimatedRows="));
            assertTrue(heapOutput.text().contains("actualRows=2 estimateComparison="));
            assertTrue(heapOutput.text().contains("storage=heap"));
            assertTrue(heapOutput.text().contains("ROWS_VISITED="));
            assertTrue(heapOutput.text().contains("ROWS_QUALIFIED="));
            assertTrue(heapOutput.text().contains("elapsedMillis="));
            assertTrue(heapOutput.text().contains("nextMillis="));
            assertTrue(heapOutput.json().contains("\"rootRowsReturned\":2"));
            assertTrue(heapOutput.json().contains("\"estimatedRows\":"));
            assertTrue(heapOutput.json().contains("\"actualRows\":2,\"estimateComparison\":\""));
            assertTrue(heapOutput.json().contains("\"elapsedMillis\":"));
            assertTrue(heapOutput.json().contains("\"name\":\"PAGES_VISITED\""));

            ExplainOutput mvccOutput = analyze(connection, mvcc);
            assertTrue(mvccOutput.text().contains("rootRowsReturned=2"));
            assertTrue(mvccOutput.text().contains("estimatedRows="));
            assertTrue(mvccOutput.text().contains("actualRows=2 estimateComparison="));
            assertTrue(mvccOutput.text().contains("mvccSnapshotSequence="));
            assertFalse(mvccOutput.text().contains("mvccSnapshotSequence=-"));
            assertTrue(mvccOutput.text().contains(
                    "mvccReadPath=TABLE_SCAN mvccVersionTraversal=NOT_MEASURED"));
            assertTrue(mvccOutput.text().contains("storage=delos_mvcc"));
            assertTrue(mvccOutput.text().contains("ROWS_VISITED="));
            assertTrue(mvccOutput.text().contains("MVCC_VISIBILITY_CHECKS="));
            assertTrue(mvccOutput.text().contains("elapsedMillis="));
            assertTrue(mvccOutput.json().contains("\"rootRowsReturned\":2"));
            assertTrue(mvccOutput.json().contains("\"estimatedRows\":"));
            assertTrue(mvccOutput.json().contains("\"actualRows\":2,\"estimateComparison\":\""));
            assertTrue(mvccOutput.json().contains("\"mvccSnapshotSequence\":"));
            assertTrue(mvccOutput.json().contains(
                    "\"mvccReadPath\":\"TABLE_SCAN\","
                            + "\"mvccVersionTraversal\":\"NOT_MEASURED\""));
            assertTrue(mvccOutput.json().contains("\"elapsedMillis\":"));
            assertTrue(mvccOutput.json().contains("\"name\":\"MVCC_VERSION_CHAIN_STEPS\""));
            connection.rollback();
        }
    }

    public void testExplainAnalyzeActualRowsAcrossOperatorFamilies() throws Exception {
        String databaseName = databaseName("explain-analyze-actual-rows-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table explain_analyze_rows_t (id int primary key, g int, v int)");
            executeUpdate(connection,
                    "create table explain_analyze_join_t (id int primary key, marker int)");
            executeUpdate(connection,
                    "insert into explain_analyze_rows_t values "
                            + "(1, 1, 10), (2, 1, 20), (3, 2, 30)");
            executeUpdate(connection,
                    "insert into explain_analyze_join_t values (1, 100), (2, 200), (4, 400)");
            connection.commit();

            assertActualRows(connection,
                    "select id from explain_analyze_rows_t --DERBY-PROPERTIES index=null\n"
                            + "where v >= 20",
                    "TABLE_SCAN", 2);
            assertActualRows(connection,
                    "select id from explain_analyze_rows_t order by v",
                    "ORDER_BY", 3);
            assertActualRows(connection,
                    "select id from explain_analyze_rows_t order by id "
                            + "offset 1 rows fetch next 1 row only",
                    "ROW_COUNT", 1);
            assertActualRows(connection,
                    "select r.id from explain_analyze_rows_t r "
                            + "join explain_analyze_join_t j on r.id = j.id",
                    "JOIN", 2);
            assertActualRows(connection,
                    "select g, count(*) from explain_analyze_rows_t group by g",
                    "GROUP_BY", 2);
            assertActualRows(connection,
                    "select count(*) from explain_analyze_rows_t",
                    "GROUP_BY", 1);
            assertActualRows(connection,
                    "select id from explain_analyze_rows_t where id <= 2 "
                            + "union all select id from explain_analyze_rows_t where id = 3",
                    "UNION", 3);
            assertActualRows(connection,
                    "select g from explain_analyze_rows_t "
                            + "intersect select g from explain_analyze_rows_t where id <> 2",
                    "INTERSECT_OR_EXCEPT", 2);
            connection.rollback();
        }
    }

    public void testExplainAnalyzeParametersAndMutationBoundary() throws Exception {
        String databaseName = databaseName("explain-analyze-boundary-db");
        String sql = "select id from explain_analyze_parameter_t where analyze >= ? order by id";

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table explain_analyze_parameter_t (id int primary key, analyze int)");
            executeUpdate(connection,
                    "insert into explain_analyze_parameter_t values (1, 10), (2, 20), (3, 30)");
            connection.commit();

            try (PreparedStatement statement = connection.prepareStatement("explain analyze " + sql)) {
                StablePlanModel direct;
                try (PreparedStatement plain = connection.prepareStatement(sql)) {
                    direct = stablePlan(plain);
                }
                assertEquals(direct, stablePlan(statement));
                statement.setInt(1, 20);
                try (ResultSet rs = statement.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(rs.getString(1).contains("rootRowsReturned=2"));
                    assertTrue(rs.getString(2).contains("\"rootRowsReturned\":2"));
                    assertFalse(rs.next());
                }
            }

            try {
                connection.prepareStatement(
                        "explain analyze update explain_analyze_parameter_t set analyze = 99 where id = 1");
                fail("EXPLAIN ANALYZE must reject mutation in Phase 10.3A");
            } catch (SQLException expected) {
                assertEquals("0A000", expected.getSQLState());
            }
            try {
                connection.prepareStatement("explain analyze create table explain_analyze_ddl_t(i int)");
                fail("EXPLAIN ANALYZE must reject DDL in Phase 10.3A");
            } catch (SQLException expected) {
                assertEquals("0A000", expected.getSQLState());
            }
            try {
                connection.prepareStatement(
                        "explain analyze select * from explain_analyze_parameter_t for update");
                fail("EXPLAIN ANALYZE must reject updatable cursors in Phase 10.3A");
            } catch (SQLException expected) {
                assertEquals("0A000", expected.getSQLState());
            }
            try (Statement query = connection.createStatement();
                    ResultSet rs = query.executeQuery(
                            "select analyze from explain_analyze_parameter_t where id = 1")) {
                assertTrue(rs.next());
                assertEquals(10, rs.getInt(1));
            }
            connection.rollback();
        }
    }

    private static void assertMvccDiagnosis(
            StablePlanModel plan,
            long candidates,
            long covered,
            long fallback,
            long versionSteps,
            String readPath,
            String traversal) {
        StablePlanExecutionEvidence evidence = new StablePlanExecutionEvidence(
                StablePlanExecutionEvidence.CURRENT_SCHEMA_VERSION,
                plan.statementId(),
                candidates,
                List.of(new StablePlanExecutionEvidence.Node(
                        "n0", true, 1, candidates, candidates, 0, 1, 0, 1, 0, null,
                        List.of(
                                new StablePlanExecutionEvidence.Metric(
                                        "MVCC_ORDERED_CANDIDATES", candidates),
                                new StablePlanExecutionEvidence.Metric(
                                        "MVCC_COVERED_CANDIDATES", covered),
                                new StablePlanExecutionEvidence.Metric(
                                        "MVCC_FALLBACK_CANDIDATES", fallback),
                                new StablePlanExecutionEvidence.Metric(
                                        "MVCC_VERSION_CHAIN_STEPS", versionSteps)))),
                false);
        String text = "mvccReadPath=" + readPath + " mvccVersionTraversal=" + traversal;
        String json = "\"mvccReadPath\":\"" + readPath
                + "\",\"mvccVersionTraversal\":\"" + traversal + "\"";
        assertTrue(StablePlanExecutionRenderer.text(plan, evidence).contains(text));
        assertTrue(StablePlanExecutionRenderer.json(plan, evidence).contains(json));
    }

    private static void assertActualRows(
            Connection connection, String sql, String physicalOperation, long expected)
            throws Exception {
        ExplainOutput output = analyze(connection, sql);
        StablePlanModel.Node node = null;
        for (StablePlanModel.Node candidate : output.model().nodes()) {
            if (physicalOperation.equals(candidate.physicalOperation())) {
                node = candidate;
                break;
            }
        }
        assertNotNull("missing " + physicalOperation + " node for " + sql, node);
        String prefix = node.id() + " observed=true ";
        int start = output.text().indexOf(prefix);
        assertTrue("missing execution evidence for " + physicalOperation + " in " + sql, start >= 0);
        int end = output.text().indexOf('\n', start);
        String line = output.text().substring(start, end < 0 ? output.text().length() : end);
        assertTrue("wrong actualRows for " + physicalOperation + " in " + sql + ": " + line,
                line.contains(" actualRows=" + expected + " "));
        assertTrue(output.json().contains(
                "\"nodeId\":\"" + node.id() + "\",\"observed\":true,"
                        + "\"opens\":"));
        assertTrue(output.json().contains("\"actualRows\":" + expected));
    }

    private static void assertExplainMatchesDirectPlan(
            Connection connection, String sql, String storageMode, String indexName) throws Exception {
        StablePlanModel direct;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            direct = stablePlan(statement);
        }

        ExplainOutput explained = explain(connection, sql);
        assertEquals(direct, explained.model());
        assertEquals(StablePlanRenderer.text(direct), explained.text());
        assertEquals(StablePlanRenderer.json(direct), explained.json());
        assertTrue(explained.text().contains("storage=" + storageMode));
        assertTrue(explained.text().contains("access=" + indexName));
        assertTrue(explained.json().contains("\"storageMode\":\"" + storageMode + "\""));
        assertTrue(explained.json().contains("\"accessPath\":\"" + indexName + "\""));
    }

    private static ExplainOutput explain(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("explain " + sql)) {
            StablePlanModel model = stablePlan(statement);
            try (ResultSet rs = statement.executeQuery()) {
                assertEquals(2, rs.getMetaData().getColumnCount());
                assertEquals("PLAN_TEXT", rs.getMetaData().getColumnLabel(1));
                assertEquals("PLAN_JSON", rs.getMetaData().getColumnLabel(2));
                assertEquals(Types.CLOB, rs.getMetaData().getColumnType(1));
                assertEquals(Types.CLOB, rs.getMetaData().getColumnType(2));
                assertTrue(rs.next());
                String text = rs.getString(1);
                String json = rs.getString(2);
                assertFalse(rs.next());
                assertTrue(text.startsWith("PLAN schemaVersion=1 "));
                assertTrue(json.startsWith("{\"schemaVersion\":1,"));
                assertTrue(json.endsWith("}"));
                return new ExplainOutput(model, text, json);
            }
        }
    }

    private static ExplainOutput analyze(Connection connection, String sql) throws Exception {
        StablePlanModel direct;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            direct = stablePlan(statement);
        }
        try (PreparedStatement statement = connection.prepareStatement("explain analyze " + sql)) {
            assertEquals(direct, stablePlan(statement));
            try (ResultSet rs = statement.executeQuery()) {
                assertEquals(2, rs.getMetaData().getColumnCount());
                assertEquals("PLAN_TEXT", rs.getMetaData().getColumnLabel(1));
                assertEquals("PLAN_JSON", rs.getMetaData().getColumnLabel(2));
                assertEquals(Types.CLOB, rs.getMetaData().getColumnType(1));
                assertEquals(Types.CLOB, rs.getMetaData().getColumnType(2));
                assertTrue(rs.next());
                String text = rs.getString(1);
                String json = rs.getString(2);
                assertFalse(rs.next());
                assertTrue(text.startsWith("PLAN schemaVersion=1 "));
                assertTrue(text.contains("\nEXECUTION schemaVersion=6 "));
                assertTrue(json.startsWith("{\"plan\":{\"schemaVersion\":1,"));
                assertTrue(json.contains("\"execution\":{\"schemaVersion\":6,"));
                assertTrue(json.endsWith("}}"));
                return new ExplainOutput(stablePlan(statement), text, json);
            }
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

    private record ExplainOutput(StablePlanModel model, String text, String json) {}
}
