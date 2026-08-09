/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.ExplainCompatibilityTest

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
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import junit.framework.Test;

import org.apache.derbyTesting.junit.BaseJDBCTestCase;
import org.apache.derbyTesting.junit.BaseTestSuite;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;

/** Phase 10.2/10.3 embedded/DRDA compatibility proof for EXPLAIN surfaces. */
public final class ExplainCompatibilityTest extends BaseJDBCTestCase {
    public ExplainCompatibilityTest(String name) {
        super(name);
    }

    public static Test suite() {
        BaseTestSuite suite = new BaseTestSuite(ExplainCompatibilityTest.class);
        return TestConfiguration.clientServerDecorator(new CleanDatabaseTestSetup(suite));
    }

    public void testExplainPlanPayloadParity() throws Exception {
        assertNetworkClient();
        try (Connection embedded = openEmbedded("explain-compat-embedded-plan")) {
            Connection network = getConnection();
            setupPlanSchema(network);
            setupPlanSchema(embedded);

            assertSameExplain(network, embedded,
                    "select id from explain_compat_heap_t "
                            + "--DERBY-PROPERTIES index=explain_compat_heap_v_idx\n"
                            + "where v = 20 and upper(note) = 'B'",
                    "storage=heap", "access=EXPLAIN_COMPAT_HEAP_V_IDX", "FORCED_INDEX",
                    "EXPRESSION");
            assertSameExplain(network, embedded,
                    "select id from explain_compat_mvcc_t "
                            + "--DERBY-PROPERTIES index=explain_compat_mvcc_v_idx\n"
                            + "where v = 20",
                    "storage=delos_mvcc", "access=EXPLAIN_COMPAT_MVCC_V_IDX", "FORCED_INDEX");
            assertSameExplain(network, embedded,
                    "select h.id, m.id from explain_compat_heap_t h, explain_compat_mvcc_t m "
                            + "where h.v = m.v and h.v = 20",
                    " JOIN/", "COST_SELECTED_JOIN_STRATEGY");
            assertSameExplain(network, embedded,
                    "select distinct note from explain_compat_heap_t",
                    "DISTINCT/DISTINCT_SCAN");
            network.rollback();
            embedded.rollback();
        }
    }

    public void testExplainAnalyzePayloadParity() throws Exception {
        assertNetworkClient();
        try (Connection embedded = openEmbedded("explain-compat-embedded-analyze")) {
            Connection network = getConnection();
            setupAnalyzeSchema(network);
            setupAnalyzeSchema(embedded);

            assertSameAnalyze(network, embedded,
                    "select id from explain_analyze_compat_heap_t "
                            + "--DERBY-PROPERTIES index=null\n"
                            + "where v >= 20",
                    "storage=heap", "rootRowsReturned=2", "ROWS_VISITED");
            assertSameAnalyze(network, embedded,
                    "select id from explain_analyze_compat_mvcc_t "
                            + "--DERBY-PROPERTIES index=null\n"
                            + "where v >= 20",
                    "storage=delos_mvcc", "rootRowsReturned=2",
                    "MVCC_VISIBILITY_CHECKS", "MVCC_VERSION_CHAIN_STEPS");

            String parameterSql =
                    "explain analyze select id from explain_analyze_compat_heap_t where v >= ?";
            try (PreparedStatement remote = network.prepareStatement(parameterSql);
                    PreparedStatement local = embedded.prepareStatement(parameterSql)) {
                assertEquals(Types.INTEGER, remote.getParameterMetaData().getParameterType(1));
                remote.setInt(1, 20);
                local.setInt(1, 20);
                assertSameAnalyzePayload(readExplain(local), readExplain(remote), parameterSql);
            }
            network.rollback();
            embedded.rollback();
        }
    }

    public void testExplainPreparedParametersAndCacheDeterminism() throws Exception {
        assertNetworkClient();
        try (Connection embedded = openEmbedded("explain-compat-embedded-parameters")) {
            Connection network = getConnection();
            ExplainOutput networkOutput = parameterScenario(network);
            ExplainOutput embeddedOutput = parameterScenario(embedded);
            assertEquals("parameterized PLAN_TEXT differs across embedded/DRDA",
                    embeddedOutput.text(), networkOutput.text());
            assertEquals("parameterized PLAN_JSON differs across embedded/DRDA",
                    embeddedOutput.json(), networkOutput.json());
        }
    }

    public void testExplainLargeClobPayloadParity() throws Exception {
        assertNetworkClient();
        String sql = largeUnionSql(230);
        try (Connection embedded = openEmbedded("explain-compat-embedded-large")) {
            ExplainOutput network = explain(getConnection(), sql);
            ExplainOutput local = explain(embedded, sql);
            assertTrue("large EXPLAIN text should exercise non-trivial CLOB delivery: "
                            + network.text().length(),
                    network.text().length() > 8_192);
            assertTrue("large EXPLAIN JSON must cross the DRDA external-data threshold: "
                            + network.json().length(),
                    network.json().length() > 32_768);
            assertTrue(network.json().contains("\"truncated\":"));
            assertEquals("large PLAN_TEXT differs across embedded/DRDA", local.text(), network.text());
            assertEquals("large PLAN_JSON differs across embedded/DRDA", local.json(), network.json());
        }
    }

    private void assertNetworkClient() {
        assertTrue("EXPLAIN compatibility proof must run through Derby network client",
                getTestConfiguration().getJDBCClient().isDerbyNetClient());
        assertTrue(getTestConfiguration().getJDBCUrl().startsWith("jdbc:derby://"));
    }

