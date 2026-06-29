/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlIntegrationTest

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;

/** Real Derby/JDBC proof for the opt-in delos_mvcc storage provider. */
public final class MvccSqlIntegrationTest extends TestCase {
    public void testCommittedMvccTableSurvivesDatabaseShutdownAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_commit_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_commit_t values (1, 'alpha')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_commit_t",
                    "1|alpha");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_commit_t",
                    "1|alpha");
        }
    }

    public void testRolledBackMvccInsertDoesNotSurviveDatabaseReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rollback_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_rollback_t values (1, 'ghost')");
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_rollback_t");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rollback_t");
        }
    }

    public void testCommittedMvccUpdateSurvivesDatabaseShutdownAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-update-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_update_commit_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_update_commit_t values (1, 'before')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_update_commit_t set name = 'after' where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_update_commit_t where id = 1",
                    "1|after");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_update_commit_t where id = 1",
                    "1|after");
        }
    }

    public void testRolledBackMvccUpdateRestoresCommittedVersionAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-update-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_update_rollback_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_update_rollback_t values (1, 'before')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_update_rollback_t set name = 'after' where id = 1"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_update_rollback_t where id = 1",
                    "1|before");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_update_rollback_t where id = 1",
                    "1|before");
        }
    }

    public void testCommittedMvccDeleteSurvivesDatabaseShutdownAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-delete-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_delete_commit_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_delete_commit_t values (1, 'doomed')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_delete_commit_t where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_delete_commit_t where id = 1");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_delete_commit_t where id = 1");
        }
    }

    public void testRolledBackMvccDeleteRestoresCommittedRowAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-delete-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_delete_rollback_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_delete_rollback_t values (1, 'survivor')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_delete_rollback_t where id = 1"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_delete_rollback_t where id = 1",
                    "1|survivor");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_delete_rollback_t where id = 1",
                    "1|survivor");
        }
    }

    public void testTwoMvccTablesCommitInOneSqlTransactionSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-two-table-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_tx_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_tx_b (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_tx_a values (1, 'left')");
            executeUpdate(connection, "insert into mvcc_tx_b values (1, 'right')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_tx_a order by id",
                    "1|left");
            assertRows(connection,
                    "select id, name from mvcc_tx_b order by id",
                    "1|right");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_tx_a order by id",
                    "1|left");
            assertRows(reopened,
                    "select id, name from mvcc_tx_b order by id",
                    "1|right");
        }
    }

    public void testTwoMvccTablesRollbackInOneSqlTransactionLeavesBothEmptyAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-two-table-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_tx_rb_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_tx_rb_b (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_tx_rb_a values (1, 'ghost-left')");
            executeUpdate(connection, "insert into mvcc_tx_rb_b values (1, 'ghost-right')");
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_tx_rb_a order by id");
            assertRows(connection,
                    "select id, name from mvcc_tx_rb_b order by id");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_tx_rb_a order by id");
            assertRows(reopened,
                    "select id, name from mvcc_tx_rb_b order by id");
        }
    }

    public void testTwoMvccTablesUpdateAndDeleteCommitInOneSqlTransactionSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-two-table-update-delete-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_tx_ud_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_tx_ud_b (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_tx_ud_a values (1, 'before')");
            executeUpdate(connection, "insert into mvcc_tx_ud_b values (1, 'doomed')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_tx_ud_a set name = 'after' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_tx_ud_b where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_tx_ud_a order by id",
                    "1|after");
            assertRows(connection,
                    "select id, name from mvcc_tx_ud_b order by id");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_tx_ud_a order by id",
                    "1|after");
            assertRows(reopened,
                    "select id, name from mvcc_tx_ud_b order by id");
        }
    }

    public void testTwoMvccTablesUpdateAndDeleteRollbackInOneSqlTransactionRestoresBothAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-two-table-update-delete-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_tx_ud_rb_a (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_tx_ud_rb_b (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_tx_ud_rb_a values (1, 'before')");
            executeUpdate(connection, "insert into mvcc_tx_ud_rb_b values (1, 'survivor')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_tx_ud_rb_a set name = 'after' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_tx_ud_rb_b where id = 1"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_tx_ud_rb_a order by id",
                    "1|before");
            assertRows(connection,
                    "select id, name from mvcc_tx_ud_rb_b order by id",
                    "1|survivor");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_tx_ud_rb_a order by id",
                    "1|before");
            assertRows(reopened,
                    "select id, name from mvcc_tx_ud_rb_b order by id",
                    "1|survivor");
        }
    }


    public void testHeapAndMvccTablesCommitInOneSqlTransactionSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-mixed-heap-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_mixed_commit_t (id int, name varchar(32))");
            executeUpdate(connection, "create table mvcc_mixed_commit_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into heap_mixed_commit_t values (1, 'heap')");
            executeUpdate(connection, "insert into mvcc_mixed_commit_t values (1, 'mvcc')");
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_mixed_commit_t order by id",
                    "1|heap");
            assertRows(connection,
                    "select id, name from mvcc_mixed_commit_t order by id",
                    "1|mvcc");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_mixed_commit_t order by id",
                    "1|heap");
            assertRows(reopened,
                    "select id, name from mvcc_mixed_commit_t order by id",
                    "1|mvcc");
        }
    }

    public void testHeapAndMvccTablesRollbackInOneSqlTransactionLeavesBothEmptyAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-mixed-heap-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_mixed_rollback_t (id int, name varchar(32))");
            executeUpdate(connection, "create table mvcc_mixed_rollback_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into heap_mixed_rollback_t values (1, 'ghost-heap')");
            executeUpdate(connection, "insert into mvcc_mixed_rollback_t values (1, 'ghost-mvcc')");
            connection.rollback();

            assertRows(connection,
                    "select id, name from heap_mixed_rollback_t order by id");
            assertRows(connection,
                    "select id, name from mvcc_mixed_rollback_t order by id");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_mixed_rollback_t order by id");
            assertRows(reopened,
                    "select id, name from mvcc_mixed_rollback_t order by id");
        }
    }

    public void testHeapAndMvccTablesUpdateAndDeleteCommitInOneSqlTransactionSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-mixed-heap-update-delete-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_mixed_ud_t (id int, name varchar(32))");
            executeUpdate(connection, "create table mvcc_mixed_ud_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into heap_mixed_ud_t values (1, 'heap-before')");
            executeUpdate(connection, "insert into mvcc_mixed_ud_t values (1, 'mvcc-survivor')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update heap_mixed_ud_t set name = 'heap-after' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_mixed_ud_t where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_mixed_ud_t order by id",
                    "1|heap-after");
            assertRows(connection,
                    "select id, name from mvcc_mixed_ud_t order by id");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_mixed_ud_t order by id",
                    "1|heap-after");
            assertRows(reopened,
                    "select id, name from mvcc_mixed_ud_t order by id");
        }
    }

    public void testHeapAndMvccTablesUpdateAndDeleteRollbackInOneSqlTransactionRestoresBothAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-mixed-heap-update-delete-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_mixed_ud_rb_t (id int, name varchar(32))");
            executeUpdate(connection, "create table mvcc_mixed_ud_rb_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into heap_mixed_ud_rb_t values (1, 'heap-before')");
            executeUpdate(connection, "insert into mvcc_mixed_ud_rb_t values (1, 'mvcc-survivor')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update heap_mixed_ud_rb_t set name = 'heap-after' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_mixed_ud_rb_t where id = 1"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from heap_mixed_ud_rb_t order by id",
                    "1|heap-before");
            assertRows(connection,
                    "select id, name from mvcc_mixed_ud_rb_t order by id",
                    "1|mvcc-survivor");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_mixed_ud_rb_t order by id",
                    "1|heap-before");
            assertRows(reopened,
                    "select id, name from mvcc_mixed_ud_rb_t order by id",
                    "1|mvcc-survivor");
        }
    }

    public void testUncommittedMvccUpdateIsInvisibleAcrossSqlConnections() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-update-visibility-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_update_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_concurrent_update_t values (1, 'before')");
            connection.commit();
        }

        try (Connection writer = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);

            assertEquals(1, executeUpdate(writer,
                    "update mvcc_concurrent_update_t set name = 'after' where id = 1"));

            assertRows(reader,
                    "select id, name from mvcc_concurrent_update_t where id = 1",
                    "1|before");

            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_concurrent_update_t where id = 1",
                    "1|after");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_update_t where id = 1",
                    "1|after");
        }
    }

    public void testUncommittedMvccDeleteIsInvisibleAcrossSqlConnections() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-delete-visibility-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_delete_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_concurrent_delete_t values (1, 'survivor')");
            connection.commit();
        }

        try (Connection writer = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);

            assertEquals(1, executeUpdate(writer,
                    "delete from mvcc_concurrent_delete_t where id = 1"));

            assertRows(reader,
                    "select id, name from mvcc_concurrent_delete_t where id = 1",
                    "1|survivor");

            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_concurrent_delete_t where id = 1");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_delete_t where id = 1");
        }
    }

    public void testUncommittedMvccInsertIsInvisibleAcrossSqlConnections() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-insert-visibility-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_insert_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();
        }

        try (Connection writer = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);

            assertEquals(1, executeUpdate(writer,
                    "insert into mvcc_concurrent_insert_t values (1, 'pending')"));

            assertRows(reader,
                    "select id, name from mvcc_concurrent_insert_t where id = 1");

            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_concurrent_insert_t where id = 1",
                    "1|pending");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_insert_t where id = 1",
                    "1|pending");
        }
    }





    private static Connection openDatabase(String databaseName, boolean create) throws SQLException {
        return DriverManager.getConnection("jdbc:derby:" + databaseName + (create ? ";create=true" : ""));
    }

    private static void shutdownDatabase(String databaseName) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + databaseName + ";shutdown=true");
            fail("Database shutdown should throw the normal Derby shutdown exception");
        } catch (SQLException e) {
            if (!"08006".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void assertRows(Connection connection, String sql, String... expectedRows) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            int columnCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columnCount; i++) {
                    if (i > 1) {
                        row.append('|');
                    }
                    row.append(rs.getString(i));
                }
                rows.add(row.toString());
            }
        }
        assertEquals(List.of(expectedRows), rows);
    }

    private static String databaseName(String name) {
        return new File(name).getPath();
    }
}
