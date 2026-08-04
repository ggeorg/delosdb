/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlDatabaseRuntimeIsolationTest

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
import java.sql.DriverManager;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;

/** SQL proof that each open Derby database owns an independent MVCC runtime. */
public final class MvccSqlDatabaseRuntimeIsolationTest extends MvccSqlTestSupport {
    public void testAlternatingTwoDatabaseLifecycleKeepsMvccStateIsolated() throws Exception {
        String databaseA = databaseName("mvcc-runtime-isolation-a");
        String databaseB = databaseName("mvcc-runtime-isolation-b");
        DelosStorageDiagnostics diagnosticsA = mvccDiagnostics(databaseA);
        DelosStorageDiagnostics diagnosticsB = mvccDiagnostics(databaseB);

        long aFirstContainer;
        long aSecondContainer;
        long bFirstContainer;

        Connection connectionA = openDatabase(databaseA, true);
        Connection connectionB = null;
        try {
            connectionA.setAutoCommit(false);
            executeUpdate(connectionA,
                    "create table runtime_a_first (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionA, "insert into runtime_a_first values (1, 'a-first')");
            connectionA.commit();
            aFirstContainer = mvccContainerId(connectionA, "RUNTIME_A_FIRST");
            connectionA.rollback();

            connectionB = openDatabase(databaseB, true);
            connectionB.setAutoCommit(false);
            executeUpdate(connectionB,
                    "create table runtime_b_first (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionB, "insert into runtime_b_first values (1, 'b-first')");
            connectionB.commit();
            bFirstContainer = mvccContainerId(connectionB, "RUNTIME_B_FIRST");
            connectionB.rollback();

            // This creation happens after database B has booted. It is the
            // sequence which previously resolved through the mutable global
            // database directory and could attach A's table to B's store.
            executeUpdate(connectionA,
                    "create table runtime_a_second (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionA, "insert into runtime_a_second values (2, 'a-second')");
            connectionA.commit();
            aSecondContainer = mvccContainerId(connectionA, "RUNTIME_A_SECOND");
            connectionA.rollback();

            assertRows(connectionA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(connectionA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            assertRows(connectionB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first");

            DelosStorageMaintenanceSnapshot initialA = assertRuntimeOwnsTables(
                    diagnosticsA, aFirstContainer, aSecondContainer);
            DelosStorageMaintenanceSnapshot initialB = assertRuntimeOwnsTables(
                    diagnosticsB, bFirstContainer);
            assertFalse("each database must expose a distinct RawStore MVCC runtime identity",
                    initialA.databaseIdentity().equals(initialB.databaseIdentity()));
            connectionA.rollback();

            connectionA.close();
            connectionA = null;
            shutdownDatabase(databaseA);
            assertFalse("database A runtime must be released by clean shutdown",
                    diagnosticsA.runtimeActiveForTesting());
            assertEquals(0, diagnosticsA.runtimeStateCountForTesting());

            // Shutting down A must not close B's store, maintenance service,
            // backup coordinator, table state, or diagnostics context.
            executeUpdate(connectionB, "insert into runtime_b_first values (2, 'b-after-a-shutdown')");
            connectionB.commit();
            assertRows(connectionB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first",
                    "2|b-after-a-shutdown");
            assertRuntimeOwnsTables(diagnosticsB, bFirstContainer);
            connectionB.rollback();

            connectionA = openDatabase(databaseA, false);
            assertTrue("reopen must activate database A's persisted MVCC runtime before diagnostics",
                    diagnosticsA.runtimeActiveForTesting());
            assertEquals("reopen must attach both persisted database A table states",
                    2, diagnosticsA.runtimeStateCountForTesting());
            connectionA.setAutoCommit(false);
            assertRows(connectionA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(connectionA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            long reopenedAFirst = mvccContainerId(connectionA, "RUNTIME_A_FIRST");
            connectionA.rollback();
            assertEquals(aFirstContainer, reopenedAFirst);
            DelosStorageMaintenanceSnapshot reopenedASnapshot = assertRuntimeOwnsTables(
                    diagnosticsA, aFirstContainer, aSecondContainer);
            DelosStorageMaintenanceSnapshot stillOpenBSnapshot = assertRuntimeOwnsTables(
                    diagnosticsB, bFirstContainer);
            assertFalse("reopened database A must not attach to database B's runtime",
                    reopenedASnapshot.databaseIdentity().equals(
                            stillOpenBSnapshot.databaseIdentity()));
        } finally {
            rollbackAndClose(connectionA);
            rollbackAndClose(connectionB);
        }

        shutdownDatabase(databaseA);
        shutdownDatabase(databaseB);

        try (Connection reopenedA = openDatabase(databaseA, false);
                Connection reopenedB = openDatabase(databaseB, false)) {
            reopenedA.setAutoCommit(false);
            reopenedB.setAutoCommit(false);
            DelosStorageMaintenanceSnapshot finalA = assertRuntimeOwnsTables(
                    diagnosticsA, aFirstContainer, aSecondContainer);
            DelosStorageMaintenanceSnapshot finalB = assertRuntimeOwnsTables(
                    diagnosticsB, bFirstContainer);
            assertFalse("simultaneously reopened databases must retain distinct runtime identities",
                    finalA.databaseIdentity().equals(finalB.databaseIdentity()));
            assertRows(reopenedA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(reopenedA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            assertRows(reopenedB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first",
                    "2|b-after-a-shutdown");
            reopenedA.rollback();
            reopenedB.rollback();
        }

        shutdownDatabase(databaseA);
        shutdownDatabase(databaseB);
        assertFalse(diagnosticsA.runtimeActiveForTesting());
        assertFalse(diagnosticsB.runtimeActiveForTesting());
        assertEquals(0, diagnosticsA.runtimeStateCountForTesting());
        assertEquals(0, diagnosticsB.runtimeStateCountForTesting());
    }


    public void testMemoryDatabaseUsesInheritedRawStoreRuntime() throws Exception {
        String database = "mvcc-memory-runtime-" + System.nanoTime();
        String jdbcUrl = "jdbc:derby:memory:" + database;
        try (Connection connection = DriverManager.getConnection(jdbcUrl + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_control (id int primary key)");
            executeUpdate(connection,
                    "create table memory_mvcc (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into heap_control values 1");
            executeUpdate(connection, "insert into memory_mvcc values (1, 10)");
            connection.commit();

            assertRows(connection, "select id from heap_control", "1");
            assertRows(connection, "select id, value from memory_mvcc", "1|10");
            connection.rollback();
        } finally {
            try {
                DriverManager.getConnection(jdbcUrl + ";shutdown=true");
                fail("Memory database shutdown should throw the normal Derby shutdown exception");
            } catch (java.sql.SQLException expected) {
                assertEquals("08006", expected.getSQLState());
            }
        }
    }


    private static void rollbackAndClose(Connection connection) throws Exception {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
        } finally {
            connection.close();
        }
    }

    private static DelosStorageMaintenanceSnapshot assertRuntimeOwnsTables(
            DelosStorageDiagnostics diagnostics,
            long... metadataContainerIds) {
        DelosStorageMaintenanceSnapshot snapshot = diagnostics.databaseMaintenanceSnapshot();
        assertTrue("database-scoped RawStore MVCC runtime must be active",
                snapshot.runtimeActive());
        assertEquals(DelosStorageMaintenanceSnapshot.RAWSTORE_MVCC_MODE,
                snapshot.storageMode());
        assertEquals(metadataContainerIds.length, snapshot.registeredTableCount());
        assertEquals("small isolation fixtures must retain every table observation",
                0L, snapshot.tableSnapshotDroppedCount());
        for (long metadataContainerId : metadataContainerIds) {
            assertTrue("runtime " + snapshot.databaseIdentity()
                            + " must own active metadata container " + metadataContainerId,
                    snapshot.tableSnapshots().stream().anyMatch(table ->
                            table.active()
                                    && table.metadataContainerId() == metadataContainerId));
        }
        return snapshot;
    }
}
