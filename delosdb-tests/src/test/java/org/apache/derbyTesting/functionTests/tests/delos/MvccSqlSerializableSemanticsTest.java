/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlSerializableSemanticsTest

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
import java.sql.SQLException;

/** Truth gate for heap SERIALIZABLE and early delos_mvcc SERIALIZABLE rejection. */
public final class MvccSqlSerializableSemanticsTest extends MvccSqlTestSupport {
    private static final String UNSUPPORTED_SQL_STATE = "0A000";

    public void testHeapSerializableRemainsSupported() throws Exception {
        String databaseName = databaseName("heap-serializable-semantics-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table heap_serializable_t "
                    + "(id int primary key, value int not null)");
            executeUpdate(setup, "insert into heap_serializable_t values (1, 10)");
            setup.commit();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            assertRows(connection, "select id, value from heap_serializable_t", "1|10");
            assertEquals(1, executeUpdate(connection,
                    "update heap_serializable_t set value = 11 where id = 1"));
            connection.commit();
        }

        try (Connection observer = openDatabase(databaseName, false)) {
            assertRows(observer, "select id, value from heap_serializable_t", "1|11");
        }

        shutdownDatabase(databaseName);
    }

    public void testHeapSerializableViewBindingRemainsSupported() throws Exception {
        String databaseName = databaseName("heap-serializable-view-binding-db");

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table heap_serializable_left "
                    + "(id int primary key, value int not null)");
            executeUpdate(setup, "create table heap_serializable_right "
                    + "(id int primary key, value int not null)");
            executeUpdate(setup, "insert into heap_serializable_left values (1, 10)");
            executeUpdate(setup, "insert into heap_serializable_right values (2, 20)");
            executeUpdate(setup, "create view heap_serializable_v as "
                    + "select id, value from heap_serializable_left "
                    + "union all select id, value from heap_serializable_right");
            setup.commit();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            assertRows(connection,
                    "select id, value from heap_serializable_v order by id",
                    "1|10", "2|20");
            connection.commit();
        }

        shutdownDatabase(databaseName);
    }

    public void testMvccSerializableViewRejectsOnUnderlyingBaseTable() throws Exception {
        String databaseName = databaseName("mvcc-serializable-view-rejection-db");
        createMvccFixture(databaseName);

        try (Connection setup = openDatabase(databaseName, false)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create view mvcc_serializable_v as "
                    + "select id, value from mvcc_serializable_t");
            setup.commit();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            assertUnsupported(() -> assertRows(connection,
                    "select id, value from mvcc_serializable_v", "1|10"));
            connection.rollback();
        }

        shutdownDatabase(databaseName);
    }

    public void testMvccSerializableReadRejectsBeforeOpeningUnsafeSnapshot() throws Exception {
        String databaseName = databaseName("mvcc-serializable-read-rejection-db");
        createMvccFixture(databaseName);

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            assertUnsupported(() -> assertRows(connection,
                    "select id, value from mvcc_serializable_t", "1|10"));
            connection.rollback();
        }

        try (Connection observer = openDatabase(databaseName, false)) {
            assertRows(observer, "select id, value from mvcc_serializable_t", "1|10");
        }

        shutdownDatabase(databaseName);
    }

    public void testMvccSerializableWriteRejectsBeforeMutation() throws Exception {
        String databaseName = databaseName("mvcc-serializable-write-rejection-db");
        createMvccFixture(databaseName);

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            assertUnsupported(() -> executeUpdate(connection,
                    "insert into mvcc_serializable_t values (2, 20)"));
            connection.rollback();
        }

        try (Connection observer = openDatabase(databaseName, false)) {
            assertRows(observer,
                    "select id, value from mvcc_serializable_t order by id",
                    "1|10");
        }

        shutdownDatabase(databaseName);
    }

    public void testMvccRepeatableReadRetainsTransactionSnapshot() throws Exception {
        String databaseName = databaseName("mvcc-repeatable-read-semantics-db");
        createMvccFixture(databaseName);

        try (Connection first = openDatabase(databaseName, false);
             Connection second = openDatabase(databaseName, false)) {
            first.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            assertRows(first, "select id, value from mvcc_serializable_t", "1|10");
            executeUpdate(second, "update mvcc_serializable_t set value = 11 where id = 1");
            second.commit();
            assertRows(first, "select id, value from mvcc_serializable_t", "1|10");
            first.commit();
        }

        try (Connection observer = openDatabase(databaseName, false)) {
            assertRows(observer, "select id, value from mvcc_serializable_t", "1|11");
        }

        shutdownDatabase(databaseName);
    }

    private static void createMvccFixture(String databaseName) throws SQLException {
        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table mvcc_serializable_t "
                    + "(id int primary key, value int not null) using delos_mvcc");
            executeUpdate(setup, "insert into mvcc_serializable_t values (1, 10)");
            setup.commit();
        }
    }

    private static void assertUnsupported(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected delos_mvcc SERIALIZABLE to reject before execution");
        } catch (SQLException expected) {
            assertEquals("stable MVCC SERIALIZABLE SQLState", UNSUPPORTED_SQL_STATE,
                    expected.getSQLState());
            assertTrue("expected truthful SERIALIZABLE rejection, got: " + expected,
                    containsMessage(expected, "SERIALIZABLE")
                            && containsMessage(expected, "delos_mvcc"));
        }
    }
}
