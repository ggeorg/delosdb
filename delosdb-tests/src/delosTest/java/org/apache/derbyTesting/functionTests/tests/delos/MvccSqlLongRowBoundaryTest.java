/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlLongRowBoundaryTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL proof for large delos_mvcc row payloads across vacuum and reopen. */
public final class MvccSqlLongRowBoundaryTest extends MvccSqlTestSupport {
    public void testLongVarcharSurvivesVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-long-row-overflow-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        String largePayload = repeated('x', 16000);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_long_row_t ("
                    + "id int primary key, payload varchar(32672)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "MVCC_LONG_ROW_T");
            connection.rollback();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            insertRow(connection, 1, largePayload);
            connection.commit();

            assertPayloadRoundTrips(connection, 1, largePayload);
            assertMvccConsistent(diagnostics, containerId);
            connection.rollback();
        }

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            inPlaceCompressTable(connection, "MVCC_LONG_ROW_T");
            connection.commit();
            assertMvccStorageProvider(connection, "MVCC_LONG_ROW_T");
            assertPayloadRoundTrips(connection, 1, largePayload);
            assertMvccConsistent(diagnostics, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertMvccStorageProvider(reopened, "MVCC_LONG_ROW_T");
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_LONG_ROW_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertPayloadRoundTrips(reopened, 1, largePayload);
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }

    private static void insertRow(Connection connection, int id, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_long_row_t values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertPayloadRoundTrips(Connection connection, int id, String expected) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from mvcc_long_row_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected row " + id, rs.next());
                assertEquals(expected, rs.getString(1));
                assertFalse("expected only one row for id " + id, rs.next());
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
