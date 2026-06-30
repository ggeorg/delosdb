/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlOverflowLifecycleTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL lifecycle proof for MVCC overflow-backed row payloads. */
public final class MvccSqlOverflowLifecycleTest extends MvccSqlTestSupport {
    public void testOverflowPayloadsSurviveUpdateDeleteRollbackVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-overflow-lifecycle-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        String shortPayload = "short-payload";
        String initialLongA = repeated('a', 16000);
        String initialLongB = repeated('b', 17000);
        String initialLongE = repeated('e', 18000);
        String committedLongC = repeated('c', 19000);
        String committedLongD = repeated('d', 15000);
        String rolledBackLongR = repeated('r', 16000);
        String rolledBackLongZ = repeated('z', 16000);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_overflow_lifecycle_t ("
                    + "id int primary key, label varchar(32), payload varchar(32672)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "MVCC_OVERFLOW_LIFECYCLE_T");
            connection.rollback();
        }

        long initialOverflowPages;
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            insertRow(connection, 1, "short", shortPayload);
            insertRow(connection, 2, "long-a", initialLongA);
            insertRow(connection, 3, "long-b", initialLongB);
            insertRow(connection, 5, "long-e", initialLongE);
            connection.commit();

            assertPayloadRoundTrips(connection, 1, shortPayload);
            assertPayloadRoundTrips(connection, 2, initialLongA);
            assertPayloadRoundTrips(connection, 3, initialLongB);
            assertPayloadRoundTrips(connection, 5, initialLongE);
            assertRows(connection,
                    "select id, label from mvcc_overflow_lifecycle_t order by id",
                    "1|short", "2|long-a", "3|long-b", "5|long-e");
            assertMvccConsistent(diagnostics, containerId);
            initialOverflowPages = diagnostics.overflowPageCountForTesting(0, containerId);
            assertTrue("expected long rows to allocate overflow pages", initialOverflowPages > 0L);
            connection.rollback();
        }

        long overflowPagesBeforeVacuum;
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);

            Savepoint rollbackPoint = connection.setSavepoint("ROLLBACK_OVERFLOW_MUTATIONS");
            updatePayload(connection, 2, "rolled-back-long", rolledBackLongR);
            insertRow(connection, 4, "rolled-back-insert", rolledBackLongZ);
            connection.rollback(rollbackPoint);
            connection.releaseSavepoint(rollbackPoint);

            updatePayload(connection, 2, "long-c", committedLongC);
            updatePayload(connection, 3, "short-after-long", "now-short");
            updatePayload(connection, 1, "short-to-long", committedLongD);
            deleteRow(connection, 5);
            connection.commit();

            assertPayloadRoundTrips(connection, 1, committedLongD);
            assertPayloadRoundTrips(connection, 2, committedLongC);
            assertPayloadRoundTrips(connection, 3, "now-short");
            assertNoRow(connection, 4);
            assertNoRow(connection, 5);
            assertRows(connection,
                    "select id, label from mvcc_overflow_lifecycle_t order by id",
                    "1|short-to-long", "2|long-c", "3|short-after-long");
            assertMvccConsistent(diagnostics, containerId);
            overflowPagesBeforeVacuum = diagnostics.overflowPageCountForTesting(0, containerId);
            assertTrue("expected obsolete long-row versions to leave extra overflow pages before vacuum; initial="
                            + initialOverflowPages + ", beforeVacuum=" + overflowPagesBeforeVacuum,
                    overflowPagesBeforeVacuum > initialOverflowPages);
            connection.rollback();
        }

        long overflowPagesAfterVacuum;
        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            inPlaceCompressTable(connection, "MVCC_OVERFLOW_LIFECYCLE_T");
            connection.commit();

            assertEquals("in-place compress must keep the MVCC base container stable",
                    containerId, mvccContainerId(connection, "MVCC_OVERFLOW_LIFECYCLE_T"));
            assertPayloadRoundTrips(connection, 1, committedLongD);
            assertPayloadRoundTrips(connection, 2, committedLongC);
            assertPayloadRoundTrips(connection, 3, "now-short");
            assertNoRow(connection, 5);
            assertMvccConsistent(diagnostics, containerId);
            overflowPagesAfterVacuum = diagnostics.overflowPageCountForTesting(0, containerId);
            assertTrue("vacuum must retain live overflow chains", overflowPagesAfterVacuum > 0L);
            assertTrue("vacuum should drop obsolete overflow chains; before="
                            + overflowPagesBeforeVacuum + ", after=" + overflowPagesAfterVacuum,
                    overflowPagesAfterVacuum < overflowPagesBeforeVacuum);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_OVERFLOW_LIFECYCLE_T");
            assertEquals("overflow page count should remain stable after reopen",
                    overflowPagesAfterVacuum, diagnostics.overflowPageCountForTesting(0, reopenedContainerId));
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertPayloadRoundTrips(reopened, 1, committedLongD);
            assertPayloadRoundTrips(reopened, 2, committedLongC);
            assertPayloadRoundTrips(reopened, 3, "now-short");
            assertRows(reopened,
                    "select id, label from mvcc_overflow_lifecycle_t order by id",
                    "1|short-to-long", "2|long-c", "3|short-after-long");
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }

    private static void insertRow(Connection connection, int id, String label, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_overflow_lifecycle_t values (?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, label);
            statement.setString(3, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void updatePayload(Connection connection, int id, String label, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update mvcc_overflow_lifecycle_t set label = ?, payload = ? where id = ?")) {
            statement.setString(1, label);
            statement.setString(2, payload);
            statement.setInt(3, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void deleteRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from mvcc_overflow_lifecycle_t where id = ?")) {
            statement.setInt(1, id);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertPayloadRoundTrips(Connection connection, int id, String expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from mvcc_overflow_lifecycle_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected row " + id, rs.next());
                assertEquals(expected, rs.getString(1));
                assertFalse("expected only one row for id " + id, rs.next());
            }
        }
    }

    private static void assertNoRow(Connection connection, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from mvcc_overflow_lifecycle_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertFalse("expected no row for id " + id, rs.next());
            }
        }
    }

    private static String repeated(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
