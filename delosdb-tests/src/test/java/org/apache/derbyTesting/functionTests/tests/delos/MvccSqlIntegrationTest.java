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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

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


    public void testConcurrentMvccUpdatesRejectSecondWriterAndPreserveWinnerAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-write-conflict-update-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_conflict_update_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_conflict_update_t values (1, 'base')");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "update mvcc_conflict_update_t set name = 'from_a' where id = 1"));

            assertRows(reader,
                    "select id, name from mvcc_conflict_update_t where id = 1",
                    "1|base");

            assertWriteConflict(() -> executeUpdate(writerB,
                    "update mvcc_conflict_update_t set name = 'from_b' where id = 1"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, name from mvcc_conflict_update_t where id = 1",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_conflict_update_t where id = 1",
                    "1|from_a");
        }
    }

    public void testConcurrentMvccDeleteThenUpdateRejectsSecondWriterAndPreservesDeleteAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-write-conflict-delete-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_conflict_delete_update_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_conflict_delete_update_t values (1, 'base')");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "delete from mvcc_conflict_delete_update_t where id = 1"));

            assertRows(reader,
                    "select id, name from mvcc_conflict_delete_update_t where id = 1",
                    "1|base");

            assertWriteConflict(() -> executeUpdate(writerB,
                    "update mvcc_conflict_delete_update_t set name = 'from_b' where id = 1"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, name from mvcc_conflict_delete_update_t where id = 1");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_conflict_delete_update_t where id = 1");
        }
    }

    public void testConcurrentMvccUpdateThenDeleteRejectsSecondWriterAndPreservesUpdateAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-write-conflict-update-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_conflict_update_delete_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_conflict_update_delete_t values (1, 'base')");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "update mvcc_conflict_update_delete_t set name = 'from_a' where id = 1"));

            assertRows(reader,
                    "select id, name from mvcc_conflict_update_delete_t where id = 1",
                    "1|base");

            assertWriteConflict(() -> executeUpdate(writerB,
                    "delete from mvcc_conflict_update_delete_t where id = 1"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, name from mvcc_conflict_update_delete_t where id = 1",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_conflict_update_delete_t where id = 1",
                    "1|from_a");
        }
    }

    public void testReadCommittedMvccUpdateBecomesVisibleBetweenStatements() throws Exception {
        String databaseName = databaseName("mvcc-sql-read-committed-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rc_update_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rc_update_t values (1, 'before')");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rc_update_t where id = 1",
                    "1|before");

            assertEquals(1, executeUpdate(writer,
                    "update mvcc_rc_update_t set name = 'after' where id = 1"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rc_update_t where id = 1",
                    "1|after");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rc_update_t where id = 1",
                    "1|after");
        }
    }

    public void testReadCommittedMvccDeleteBecomesVisibleBetweenStatements() throws Exception {
        String databaseName = databaseName("mvcc-sql-read-committed-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rc_delete_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rc_delete_t values (1, 'survivor')");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rc_delete_t where id = 1",
                    "1|survivor");

            assertEquals(1, executeUpdate(writer,
                    "delete from mvcc_rc_delete_t where id = 1"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rc_delete_t where id = 1");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rc_delete_t where id = 1");
        }
    }

    public void testReadCommittedMvccInsertBecomesVisibleBetweenStatements() throws Exception {
        String databaseName = databaseName("mvcc-sql-read-committed-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rc_insert_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rc_insert_t where id = 1");

            assertEquals(1, executeUpdate(writer,
                    "insert into mvcc_rc_insert_t values (1, 'committed')"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rc_insert_t where id = 1",
                    "1|committed");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rc_insert_t where id = 1",
                    "1|committed");
        }
    }

    public void testRepeatableReadMvccUpdateKeepsStableSnapshotUntilTransactionEnds() throws Exception {
        String databaseName = databaseName("mvcc-sql-repeatable-read-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rr_update_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rr_update_t values (1, 'before')");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rr_update_t where id = 1",
                    "1|before");

            assertEquals(1, executeUpdate(writer,
                    "update mvcc_rr_update_t set name = 'after' where id = 1"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_update_t where id = 1",
                    "1|before");
            reader.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_update_t where id = 1",
                    "1|after");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rr_update_t where id = 1",
                    "1|after");
        }
    }

    public void testRepeatableReadMvccDeleteKeepsStableSnapshotUntilTransactionEnds() throws Exception {
        String databaseName = databaseName("mvcc-sql-repeatable-read-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rr_delete_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_rr_delete_t values (1, 'survivor')");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rr_delete_t where id = 1",
                    "1|survivor");

            assertEquals(1, executeUpdate(writer,
                    "delete from mvcc_rr_delete_t where id = 1"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_delete_t where id = 1",
                    "1|survivor");
            reader.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_delete_t where id = 1");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rr_delete_t where id = 1");
        }
    }

    public void testRepeatableReadMvccInsertKeepsStableSnapshotUntilTransactionEnds() throws Exception {
        String databaseName = databaseName("mvcc-sql-repeatable-read-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_rr_insert_t (id int, name varchar(32)) using delos_mvcc");
            connection.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_rr_insert_t where id = 1");

            assertEquals(1, executeUpdate(writer,
                    "insert into mvcc_rr_insert_t values (1, 'committed')"));
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_insert_t where id = 1");
            reader.commit();

            assertRows(reader,
                    "select id, name from mvcc_rr_insert_t where id = 1",
                    "1|committed");
            reader.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_rr_insert_t where id = 1",
                    "1|committed");
        }
    }


    public void testMvccPrimaryKeyRejectsDuplicateCommittedKeyAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_duplicate_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_duplicate_t values (1, 'first')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "insert into mvcc_pk_duplicate_t values (1, 'duplicate')"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_pk_duplicate_t order by id",
                    "1|first");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_duplicate_t order by id",
                    "1|first");
        }
    }

    public void testMvccPrimaryKeyRollbackAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-rollback-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_rollback_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_pk_rollback_t values (1, 'rolled-back')");
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_pk_rollback_t values (1, 'committed')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_pk_rollback_t order by id",
                    "1|committed");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_rollback_t order by id",
                    "1|committed");
        }
    }

    public void testMvccPrimaryKeyDeleteAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-delete-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_delete_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_delete_t values (1, 'old')");
            connection.commit();

            assertEquals(1, executeUpdate(connection, "delete from mvcc_pk_delete_t where id = 1"));
            executeUpdate(connection, "insert into mvcc_pk_delete_t values (1, 'new')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_pk_delete_t order by id",
                    "1|new");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_delete_t order by id",
                    "1|new");
        }
    }

    public void testMvccPrimaryKeyUpdateCannotCreateDuplicateKeyAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-pk-update-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_pk_update_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_pk_update_t values (1, 'one')");
            executeUpdate(connection, "insert into mvcc_pk_update_t values (2, 'two')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "update mvcc_pk_update_t set id = 1 where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_pk_update_t order by id",
                    "1|one",
                    "2|two");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_pk_update_t order by id",
                    "1|one",
                    "2|two");
        }
    }


    public void testMvccSecondaryIndexReflectsCommittedInsertUpdateAndDeleteAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-commit-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_commit_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_commit_tag_idx on mvcc_si_commit_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (2, 'red', 'two')");
            executeUpdate(connection, "insert into mvcc_si_commit_t values (3, 'blue', 'three')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id",
                    "1|one",
                    "3|three");

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_si_commit_t set tag = 'red' where id = 3"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'red' order by id",
                    "2|two",
                    "3|three");

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_si_commit_t where id = 1"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'blue' order by id");
            assertRows(reopened,
                    "select id, name from mvcc_si_commit_t --DERBY-PROPERTIES index=mvcc_si_commit_tag_idx\n where tag = 'red' order by id",
                    "2|two",
                    "3|three");
        }
    }

    public void testMvccSecondaryIndexRollbackRestoresIndexedVisibilityAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-rollback-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_rollback_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_rollback_tag_idx on mvcc_si_rollback_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_rollback_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_rollback_t values (2, 'red', 'two')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_si_rollback_t set tag = 'blue' where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_si_rollback_t where id = 1"));
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_si_rollback_t values (3, 'blue', 'three')");
            connection.rollback();

            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(connection,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'blue' order by id",
                    "1|one");
            assertRows(reopened,
                    "select id, name from mvcc_si_rollback_t --DERBY-PROPERTIES index=mvcc_si_rollback_tag_idx\n where tag = 'red' order by id",
                    "2|two");
        }
    }

    public void testMvccSecondaryIndexCanDriveDeleteAndUpdatePredicatesAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-secondary-index-write-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_si_write_t (id int, tag varchar(16), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_si_write_tag_idx on mvcc_si_write_t(tag)");
            executeUpdate(connection, "insert into mvcc_si_write_t values (1, 'blue', 'one')");
            executeUpdate(connection, "insert into mvcc_si_write_t values (2, 'blue', 'two')");
            executeUpdate(connection, "insert into mvcc_si_write_t values (3, 'red', 'three')");
            connection.commit();

            assertEquals(2, executeUpdate(connection,
                    "update mvcc_si_write_t set name = 'seen' where tag = 'blue'"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id",
                    "1|seen",
                    "2|seen");

            assertEquals(2, executeUpdate(connection,
                    "delete from mvcc_si_write_t where tag = 'blue'"));
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id");
            assertRows(connection,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'red' order by id",
                    "3|three");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'blue' order by id");
            assertRows(reopened,
                    "select id, name from mvcc_si_write_t --DERBY-PROPERTIES index=mvcc_si_write_tag_idx\n where tag = 'red' order by id",
                    "3|three");
        }
    }



    public void testMvccUniqueIndexRejectsDuplicateCommittedValueAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_duplicate_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_duplicate_email_idx on mvcc_unique_duplicate_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_duplicate_t values (1, 'a@example.com', 'alpha')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "insert into mvcc_unique_duplicate_t values (2, 'a@example.com', 'duplicate')"));
            connection.rollback();

            assertRows(connection,
                    "select id, email from mvcc_unique_duplicate_t --DERBY-PROPERTIES index=mvcc_unique_duplicate_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_duplicate_t order by id",
                    "1|alpha");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_duplicate_t --DERBY-PROPERTIES index=mvcc_unique_duplicate_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_duplicate_t order by id",
                    "1|alpha");
        }
    }

    public void testMvccUniqueIndexRollbackAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-rollback-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_rollback_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_rollback_email_idx on mvcc_unique_rollback_t(email)");
            connection.commit();

            executeUpdate(connection, "insert into mvcc_unique_rollback_t values (1, 'a@example.com', 'ghost')");
            connection.rollback();

            executeUpdate(connection, "insert into mvcc_unique_rollback_t values (2, 'a@example.com', 'alpha')");
            connection.commit();

            assertRows(connection,
                    "select id, email from mvcc_unique_rollback_t --DERBY-PROPERTIES index=mvcc_unique_rollback_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_rollback_t order by id",
                    "2|alpha");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_rollback_t --DERBY-PROPERTIES index=mvcc_unique_rollback_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_rollback_t order by id",
                    "2|alpha");
        }
    }

    public void testMvccUniqueIndexDeleteAllowsReinsertAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-delete-reinsert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_delete_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_delete_email_idx on mvcc_unique_delete_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_delete_t values (1, 'a@example.com', 'alpha')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_unique_delete_t where email = 'a@example.com'"));
            connection.commit();

            executeUpdate(connection, "insert into mvcc_unique_delete_t values (2, 'a@example.com', 'beta')");
            connection.commit();

            assertRows(connection,
                    "select id, email from mvcc_unique_delete_t --DERBY-PROPERTIES index=mvcc_unique_delete_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_delete_t order by id",
                    "2|beta");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_delete_t --DERBY-PROPERTIES index=mvcc_unique_delete_email_idx\n where email = 'a@example.com' order by id",
                    "2|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_delete_t order by id",
                    "2|beta");
        }
    }

    public void testMvccUniqueIndexUpdateCannotCreateDuplicateValueAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-unique-update-duplicate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_unique_update_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_unique_update_email_idx on mvcc_unique_update_t(email)");
            executeUpdate(connection, "insert into mvcc_unique_update_t values (1, 'a@example.com', 'alpha')");
            executeUpdate(connection, "insert into mvcc_unique_update_t values (2, 'b@example.com', 'beta')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(connection,
                    "update mvcc_unique_update_t set email = 'a@example.com' where id = 2"));
            connection.rollback();

            assertRows(connection,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(connection,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'b@example.com' order by id",
                    "2|b@example.com");
            assertRows(connection,
                    "select id, name from mvcc_unique_update_t order by id",
                    "1|alpha",
                    "2|beta");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, email from mvcc_unique_update_t --DERBY-PROPERTIES index=mvcc_unique_update_email_idx\n where email = 'b@example.com' order by id",
                    "2|b@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_unique_update_t order by id",
                    "1|alpha",
                    "2|beta");
        }
    }


    public void testConcurrentMvccPrimaryKeyInsertRejectsSecondWriterAndPreservesWinnerAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-pk-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_pk_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "insert into mvcc_concurrent_pk_t values (1, 'from_a')"));

            assertRows(reader,
                    "select id, name from mvcc_concurrent_pk_t where id = 1");

            assertDuplicateKeyOrWriteConflict(() -> executeUpdate(writerB,
                    "insert into mvcc_concurrent_pk_t values (1, 'from_b')"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, name from mvcc_concurrent_pk_t where id = 1",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_pk_t where id = 1",
                    "1|from_a");
        }
    }

    public void testConcurrentMvccUniqueIndexInsertRejectsSecondWriterAndPreservesWinnerAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-concurrent-unique-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_concurrent_unique_t (id int, email varchar(64), name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create unique index mvcc_concurrent_unique_email_idx on mvcc_concurrent_unique_t(email)");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            assertEquals(1, executeUpdate(writerA,
                    "insert into mvcc_concurrent_unique_t values (1, 'a@example.com', 'from_a')"));

            assertRows(reader,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id");

            assertDuplicateKeyOrWriteConflict(() -> executeUpdate(writerB,
                    "insert into mvcc_concurrent_unique_t values (2, 'a@example.com', 'from_b')"));
            rollbackAfterExpectedConflict(writerB);

            writerA.commit();

            assertRows(reader,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reader,
                    "select id, name from mvcc_concurrent_unique_t order by id",
                    "1|from_a");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email from mvcc_concurrent_unique_t --DERBY-PROPERTIES index=mvcc_concurrent_unique_email_idx\n where email = 'a@example.com' order by id",
                    "1|a@example.com");
            assertRows(reopened,
                    "select id, name from mvcc_concurrent_unique_t order by id",
                    "1|from_a");
        }
    }



    public void testDroppedMvccTableRecreateDoesNotSeeDroppedRowsAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-drop-recreate-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_drop_recreate_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_drop_recreate_t values (1, 'dropped')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "1|dropped");

            executeUpdate(connection, "drop table mvcc_drop_recreate_t");
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            executeUpdate(reopened, "create table mvcc_drop_recreate_t (id int primary key, name varchar(32)) using delos_mvcc");
            reopened.commit();

            assertRows(reopened,
                    "select id, name from mvcc_drop_recreate_t order by id");

            executeUpdate(reopened, "insert into mvcc_drop_recreate_t values (2, 'fresh')");
            reopened.commit();

            assertRows(reopened,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "2|fresh");
        }

        shutdownDatabase(databaseName);

        try (Connection reopenedAgain = openDatabase(databaseName, false)) {
            assertRows(reopenedAgain,
                    "select id, name from mvcc_drop_recreate_t order by id",
                    "2|fresh");
        }
    }

    public void testDroppedMvccTableRemovesInheritedStateFiles() throws Exception {
        String databaseName = databaseName("mvcc-sql-drop-cleanup-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_drop_cleanup_t (id int, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_drop_cleanup_t values (1, 'alpha')");
            executeUpdate(connection, "insert into mvcc_drop_cleanup_t values (2, 'beta')");
            connection.commit();

            assertTrue("expected delos_mvcc inherited-store files before DROP TABLE",
                    inheritedMvccStateFileCount(databaseName) > 0L);

            executeUpdate(connection, "drop table mvcc_drop_cleanup_t");
            connection.commit();
        }

        assertEquals("DROP TABLE should remove delos_mvcc inherited-store files",
                0L,
                inheritedMvccStateFileCount(databaseName));

        shutdownDatabase(databaseName);

        assertEquals("dropped delos_mvcc inherited-store files should not reappear after reopen",
                0L,
                inheritedMvccStateFileCount(databaseName));
    }


    public void testMvccSqlInPlaceCompressVacuumPrunesUpdateVersionsAndPreservesVisibleRowAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-vacuum-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_vacuum_update_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_vacuum_update_t values (1, 'v1')");
            connection.commit();

            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v2' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v3' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v4' where id = 1"));
            connection.commit();

            DelosStorageDiagnostics diagnostics = mvccDiagnostics();
            long containerId = mvccContainerId(connection, "MVCC_VACUUM_UPDATE_T");
            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected multiple MVCC physical versions before vacuum, got " + versionsBeforeVacuum,
                    versionsBeforeVacuum >= 4);

            inPlaceCompressTable(connection, "MVCC_VACUUM_UPDATE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained SQL snapshot is active",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should prune at least one superseded update version",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertTrue("vacuum should reduce MVCC physical version count",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsBeforeVacuum);
            assertEquals("vacuum must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, name from mvcc_vacuum_update_t order by id",
                    "1|v4");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_vacuum_update_t order by id",
                    "1|v4");
        }
    }

    public void testMvccSqlInPlaceCompressVacuumPrunesDeletedRowsAndPreservesSurvivorAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-vacuum-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_vacuum_delete_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (1, 'delete-me-1')");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (2, 'survivor')");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (3, 'delete-me-3')");
            connection.commit();

            assertEquals(2, executeUpdate(connection, "delete from mvcc_vacuum_delete_t where id in (1, 3)"));
            connection.commit();

            DelosStorageDiagnostics diagnostics = mvccDiagnostics();
            long containerId = mvccContainerId(connection, "MVCC_VACUUM_DELETE_T");
            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected deleted MVCC versions before vacuum, got " + versionsBeforeVacuum,
                    versionsBeforeVacuum >= 3);

            inPlaceCompressTable(connection, "MVCC_VACUUM_DELETE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained SQL snapshot is active",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should prune deleted physical versions",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertEquals("vacuum must preserve one logical survivor",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));
            assertTrue("vacuum should reduce MVCC physical version count",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsBeforeVacuum);

            assertRows(connection,
                    "select id, name from mvcc_vacuum_delete_t order by id",
                    "2|survivor");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_vacuum_delete_t order by id",
                    "2|survivor");
        }
    }





    public void testCommittedMvccInsertSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-commit-db");

        runCrashBoundaryWorker("commit-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_commit_t order by id",
                    "1|committed-before-halt");
        }
    }

    public void testUncommittedMvccInsertDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_uncommitted_t order by id");
        }
    }

    public void testCommittedHeapAndMvccTransactionSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-commit-db");

        runCrashBoundaryWorker("commit-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_commit_t order by id",
                    "1|heap-committed");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_commit_t order by id",
                    "1|mvcc-committed");
        }
    }

    public void testUncommittedHeapAndMvccTransactionDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_uncommitted_t order by id");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_uncommitted_t order by id");
        }
    }


    private static void runCrashBoundaryWorker(String scenario, String databaseName) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classpath);
        command.add(CrashBoundaryWorker.class.getName());
        command.add(scenario);
        command.add(databaseName);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals("Crash-boundary worker failed. Output:\n" + output, 0, exitCode);
    }

    public static final class CrashBoundaryWorker {
        public static void main(String[] args) {
            try {
                if (args.length != 2) {
                    throw new IllegalArgumentException("expected scenario and database name");
                }
                runScenario(args[0], args[1]);
                Runtime.getRuntime().halt(0);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                Runtime.getRuntime().halt(2);
            }
        }

        private static void runScenario(String scenario, String databaseName) throws Exception {
            switch (scenario) {
            case "commit-mvcc-insert":
                commitMvccInsert(databaseName);
                break;
            case "uncommitted-mvcc-insert":
                uncommittedMvccInsert(databaseName);
                break;
            case "commit-mixed-insert":
                commitMixedInsert(databaseName);
                break;
            case "uncommitted-mixed-insert":
                uncommittedMixedInsert(databaseName);
                break;
            default:
                throw new IllegalArgumentException("unknown crash-boundary scenario: " + scenario);
            }
        }

        private static void commitMvccInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_crash_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_crash_commit_t values (1, 'committed-before-halt')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }

        private static void uncommittedMvccInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_crash_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into mvcc_crash_uncommitted_t values (1, 'uncommitted-before-halt')");
                Runtime.getRuntime().halt(0);
            }
        }

        private static void commitMixedInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_crash_commit_t (id int primary key, name varchar(32))");
            executeUpdate(connection, "create table mvcc_crash_mixed_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
            executeUpdate(connection, "insert into heap_crash_commit_t values (1, 'heap-committed')");
            executeUpdate(connection, "insert into mvcc_crash_mixed_commit_t values (1, 'mvcc-committed')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }

        private static void uncommittedMixedInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table heap_crash_uncommitted_t (id int primary key, name varchar(32))");
                executeUpdate(connection, "create table mvcc_crash_mixed_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into heap_crash_uncommitted_t values (1, 'heap-uncommitted')");
                executeUpdate(connection, "insert into mvcc_crash_mixed_uncommitted_t values (1, 'mvcc-uncommitted')");
                Runtime.getRuntime().halt(0);
            }
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


    private interface SqlAction {
        void run() throws SQLException;
    }

    private static void assertWriteConflict(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected a deterministic MVCC write conflict");
        } catch (SQLException expected) {
            assertTrue("expected a Derby-wrapped MVCC write conflict, got: " + expected,
                    containsMessage(expected, "conflict")
                            || containsMessage(expected, "already deleted")
                            || containsMessage(expected, "not visible"));
        }
    }


    private static void assertDuplicateKey(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected duplicate-key violation");
        } catch (SQLException expected) {
            assertTrue("expected duplicate-key violation, got: " + expected,
                    "23505".equals(expected.getSQLState())
                            || containsMessage(expected, "duplicate")
                            || containsMessage(expected, "constraint")
                            || containsMessage(expected, "primary key"));
        }
    }


    private static void assertDuplicateKeyOrWriteConflict(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected duplicate-key violation or deterministic MVCC write conflict");
        } catch (SQLException expected) {
            assertTrue("expected duplicate-key violation or deterministic MVCC write conflict, got: " + expected,
                    "23505".equals(expected.getSQLState())
                            || containsMessage(expected, "duplicate")
                            || containsMessage(expected, "constraint")
                            || containsMessage(expected, "primary key")
                            || containsMessage(expected, "conflict")
                            || containsMessage(expected, "not visible")
                            || containsMessage(expected, "lock"));
        }
    }


    private static void rollbackAfterExpectedConflict(Connection connection) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            if (!"08003".equals(e.getSQLState())) {
                throw e;
            }
        }
    }


    private static DelosStorageDiagnostics mvccDiagnostics() {
        return DelosStorageDiagnosticsRegistry.mvcc();
    }

    private static long mvccContainerId(Connection connection, String tableName) throws SQLException {
        String sql = "select c.conglomeratenumber "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid "
                + "and t.schemaid = s.schemaid "
                + "and s.schemaname = 'APP' "
                + "and t.tablename = ? "
                + "and c.isindex = false";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected MVCC base conglomerate for table " + tableName, rs.next());
                long containerId = rs.getLong(1);
                assertFalse("expected one MVCC base conglomerate for table " + tableName, rs.next());
                return containerId;
            }
        }
    }

    private static void inPlaceCompressTable(Connection connection, String tableName) throws SQLException {
        executeStatement(connection, "call syscs_util.syscs_inplace_compress_table('APP', '"
                + tableName
                + "', 1, 0, 0)");
    }

    private static void executeStatement(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }




    private static long inheritedMvccStateFileCount(String databaseName) throws IOException {
        Path inheritedStore = new File(databaseName).toPath()
                .resolve("delos_mvcc")
                .resolve("inherited-store");
        if (!Files.exists(inheritedStore)) {
            return 0L;
        }
        try (var paths = Files.walk(inheritedStore)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static boolean containsMessage(Throwable throwable, String needle) {
        String lowerNeedle = needle.toLowerCase();
        for (Throwable current = throwable; current != null; current = nextThrowable(current)) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains(lowerNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static Throwable nextThrowable(Throwable throwable) {
        if (throwable instanceof SQLException sqlException && sqlException.getNextException() != null) {
            return sqlException.getNextException();
        }
        return throwable.getCause();
    }

    private static String databaseName(String name) {
        return new File(name).getPath();
    }
}
