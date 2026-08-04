/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedNoOpUpdatePropagationTest

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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Cross-provider proof for SQL-layer actual-change propagation. */
public final class SharedNoOpUpdatePropagationTest extends MvccSqlTestSupport {
    public void testHeapAndMvccSkipPhysicalNoOpUpdates() throws Exception {
        exerciseProvider("heap", "");
        exerciseProvider("mvcc", " using delos_mvcc");
    }

    private void exerciseProvider(String provider, String createSuffix) throws Exception {
        String database = databaseName(
                "shared-no-op-update-" + provider + '-' + System.nanoTime());
        Path databaseDirectory = databasePath(database);
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create table update_target ("
                                + "id int primary key, code varchar(32), quantity int, note varchar(32))"
                                + createSuffix);
                statement.executeUpdate(
                        "create index update_target_code on update_target(code)");
                statement.executeUpdate(
                        "insert into update_target values (1, 'Alpha', 7, null)");
            }
            connection.commit();
            checkpoint(connection);

            DelosRawStoreIoSnapshot beforeNoOp = snapshot(provider, databaseDirectory);
            try (PreparedStatement update = connection.prepareStatement(
                    "update update_target set code = ?, quantity = ?, note = ? where id = 1")) {
                update.setString(1, "Alpha");
                update.setInt(2, 7);
                update.setNull(3, java.sql.Types.VARCHAR);
                assertEquals("SQL affected-row semantics must be preserved", 1, update.executeUpdate());
            }
            connection.commit();
            checkpoint(connection);
            DelosRawStoreIoSnapshot afterNoOp = snapshot(provider, databaseDirectory);

            assertEquals("checkpointed no-op update must not flush RawStore pages for " + provider,
                    beforeNoOp.pageWriteOperations(), afterNoOp.pageWriteOperations());
            assertEquals("checkpointed no-op update must not flush RawStore bytes for " + provider,
                    beforeNoOp.pageWriteBytes(), afterNoOp.pageWriteBytes());
            assertTargetRow(connection, "Alpha", 7, null);

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "update update_target "
                                + "set code = 'Alpha', quantity = 8, note = null where id = 1"));
            }
            connection.commit();
            checkpoint(connection);
            DelosRawStoreIoSnapshot afterPartialChange = snapshot(provider, databaseDirectory);
            assertTrue("a checkpointed real partial update must flush RawStore data for " + provider,
                    afterPartialChange.pageWriteOperations() > afterNoOp.pageWriteOperations()
                            || afterPartialChange.pageWriteBytes() > afterNoOp.pageWriteBytes());
            assertTargetRow(connection, "Alpha", 8, null);
            assertRows(connection,
                    "select id, quantity from update_target where code = 'Alpha'",
                    "1|8");

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate(
                        "update update_target set code = 'alpha' where id = 1"));
            }
            connection.commit();
            assertTargetRow(connection, "alpha", 8, null);
            assertRows(connection,
                    "select id, quantity from update_target where code = 'alpha'",
                    "1|8");
            assertRows(connection,
                    "select id from update_target where code = 'Alpha'");

            verifyTriggerAndRowCountSemantics(connection, createSuffix);
        } finally {
            try {
                shutdownDatabase(database);
            } catch (Exception ignored) {
                // The connection close above may already have stopped a failed test database.
            }
        }
    }

    private static void verifyTriggerAndRowCountSemantics(
            Connection connection,
            String createSuffix) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table trigger_target (id int primary key, value int)" + createSuffix);
            statement.executeUpdate("create table update_audit (id int, value int)");
            statement.executeUpdate("insert into trigger_target values (1, 11)");
            statement.executeUpdate(
                    "create trigger trigger_target_update after update on trigger_target "
                            + "referencing new as n for each row "
                            + "insert into update_audit values (n.id, n.value)");
        }
        connection.commit();

        try (Statement statement = connection.createStatement()) {
            assertEquals("no-op UPDATE must still report one affected row", 1,
                    statement.executeUpdate(
                            "update trigger_target set value = value where id = 1"));
        }
        connection.commit();

        assertRows(connection, "select id, value from trigger_target", "1|11");
        assertRows(connection, "select id, value from update_audit", "1|11");
    }

    private static void checkpoint(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CALL SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");
        }
    }

    private static DelosRawStoreIoSnapshot snapshot(String provider, Path databaseDirectory) {
        DelosRawStoreIoSnapshot snapshot = "mvcc".equals(provider)
                ? DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(databaseDirectory)
                : DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(databaseDirectory);
        assertTrue("RawStore diagnostics must be active for " + provider, snapshot.runtimeActive());
        return snapshot;
    }

    private static void assertTargetRow(
            Connection connection,
            String expectedCode,
            int expectedQuantity,
            String expectedNote) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select code, quantity, note from update_target where id = 1")) {
            assertTrue(result.next());
            assertEquals(expectedCode, result.getString(1));
            assertEquals(expectedQuantity, result.getInt(2));
            assertEquals(expectedNote, result.getString(3));
            assertFalse(result.next());
        }
    }
}
