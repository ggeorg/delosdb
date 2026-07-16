/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlSavepointTest

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
import java.sql.Savepoint;

/** SQL integration tests for delos_mvcc savepoint behavior. */
public final class MvccSqlSavepointTest extends MvccSqlTestSupport {
    public void testMvccInsertAfterSavepointRollsBackAndSurvivesReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-savepoint-insert-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_sp_insert_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_sp_insert_t values (1, 'before')");

            Savepoint savepoint = connection.setSavepoint("S1");
            executeUpdate(connection, "insert into mvcc_sp_insert_t values (2, 'after')");
            assertRows(connection,
                    "select id, name from mvcc_sp_insert_t order by id",
                    "1|before",
                    "2|after");

            connection.rollback(savepoint);
            assertRows(connection,
                    "select id, name from mvcc_sp_insert_t order by id",
                    "1|before");
            connection.commit();
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_sp_insert_t order by id",
                    "1|before");
        }
    }

    public void testMvccUpdateAndDeleteAfterSavepointRollBackAndSurviveReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-savepoint-update-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_sp_update_delete_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_sp_update_delete_t values (1, 'one')");
            executeUpdate(connection, "insert into mvcc_sp_update_delete_t values (2, 'two')");
            connection.commit();

            Savepoint savepoint = connection.setSavepoint("S1");
            assertEquals(1, executeUpdate(connection,
                    "update mvcc_sp_update_delete_t set name = 'changed' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_sp_update_delete_t where id = 2"));
            assertRows(connection,
                    "select id, name from mvcc_sp_update_delete_t order by id",
                    "1|changed");

            connection.rollback(savepoint);
            assertRows(connection,
                    "select id, name from mvcc_sp_update_delete_t order by id",
                    "1|one",
                    "2|two");
            connection.commit();
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_sp_update_delete_t order by id",
                    "1|one",
                    "2|two");
        }
    }

    public void testMvccPrimaryAndUniqueKeyInsertAfterSavepointRollbackCanBeReused() throws Exception {
        String databaseName = databaseName("mvcc-sql-savepoint-key-reuse-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_sp_key_t (id int primary key, email varchar(64) unique, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_sp_key_t values (1, 'a@example.com', 'before')");
            connection.commit();

            Savepoint savepoint = connection.setSavepoint("S1");
            executeUpdate(connection, "insert into mvcc_sp_key_t values (2, 'b@example.com', 'rolled-back')");
            connection.rollback(savepoint);

            executeUpdate(connection, "insert into mvcc_sp_key_t values (2, 'b@example.com', 'committed')");
            connection.commit();

            assertRows(connection,
                    "select id, email, name from mvcc_sp_key_t order by id",
                    "1|a@example.com|before",
                    "2|b@example.com|committed");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, email, name from mvcc_sp_key_t order by id",
                    "1|a@example.com|before",
                    "2|b@example.com|committed");
        }
    }

    public void testHeapAndMvccSavepointsRemainSupportedInIndependentTransactions() throws Exception {
        String databaseName = databaseName("mvcc-sql-savepoint-independent-providers-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table heap_sp_t (id int primary key, name varchar(32))");
            executeUpdate(setup, "create table mvcc_sp_mixed_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into heap_sp_t values (1, 'heap-before')");
            setup.commit();
            executeUpdate(setup, "insert into mvcc_sp_mixed_t values (1, 'mvcc-before')");
            setup.commit();
        }

        try (Connection heapConnection = openDatabase(databaseName, false);
             Connection mvccConnection = openDatabase(databaseName, false)) {
            heapConnection.setAutoCommit(false);
            mvccConnection.setAutoCommit(false);

            Savepoint heapSavepoint = heapConnection.setSavepoint("HEAP_S1");
            Savepoint mvccSavepoint = mvccConnection.setSavepoint("MVCC_S1");
            executeUpdate(heapConnection, "insert into heap_sp_t values (2, 'heap-after')");
            executeUpdate(mvccConnection, "insert into mvcc_sp_mixed_t values (2, 'mvcc-after')");
            heapConnection.rollback(heapSavepoint);
            mvccConnection.rollback(mvccSavepoint);
            heapConnection.commit();
            mvccConnection.commit();

            assertRows(heapConnection,
                    "select id, name from heap_sp_t order by id",
                    "1|heap-before");
            assertRows(mvccConnection,
                    "select id, name from mvcc_sp_mixed_t order by id",
                    "1|mvcc-before");
            heapConnection.rollback();
            mvccConnection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_sp_t order by id",
                    "1|heap-before");
            assertRows(reopened,
                    "select id, name from mvcc_sp_mixed_t order by id",
                    "1|mvcc-before");
        }
    }
}
