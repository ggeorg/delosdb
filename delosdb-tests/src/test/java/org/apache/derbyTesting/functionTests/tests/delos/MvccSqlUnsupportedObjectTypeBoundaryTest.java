/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlUnsupportedObjectTypeBoundaryTest

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
import java.sql.SQLException;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL boundary tests for delos_mvcc values that need deliberate durable policies. */
public final class MvccSqlUnsupportedObjectTypeBoundaryTest extends MvccSqlTestSupport {
    public void testJavaObjectColumnFailsCleanlyAndDoesNotPoisonMvccRuntime() throws Exception {
        String databaseName = databaseName("mvcc-sql-java-object-boundary-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create type mvcc_java_util_list external name 'java.util.List' language java");
            executeUpdate(connection, "create table mvcc_java_object_t ("
                    + "id int primary key, payload mvcc_java_util_list) using delos_mvcc");
            connection.commit();
        }

        assertJavaObjectInsertFailsCleanly(databaseName);

        long normalContainerId;
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_after_object_failure_t ("
                    + "id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
            normalContainerId = mvccContainerId(connection, "MVCC_AFTER_OBJECT_FAILURE_T");
            connection.rollback();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "insert into mvcc_after_object_failure_t values (1, 'ok')");
            connection.commit();

            assertRows(connection,
                    "select id, name from mvcc_after_object_failure_t order by id",
                    "1|ok");
            assertMvccConsistent(diagnostics, normalContainerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_AFTER_OBJECT_FAILURE_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertRows(reopened,
                    "select id, name from mvcc_after_object_failure_t order by id",
                    "1|ok");
        }
    }

    public void testBlobAndClobColumnsFailCleanlyAndDoNotPoisonMvccRuntime() throws Exception {
        String databaseName = databaseName("mvcc-sql-lob-boundary-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_blob_boundary_t ("
                    + "id int primary key, payload blob(1024)) using delos_mvcc");
            executeUpdate(connection, "create table mvcc_clob_boundary_t ("
                    + "id int primary key, payload clob(1024)) using delos_mvcc");
            connection.commit();
        }

        assertBlobInsertFailsCleanly(databaseName);
        assertClobInsertFailsCleanly(databaseName);

        long normalContainerId;
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_after_lob_failure_t ("
                    + "id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_after_lob_failure_t values (1, 'ok')");
            connection.commit();
            normalContainerId = mvccContainerId(connection, "MVCC_AFTER_LOB_FAILURE_T");
            assertMvccConsistent(diagnostics, normalContainerId);
            assertRows(connection,
                    "select id, name from mvcc_after_lob_failure_t order by id",
                    "1|ok");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_AFTER_LOB_FAILURE_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertRows(reopened,
                    "select id, name from mvcc_after_lob_failure_t order by id",
                    "1|ok");
        }
    }

    private static void assertJavaObjectInsertFailsCleanly(String databaseName) throws SQLException {
        Connection connection = openDatabase(databaseName, false);
        try {
            connection.setAutoCommit(false);
            assertJavaObjectRejected(() -> {
                executeUpdate(connection, "insert into mvcc_java_object_t values (1, null)");
                connection.commit();
            });
        } finally {
            rollbackAfterExpectedConflict(connection);
            closeQuietly(connection);
        }
    }

    private static void assertJavaObjectRejected(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected delos_mvcc to reject JAVA_OBJECT/UserType durable row values");
        } catch (SQLException expected) {
            assertTrue("expected clean JAVA_OBJECT/UserType boundary failure, got: " + expected,
                    containsMessage(expected, "JAVA_OBJECT")
                            || containsMessage(expected, "UserType")
                            || containsMessage(expected, "serialization")
                            || containsMessage(expected, "not supported"));
        }
    }

    private static void assertBlobInsertFailsCleanly(String databaseName) throws SQLException {
        Connection connection = openDatabase(databaseName, false);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into mvcc_blob_boundary_t values (?, ?)")) {
                statement.setInt(1, 1);
                statement.setBytes(2, new byte[] {1, 2, 3, 4});
                assertUnsupportedLobRejected(() -> {
                    statement.executeUpdate();
                    connection.commit();
                }, "BLOB");
            }
        } finally {
            rollbackAfterExpectedConflict(connection);
            closeQuietly(connection);
        }
    }

    private static void assertClobInsertFailsCleanly(String databaseName) throws SQLException {
        Connection connection = openDatabase(databaseName, false);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into mvcc_clob_boundary_t values (?, ?)")) {
                statement.setInt(1, 1);
                statement.setString(2, "unsupported-clob-payload");
                assertUnsupportedLobRejected(() -> {
                    statement.executeUpdate();
                    connection.commit();
                }, "CLOB");
            }
        } finally {
            rollbackAfterExpectedConflict(connection);
            closeQuietly(connection);
        }
    }

    private static void assertUnsupportedLobRejected(SqlAction action, String typeName) throws SQLException {
        try {
            action.run();
            fail("Expected delos_mvcc to reject " + typeName + " durable row values");
        } catch (SQLException expected) {
            assertTrue("expected clean " + typeName + " boundary failure, got: " + expected,
                    containsMessage(expected, typeName)
                            || containsMessage(expected, "LOB")
                            || containsMessage(expected, "not supported"));
        }
    }

    private static void closeQuietly(Connection connection) throws SQLException {
        try {
            connection.close();
        } catch (SQLException e) {
            if (!"08003".equals(e.getSQLState()) && !"25001".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }
}
