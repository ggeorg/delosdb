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
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Cross-provider proof for SQL-layer actual-change propagation. */
public final class SharedNoOpUpdatePropagationTest extends MvccSqlTestSupport {
    public void testHeapAndMvccChangedIndexMutationsRemainTransactional() throws Exception {
        exerciseChangedIndexMutations("heap", "");
        exerciseChangedIndexMutations("mvcc", " using delos_mvcc");
    }

    private void exerciseChangedIndexMutations(String provider, String createSuffix)
            throws Exception {
        String database = databaseName(
                "shared-index-mutation-" + provider + '-' + System.nanoTime());
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "create table index_mutation_target ("
                                + "id int primary key, category int not null, "
                                + "bucket int not null, quantity int not null)"
                                + createSuffix);
                statement.executeUpdate(
                        "create index index_mutation_category "
                                + "on index_mutation_target(category)");
                statement.executeUpdate(
                        "create index index_mutation_range "
                                + "on index_mutation_target(bucket, quantity)");
                statement.executeUpdate(
                        "insert into index_mutation_target values "
                                + "(1, 10, 1, 100), (2, 20, 2, 200), (3, 30, 3, 300)");
            }
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update index_mutation_target "
                            + "set category = 11, quantity = 101 where id = 1"));
            connection.rollback();
            assertIndexedMutationRows(connection,
                    "10|1|100",
                    "20|2|200",
                    "30|3|300");

            assertEquals(1, executeUpdate(connection,
                    "update index_mutation_target "
                            + "set category = 11, quantity = 101 where id = 1"));
            connection.commit();
            assertRows(connection,
                    "select id from index_mutation_target "
                            + "--DERBY-PROPERTIES index=index_mutation_category\n"
                            + "where category = 10");
            assertRows(connection,
                    "select id, quantity from index_mutation_target "
                            + "--DERBY-PROPERTIES index=index_mutation_category\n"
                            + "where category = 11",
                    "1|101");

            assertEquals(1, executeUpdate(connection,
                    "delete from index_mutation_target where id = 2"));
            connection.rollback();
            assertRows(connection,
                    "select id, quantity from index_mutation_target "
                            + "--DERBY-PROPERTIES index=index_mutation_category\n"
                            + "where category = 20",
                    "2|200");

            assertEquals(1, executeUpdate(connection,
                    "delete from index_mutation_target where id = 2"));
            connection.commit();
            assertRows(connection,
                    "select id from index_mutation_target "
                            + "--DERBY-PROPERTIES index=index_mutation_category\n"
                            + "where category = 20");
            assertRows(connection,
                    "select id from index_mutation_target "
                            + "--DERBY-PROPERTIES index=index_mutation_range\n"
                            + "where bucket = 2 and quantity = 200");

            assertEquals(1, executeUpdate(connection,
                    "insert into index_mutation_target values (2, 21, 2, 201)"));
            connection.commit();
            assertIndexedMutationRows(connection,
                    "11|1|101",
                    "21|2|201",
                    "30|3|300");
            connection.rollback();
        } finally {
            try {
                shutdownDatabase(database);
            } catch (Exception ignored) {
                // The connection close above may already have stopped a failed test database.
            }
        }
    }

    private static void assertIndexedMutationRows(
            Connection connection,
            String... expectedRows) throws Exception {
        assertRows(connection,
                "select category, id, quantity from index_mutation_target "
                        + "--DERBY-PROPERTIES index=index_mutation_category\n"
                        + "order by category, id",
                expectedRows);
    }

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
            verifyMergeDefaultAndGeneratedColumnSemantics(connection, createSuffix);
            connection.rollback();
            configureShortLockTimeout(connection);
            verifyNoOpUpdateLockSemantics(provider, database);
        } finally {
            try {
                shutdownDatabase(database);
            } catch (Exception ignored) {
                // The connection close above may already have stopped a failed test database.
            }
        }
    }

    private static void verifyMergeDefaultAndGeneratedColumnSemantics(
            Connection connection,
            String createSuffix) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "create table merge_target ("
                            + "id int primary key, match_key int, "
                            + "generated_value int generated always as (id + match_key), "
                            + "payload int, default_value int default 1000)"
                            + createSuffix);
            statement.executeUpdate(
                    "create table merge_source (id int primary key, match_key int)"
                            + createSuffix);
            statement.executeUpdate(
                    "insert into merge_target(id, match_key, payload) "
                            + "values (1, 1, 100), (2, 2, 200)");
            statement.executeUpdate(
                    "insert into merge_source values (1, 1), (2, 2)");
        }
        connection.commit();

        try (Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "merge into merge_target using merge_source "
                            + "on merge_target.match_key = merge_source.match_key "
                            + "when matched and merge_target.payload = 200 then update "
                            + "set default_value = 10 * merge_source.id"));
        }
        connection.commit();
        assertRows(connection,
                "select id, match_key, generated_value, default_value "
                        + "from merge_target order by id",
                "1|1|2|1000",
                "2|2|4|20");

        try (Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate(
                    "merge into merge_target using merge_source "
                            + "on merge_target.match_key = merge_source.match_key "
                            + "when matched then update set generated_value = default, "
                            + "match_key = 10 * merge_source.match_key, "
                            + "default_value = default"));
        }
        connection.commit();
        assertRows(connection,
                "select id, match_key, generated_value, default_value "
                        + "from merge_target order by id",
                "1|10|11|1000",
                "2|20|22|1000");
    }

    private static void configureShortLockTimeout(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "call syscs_util.syscs_set_database_property("
                            + "'derby.locks.waitTimeout', '1')");
        }
        connection.commit();
    }

    private static void verifyNoOpUpdateLockSemantics(
            String provider,
            String database) throws Exception {
        try (Connection holder = openDatabase(database, false);
             Connection contender = openDatabase(database, false)) {
            holder.setAutoCommit(false);
            contender.setAutoCommit(false);

            assertEquals("first no-op UPDATE must retain affected-row semantics for " + provider,
                    1,
                    executeUpdate(holder,
                            "update update_target set quantity = quantity where id = 1"));

            try {
                executeUpdate(contender,
                        "update update_target set quantity = quantity where id = 1");
                fail("concurrent no-op UPDATE must wait for the existing write lock for "
                        + provider);
            } catch (SQLException expected) {
                String sqlState = expected.getSQLState();
                assertTrue("expected lock timeout or deadlock for concurrent no-op UPDATE on "
                                + provider + ", got " + sqlState + ": " + expected,
                        "40XL1".equals(sqlState)
                                || "40XL2".equals(sqlState)
                                || "40001".equals(sqlState));
            } finally {
                contender.rollback();
                holder.rollback();
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
