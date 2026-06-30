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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Arrays;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL boundary tests for delos_mvcc durable value policies. */
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

    public void testMaterializedBlobAndClobColumnsSurviveRollbackVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-lob-minimal-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        byte[] originalBlob = blobPayload(8_000, 11);
        String originalClob = clobPayload(8_000, 'a');
        byte[] updatedBlob = blobPayload(9_000, 71);
        String updatedClob = clobPayload(9_000, 'k');
        byte[] deletedBlob = blobPayload(7_000, 31);
        String deletedClob = clobPayload(7_000, 'm');
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_lob_minimal_t ("
                    + "id int primary key, "
                    + "blob_payload blob(32768), "
                    + "clob_payload clob(32768), "
                    + "note varchar(32)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "MVCC_LOB_MINIMAL_T");

            insertLobRow(connection, 1, originalBlob, originalClob, "original");
            connection.commit();
            assertLobRow(connection, 1, originalBlob, originalClob, "original");
            assertMvccConsistent(diagnostics, containerId);

            Savepoint savepoint = connection.setSavepoint("LOB_SP");
            updateLobRow(connection, 1, deletedBlob, deletedClob, "rolled-back-update");
            insertLobRow(connection, 2, deletedBlob, deletedClob, "rolled-back-insert");
            executeUpdate(connection, "delete from mvcc_lob_minimal_t where id = 1");
            connection.rollback(savepoint);
            assertLobRow(connection, 1, originalBlob, originalClob, "original");
            assertNoLobRow(connection, 2);

            updateLobRow(connection, 1, updatedBlob, updatedClob, "updated");
            insertLobRow(connection, 2, deletedBlob, deletedClob, "delete-me");
            executeUpdate(connection, "delete from mvcc_lob_minimal_t where id = 2");
            connection.commit();
            assertLobRow(connection, 1, updatedBlob, updatedClob, "updated");
            assertNoLobRow(connection, 2);
            assertMvccConsistent(diagnostics, containerId);

            inPlaceCompressTable(connection, "MVCC_LOB_MINIMAL_T");
            connection.commit();
            assertFalse("LOB vacuum should not be skipped",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertLobRow(connection, 1, updatedBlob, updatedClob, "updated");
            assertNoLobRow(connection, 2);
            assertMvccConsistent(diagnostics, containerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_LOB_MINIMAL_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertLobRow(reopened, 1, updatedBlob, updatedClob, "updated");
            assertNoLobRow(reopened, 2);
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

    private static void insertLobRow(
            Connection connection,
            int id,
            byte[] blobPayload,
            String clobPayload,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_lob_minimal_t values (?, ?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setBytes(2, blobPayload);
            statement.setString(3, clobPayload);
            statement.setString(4, note);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updateLobRow(
            Connection connection,
            int id,
            byte[] blobPayload,
            String clobPayload,
            String note) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_lob_minimal_t set blob_payload = ?, clob_payload = ?, note = ? where id = ?")) {
            statement.setBytes(1, blobPayload);
            statement.setString(2, clobPayload);
            statement.setString(3, note);
            statement.setInt(4, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertLobRow(
            Connection connection,
            int id,
            byte[] expectedBlob,
            String expectedClob,
            String expectedNote) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select blob_payload, clob_payload, note from mvcc_lob_minimal_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected LOB row " + id, rs.next());
                assertTrue("BLOB payload mismatch for row " + id,
                        Arrays.equals(expectedBlob, rs.getBytes(1)));
                assertEquals("CLOB payload mismatch for row " + id, expectedClob, rs.getString(2));
                assertEquals("note mismatch for row " + id, expectedNote, rs.getString(3));
                assertFalse("expected one LOB row " + id, rs.next());
            }
        }
    }

    private static void assertNoLobRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from mvcc_lob_minimal_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no LOB row " + id, rs.next());
            }
        }
    }

    private static byte[] blobPayload(int size, int seed) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((seed + (i * 17)) & 0xff);
        }
        return payload;
    }

    private static String clobPayload(int size, char base) {
        StringBuilder builder = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            builder.append((char) (base + (i % 13)));
        }
        return builder.toString();
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
        diagnostics.assertConsistentForTesting(0, containerId);
        assertEquals("expected no MVCC consistency errors", 0,
                diagnostics.consistencyDiagnosticsForTesting(0, containerId).errorCount());
    }
}
