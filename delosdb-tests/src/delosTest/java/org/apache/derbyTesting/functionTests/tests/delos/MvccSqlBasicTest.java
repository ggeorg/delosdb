/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlBasicTest

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

/** SQL integration tests for delos_mvcc basic behavior. */
public final class MvccSqlBasicTest extends MvccSqlTestSupport {
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


}
