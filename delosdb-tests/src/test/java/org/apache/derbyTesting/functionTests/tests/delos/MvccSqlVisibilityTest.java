/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlVisibilityTest

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

/** SQL integration tests for delos_mvcc visibility behavior. */
public final class MvccSqlVisibilityTest extends MvccSqlTestSupport {
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


}
