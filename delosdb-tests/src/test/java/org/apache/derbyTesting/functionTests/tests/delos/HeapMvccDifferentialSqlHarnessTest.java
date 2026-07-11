/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapMvccDifferentialSqlHarnessTest

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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/**
 * Reusable SQL-differential gate between the inherited heap provider and the
 * opt-in delos_mvcc provider. The harness intentionally keeps the SQL surface
 * narrow and explicit: only statements whose heap/MVCC semantics should match
 * are registered here.
 */
public final class HeapMvccDifferentialSqlHarnessTest extends MvccSqlTestSupport {
    private static final String HEAP_TABLE = "heap_diff_sql_t";
    private static final String MVCC_TABLE = "mvcc_diff_sql_t";
    private static final String HEAP_REFERENCE_TABLE = "heap_diff_sql_ref";
    private static final String MVCC_REFERENCE_TABLE = "mvcc_diff_sql_ref";

    public void testHeapAndMvccProduceIdenticalResultsForSupportedSqlSurface() throws Exception {
        String databaseName = databaseName("heap-mvcc-differential-sql-harness-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long mvccContainerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            createTables(connection);
            DifferentialHarness harness = new DifferentialHarness(
                    connection, HEAP_TABLE, MVCC_TABLE, HEAP_REFERENCE_TABLE, MVCC_REFERENCE_TABLE);

            insertFixtureRows(connection, harness);
            insertReferenceRows(connection, harness);
            connection.commit();
            mvccContainerId = mvccContainerId(connection, "MVCC_DIFF_SQL_T");
            assertMvccConsistent(diagnostics, mvccContainerId);

            harness.assertCheckpoint("initial committed fixture");
            assertMvccRuntimeStatisticsAvailable(connection);

            Savepoint rollbackPoint = connection.setSavepoint("DIFF_SQL_ROLLBACK_POINT");
            harness.executeUpdate("update ${table} set quantity = quantity + 900, "
                    + "status = 'ROLLBACK', note = 'rolled-back' where id in (1, 2)");
            harness.executeUpdate("delete from ${table} where id = 4");
            harness.executeReferenceUpdate("update ${ref} set label = 'rolled-back' where status = 'READY'");
            harness.insertRow(7, "eta", 70, "ROLLBACK", "7.07", "rolled-back", "2026-01-07");
            harness.assertCheckpoint("inside rollback-only mutation");
            connection.rollback(rollbackPoint);

            harness.assertCheckpoint("after rollback to fixture");
            assertMvccConsistent(diagnostics, mvccContainerId);

            harness.executeUpdate("update ${table} set quantity = quantity + 5, "
                    + "amount = amount + 10, note = 'alpha committed' where id = 1");
            harness.executeUpdate("update ${table} set code = 'beta-u', status = 'READY' where id = 2");
            harness.executeUpdate("delete from ${table} where id = 3");
            harness.executeUpdate("delete from ${table} where id = 4");
            harness.insertRow(4, "delta-r", 44, "READY", null, repeatedNote("delta-reinsert", 1000), null);
            harness.insertRow(8, "theta", 80, "READY", "8.80", repeatedNote("theta", 768), "2026-02-08");
            harness.insertRow(9, "iota", 80, "READY", "9.90", null, "2026-02-09");
            harness.executeUpdate("update ${table} set status = 'DONE', quantity = 25, "
                    + "created_on = null where id = 6");
            harness.executeUpdate("update ${table} set note = null where id = 5");
            harness.executeReferenceUpdate("update ${ref} set label = 'ready-current' where status = 'READY'");
            connection.commit();

            harness.assertCheckpoint("after committed indexed updates, delete/reinsert, and overflow rows");
            assertIndexedLookupMatches(connection, HEAP_TABLE, "beta-u", 2);
            assertIndexedLookupMatches(connection, MVCC_TABLE, "beta-u", 2);
            assertMvccConsistent(diagnostics, mvccContainerId);

            inPlaceCompressTable(connection, "HEAP_DIFF_SQL_T");
            inPlaceCompressTable(connection, "MVCC_DIFF_SQL_T");
            connection.commit();
            harness.assertCheckpoint("after provider maintenance");
            assertMvccConsistent(diagnostics, mvccContainerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            DifferentialHarness reopenedHarness = new DifferentialHarness(
                    reopened, HEAP_TABLE, MVCC_TABLE, HEAP_REFERENCE_TABLE, MVCC_REFERENCE_TABLE);
            reopenedHarness.assertCheckpoint("after shutdown and reopen");
            assertIndexedLookupMatches(reopened, HEAP_TABLE, "beta-u", 2);
            assertIndexedLookupMatches(reopened, MVCC_TABLE, "beta-u", 2);
            assertMvccConsistent(diagnostics, mvccContainerId(reopened, "MVCC_DIFF_SQL_T"));
        }
    }

    private static void assertMvccRuntimeStatisticsAvailable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("call syscs_util.syscs_set_runtimestatistics(1)");
            try (ResultSet resultSet = statement.executeQuery(
                    "select id, code from " + MVCC_TABLE + " where quantity >= 20 order by id")) {
                while (resultSet.next()) {
                    // Drain the scan so Derby requests final scan statistics.
                }
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "values syscs_util.syscs_get_runtimestatistics()")) {
                assertTrue("expected one runtime-statistics row", resultSet.next());
                String runtimeStatistics = resultSet.getString(1);
                assertNotNull("MVCC runtime statistics must not be null", runtimeStatistics);
                assertTrue("MVCC runtime statistics must describe the executed scan",
                        runtimeStatistics.length() > 0);
                assertFalse("expected one runtime-statistics row", resultSet.next());
            }
            statement.execute("call syscs_util.syscs_set_runtimestatistics(0)");
        }
    }

    private static void createTables(Connection connection) throws SQLException {
        executeUpdate(connection, "create table " + HEAP_TABLE + " ("
                + "id int not null primary key, "
                + "code varchar(32) not null unique, "
                + "quantity int not null, "
                + "status varchar(16) not null, "
                + "amount decimal(10,2), "
                + "note varchar(1024), "
                + "created_on date)");
        executeUpdate(connection, "create index heap_diff_status_qty_idx on "
                + HEAP_TABLE + "(status, quantity)");
        executeUpdate(connection, "create index heap_diff_created_idx on "
                + HEAP_TABLE + "(created_on)");

        executeUpdate(connection, "create table " + MVCC_TABLE + " ("
                + "id int not null primary key, "
                + "code varchar(32) not null unique, "
                + "quantity int not null, "
                + "status varchar(16) not null, "
                + "amount decimal(10,2), "
                + "note varchar(1024), "
                + "created_on date) using delos_mvcc");
        executeUpdate(connection, "create index mvcc_diff_status_qty_idx on "
                + MVCC_TABLE + "(status, quantity)");
        executeUpdate(connection, "create index mvcc_diff_created_idx on "
                + MVCC_TABLE + "(created_on)");

        executeUpdate(connection, "create table " + HEAP_REFERENCE_TABLE + " ("
                + "status varchar(16) not null primary key, "
                + "label varchar(64) not null, "
                + "rank_value int not null)");
        executeUpdate(connection, "create table " + MVCC_REFERENCE_TABLE + " ("
                + "status varchar(16) not null primary key, "
                + "label varchar(64) not null, "
                + "rank_value int not null) using delos_mvcc");
    }

    private static void insertFixtureRows(Connection connection, DifferentialHarness harness) throws SQLException {
        harness.insertRow(1, "alpha", 10, "READY", "1.10", "alpha initial", "2026-01-01");
        harness.insertRow(2, "beta", 20, "PENDING", "2.20", repeatedNote("beta", 300), "2026-01-02");
        harness.insertRow(3, "gamma", 30, "READY", null, null, "2026-01-03");
        harness.insertRow(4, "delta", 40, "DONE", "4.40", "delta note", null);
        harness.insertRow(5, "epsilon", 50, "READY", "5.50", repeatedNote("epsilon", 512), "2026-01-05");
        harness.insertRow(6, "zeta", 60, "PENDING", "6.60", "zeta note", "2026-01-06");
        assertEquals("fixture row count should match for heap",
                6L, countRows(connection, HEAP_TABLE));
        assertEquals("fixture row count should match for MVCC",
                6L, countRows(connection, MVCC_TABLE));
    }

    private static void insertReferenceRows(Connection connection, DifferentialHarness harness) throws SQLException {
        harness.insertReferenceRow("READY", "ready", 1);
        harness.insertReferenceRow("PENDING", "pending", 2);
        harness.insertReferenceRow("DONE", "done", 3);
        harness.insertReferenceRow("ROLLBACK", "rollback", 4);
        assertEquals("reference row count should match for heap",
                4L, countRows(connection, HEAP_REFERENCE_TABLE));
        assertEquals("reference row count should match for MVCC",
                4L, countRows(connection, MVCC_REFERENCE_TABLE));
    }

    private static long countRows(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from " + tableName)) {
            assertTrue("expected count row for " + tableName, rs.next());
            return rs.getLong(1);
        }
    }

    private static void assertIndexedLookupMatches(Connection connection, String tableName, String code, int expectedId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from " + tableName + " where code = ?")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected indexed lookup row for " + tableName, rs.next());
                assertEquals(expectedId, rs.getInt(1));
                assertFalse("expected exactly one indexed lookup row for " + tableName, rs.next());
            }
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        diagnostics.assertConsistentForTesting(0, containerId);
        assertEquals("expected no MVCC consistency errors", 0,
                diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
    }

    private static String repeatedNote(String seed, int length) {
        StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            builder.append(seed).append('-').append(builder.length()).append(';');
        }
        return builder.substring(0, length);
    }

    private static final class DifferentialHarness {
        private final Connection connection;
        private final String heapTable;
        private final String mvccTable;
        private final String heapReferenceTable;
        private final String mvccReferenceTable;
        private final List<QueryProbe> probes;

        DifferentialHarness(
                Connection connection,
                String heapTable,
                String mvccTable,
                String heapReferenceTable,
                String mvccReferenceTable) {
            this.connection = connection;
            this.heapTable = heapTable;
            this.mvccTable = mvccTable;
            this.heapReferenceTable = heapReferenceTable;
            this.mvccReferenceTable = mvccReferenceTable;
            this.probes = List.of(
                    new QueryProbe("full ordered projection",
                            "select id, code, quantity, status, amount, note, created_on "
                                    + "from ${table} order by id"),
                    new QueryProbe("status aggregate",
                            "select status, count(*), sum(quantity), min(id), max(id) "
                                    + "from ${table} group by status order by status"),
                    new QueryProbe("range predicate using secondary index shape",
                            "select id, code, quantity, status from ${table} "
                                    + "where status in ('READY', 'PENDING') "
                                    + "and quantity between 15 and 85 order by status, quantity, id"),
                    new QueryProbe("nullable predicate",
                            "select id, code from ${table} where note is null order by id"),
                    new QueryProbe("date predicate",
                            "select id, code, created_on from ${table} "
                                    + "where created_on >= date('2026-01-02') order by created_on, id"),
                    new QueryProbe("scalar aggregate",
                            "select count(*), sum(quantity), min(quantity), max(quantity) from ${table}"),
                    new QueryProbe("unique lookup",
                            "select id, code, status from ${table} "
                                    + "where code = 'beta-u' or code = 'beta' order by id"),
                    new QueryProbe("composite equality and bounded range",
                            "select id, code, status, quantity from ${table} "
                                    + "where status = 'READY' and quantity between 40 and 80 "
                                    + "order by quantity, id"),
                    new QueryProbe("explicit null ordering",
                            "select id, code, amount, created_on from ${table} "
                                    + "order by case when created_on is null then 1 else 0 end, created_on, id"),
                    new QueryProbe("distinct indexed values",
                            "select distinct status, quantity from ${table} order by status, quantity"),
                    new QueryProbe("top n ordered rows",
                            "select id, code, quantity from ${table} "
                                    + "order by quantity desc, id fetch first 4 rows only"),
                    new QueryProbe("join with provider-matched reference table",
                            "select t.id, t.code, r.label, r.rank_value from ${table} t "
                                    + "join ${ref} r on r.status = t.status "
                                    + "where t.quantity >= 20 order by r.rank_value, t.id"),
                    new QueryProbe("aggregate having",
                            "select status, count(*), sum(quantity) from ${table} "
                                    + "group by status having count(*) >= 2 order by status"),
                    new QueryProbe("correlated existence",
                            "select t.id, t.code from ${table} t where exists "
                                    + "(select 1 from ${ref} r where r.status = t.status and r.rank_value <= 2) "
                                    + "order by t.id"),
                    new QueryProbe("nullable expression and large-value length",
                            "select id, coalesce(note, '<NULL>'), length(note), "
                                    + "case when amount is null then 0 else 1 end "
                                    + "from ${table} order by id"));
        }

        void insertRow(
                int id,
                String code,
                int quantity,
                String status,
                String amount,
                String note,
                String createdOn) throws SQLException {
            insertInto(heapTable, id, code, quantity, status, amount, note, createdOn);
            insertInto(mvccTable, id, code, quantity, status, amount, note, createdOn);
        }

        void insertReferenceRow(String status, String label, int rankValue) throws SQLException {
            insertReferenceInto(heapReferenceTable, status, label, rankValue);
            insertReferenceInto(mvccReferenceTable, status, label, rankValue);
        }

        void executeReferenceUpdate(String sqlTemplate) throws SQLException {
            int heapCount = executeSingleUpdate(sql(sqlTemplate, heapTable, heapReferenceTable));
            int mvccCount = executeSingleUpdate(sql(sqlTemplate, mvccTable, mvccReferenceTable));
            assertEquals("heap/MVCC reference update count mismatch for " + sqlTemplate, heapCount, mvccCount);
        }

        void executeUpdate(String sqlTemplate) throws SQLException {
            int heapCount = executeSingleUpdate(sql(sqlTemplate, heapTable, heapReferenceTable));
            int mvccCount = executeSingleUpdate(sql(sqlTemplate, mvccTable, mvccReferenceTable));
            assertEquals("heap/MVCC update count mismatch for " + sqlTemplate, heapCount, mvccCount);
        }

        private int executeSingleUpdate(String sql) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        }

        void assertCheckpoint(String checkpointName) throws SQLException {
            for (QueryProbe probe : probes) {
                List<String> heapRows = rows(sql(probe.sqlTemplate, heapTable, heapReferenceTable));
                List<String> mvccRows = rows(sql(probe.sqlTemplate, mvccTable, mvccReferenceTable));
                assertEquals("heap/MVCC differential mismatch at checkpoint '"
                        + checkpointName + "' for probe '" + probe.name + "' using SQL "
                        + probe.sqlTemplate, heapRows, mvccRows);
            }
        }

        private void insertInto(
                String tableName,
                int id,
                String code,
                int quantity,
                String status,
                String amount,
                String note,
                String createdOn) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into " + tableName + " values (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setInt(1, id);
                statement.setString(2, code);
                statement.setInt(3, quantity);
                statement.setString(4, status);
                if (amount == null) {
                    statement.setNull(5, java.sql.Types.DECIMAL);
                } else {
                    statement.setBigDecimal(5, new BigDecimal(amount));
                }
                if (note == null) {
                    statement.setNull(6, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(6, note);
                }
                if (createdOn == null) {
                    statement.setNull(7, java.sql.Types.DATE);
                } else {
                    statement.setDate(7, Date.valueOf(createdOn));
                }
                assertEquals("expected one inserted row for " + tableName, 1, statement.executeUpdate());
            }
        }

        private void insertReferenceInto(
                String tableName, String status, String label, int rankValue) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into " + tableName + " values (?, ?, ?)")) {
                statement.setString(1, status);
                statement.setString(2, label);
                statement.setInt(3, rankValue);
                assertEquals("expected one inserted reference row for " + tableName,
                        1, statement.executeUpdate());
            }
        }

        private List<String> rows(String sql) throws SQLException {
            List<String> rows = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int column = 1; column <= columnCount; column++) {
                        if (column > 1) {
                            row.append('|');
                        }
                        row.append(normalizedValue(rs, column));
                    }
                    rows.add(row.toString());
                }
            }
            return rows;
        }

        private static String normalizedValue(ResultSet rs, int column) throws SQLException {
            Object value = rs.getObject(column);
            if (value == null) {
                return "NULL";
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.toPlainString();
            }
            if (value instanceof Date date) {
                return date.toString();
            }
            String text = value.toString();
            if (text.length() <= 96) {
                return text;
            }
            return text.length() + ":" + text.substring(0, 32) + ":" + text.substring(text.length() - 32);
        }

        private static String sql(String template, String tableName, String referenceTableName) {
            return template.replace("${table}", tableName).replace("${ref}", referenceTableName);
        }
    }

    private static final class QueryProbe {
        private final String name;
        private final String sqlTemplate;

        QueryProbe(String name, String sqlTemplate) {
            this.name = name;
            this.sqlTemplate = sqlTemplate;
        }
    }
}
