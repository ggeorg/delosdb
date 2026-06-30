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
import java.sql.SQLException;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL boundary test for delos_mvcc rows that currently exceed one page. */
public final class MvccSqlLongRowBoundaryTest extends MvccSqlTestSupport {
    public void testOversizedVarcharFailsCleanlyAndLeavesMvccTableUsable() throws Exception {
        String databaseName = databaseName("mvcc-sql-long-row-boundary-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_long_row_t ("
                    + "id int primary key, payload varchar(32672)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "MVCC_LONG_ROW_T");
            connection.rollback();
        }

        assertOversizedInsertFailsCleanly(databaseName);

        try (Connection connection = openDatabase(databaseName, false)) {
            connection.setAutoCommit(false);
            insertRow(connection, 2, "small-after-oversized-failure");
            connection.commit();

            assertRows(connection,
                    "select id, payload from mvcc_long_row_t order by id",
                    "2|small-after-oversized-failure");
            assertMvccConsistent(diagnostics, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_LONG_ROW_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertRows(reopened,
                    "select id, payload from mvcc_long_row_t order by id",
                    "2|small-after-oversized-failure");
        }
    }


    private static void assertOversizedInsertFailsCleanly(String databaseName) throws SQLException {
        Connection connection = openDatabase(databaseName, false);
        try {
            connection.setAutoCommit(false);
            assertOversizedRowRejected(() -> {
                insertRow(connection, 1, repeated('x', 16000));
                connection.commit();
            });
        } finally {
            rollbackAfterExpectedConflict(connection);
            closeQuietly(connection);
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

    private static void insertRow(Connection connection, int id, String payload) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_long_row_t values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertOversizedRowRejected(SqlAction action) throws SQLException {
        try {
            action.run();
            fail("Expected delos_mvcc to reject a row that exceeds the current single-page record format");
        } catch (SQLException expected) {
            assertTrue("expected clean long-row/overflow boundary failure, got: " + expected,
                    containsMessage(expected, "too large")
                            || containsMessage(expected, "one page")
                            || containsMessage(expected, "overflow")
                            || containsMessage(expected, "does not fit"));
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