    private static Connection openEmbedded(String databaseName) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:derby:" + databaseName + ";create=true");
        connection.setAutoCommit(false);
        return connection;
    }

    private static void setupPlanSchema(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table explain_compat_heap_t (id int primary key, v int, note varchar(16))");
        executeUpdate(connection, "create index explain_compat_heap_v_idx on explain_compat_heap_t(v)");
        executeUpdate(connection,
                "create table explain_compat_mvcc_t (id int primary key, v int, note varchar(16)) "
                        + "using delos_mvcc");
        executeUpdate(connection, "create index explain_compat_mvcc_v_idx on explain_compat_mvcc_t(v)");
        executeUpdate(connection,
                "insert into explain_compat_heap_t values "
                        + "(1, 10, 'A'), (2, 20, 'B'), (3, 20, 'B')");
        executeUpdate(connection,
                "insert into explain_compat_mvcc_t values "
                        + "(1, 10, 'A'), (2, 20, 'B'), (3, 20, 'B')");
        connection.commit();
    }

    private static void setupAnalyzeSchema(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table explain_analyze_compat_heap_t (id int primary key, v int)");
        executeUpdate(connection,
                "create table explain_analyze_compat_mvcc_t (id int primary key, v int) "
                        + "using delos_mvcc");
        executeUpdate(connection,
                "insert into explain_analyze_compat_heap_t values (1, 10), (2, 20), (3, 30)");
        executeUpdate(connection,
                "insert into explain_analyze_compat_mvcc_t values (1, 10), (2, 20), (3, 30)");
        connection.commit();
    }

    private static ExplainOutput parameterScenario(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection, "create table explain_compat_param_t (id int primary key, v int)");
        executeUpdate(connection, "create index explain_compat_param_v_idx on explain_compat_param_t(v)");
        connection.commit();

        String sql = "explain select id from explain_compat_param_t where v = ?";
        ExplainOutput first;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            ParameterMetaData parameters = statement.getParameterMetaData();
            assertEquals(1, parameters.getParameterCount());
            assertEquals(Types.INTEGER, parameters.getParameterType(1));
            statement.setInt(1, 10);
            first = readExplain(statement);
            statement.setInt(1, 20);
            assertEquals(first, readExplain(statement));
        }

        executeUpdate(connection, "call syscs_util.syscs_empty_statement_cache()");
        connection.commit();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 30);
            assertEquals(first, readExplain(statement));
        }
        connection.rollback();
        return first;
    }

    private static void assertSameExplain(
            Connection network, Connection embedded, String sql, String... fragments) throws Exception {
        ExplainOutput remote = explain(network, sql);
        ExplainOutput local = explain(embedded, sql);
        assertContains(remote, fragments);
        assertEquals("PLAN_TEXT differs across embedded/DRDA for " + sql, local.text(), remote.text());
        assertEquals("PLAN_JSON differs across embedded/DRDA for " + sql, local.json(), remote.json());
    }

    private static void assertSameAnalyze(
            Connection network, Connection embedded, String sql, String... fragments) throws Exception {
        ExplainOutput remote = analyze(network, sql);
        ExplainOutput local = analyze(embedded, sql);
        assertContains(remote, fragments);
        assertSameAnalyzePayload(local, remote, sql);
    }

    private static void assertSameAnalyzePayload(
            ExplainOutput local, ExplainOutput remote, String sql) {
        assertTimingFields(local);
        assertTimingFields(remote);
        assertEquals("ANALYZE PLAN_TEXT differs across embedded/DRDA for " + sql,
                normalizeTiming(local.text()), normalizeTiming(remote.text()));
        assertEquals("ANALYZE PLAN_JSON differs across embedded/DRDA for " + sql,
                normalizeTiming(local.json()), normalizeTiming(remote.json()));
    }

    private static void assertTimingFields(ExplainOutput output) {
        assertTrue(output.text().contains("EXECUTION schemaVersion=2 "));
        assertTrue(output.text().contains(" elapsedMillis="));
        assertTrue(output.text().contains(" openMillis="));
        assertTrue(output.text().contains(" nextMillis="));
        assertTrue(output.text().contains(" closeMillis="));
        assertTrue(output.json().contains("\"execution\":{\"schemaVersion\":2,"));
        assertTrue(output.json().contains("\"elapsedMillis\":"));
        assertTrue(output.json().contains("\"openMillis\":"));
        assertTrue(output.json().contains("\"nextMillis\":"));
        assertTrue(output.json().contains("\"closeMillis\":"));
    }

    private static String normalizeTiming(String value) {
        return value.replaceAll("(elapsedMillis|openMillis|nextMillis|closeMillis)(=|\":)\\d+", "$1$2<TIME>");
    }

    private static ExplainOutput analyze(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("explain analyze " + sql)) {
            return readExplain(statement);
        }
    }

    private static ExplainOutput explain(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("explain " + sql)) {
            return readExplain(statement);
        }
    }

    private static ExplainOutput readExplain(PreparedStatement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery()) {
            assertEquals(Types.CLOB, rs.getMetaData().getColumnType(1));
            assertEquals(Types.CLOB, rs.getMetaData().getColumnType(2));
            assertTrue(rs.next());
            ExplainOutput output = new ExplainOutput(rs.getString(1), rs.getString(2));
            assertFalse(rs.next());
            return output;
        }
    }

    private static void assertContains(ExplainOutput output, String... fragments) {
        for (String fragment : fragments) {
            assertTrue("missing EXPLAIN fragment " + fragment + " in:\n" + output.text(),
                    output.text().contains(fragment) || output.json().contains(fragment));
        }
    }

    private static void executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static String largeUnionSql(int branches) {
        StringBuilder sql = new StringBuilder(branches * 22);
        for (int i = 0; i < branches; i++) {
            if (i > 0) sql.append(" union all ");
            sql.append("values (").append(i).append(')');
        }
        return sql.toString();
    }

    private record ExplainOutput(String text, String json) {}
}
