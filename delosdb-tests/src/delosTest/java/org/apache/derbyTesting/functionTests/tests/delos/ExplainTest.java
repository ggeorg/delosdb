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
import org.apache.derby.iapi.jdbc.EnginePreparedStatement;
import org.apache.derby.iapi.sql.compile.StablePlanModel;
import org.apache.derby.iapi.sql.compile.StablePlanRenderer;

/** Phase 10.2 proof for deterministic SQL EXPLAIN over StablePlanModel. */
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
