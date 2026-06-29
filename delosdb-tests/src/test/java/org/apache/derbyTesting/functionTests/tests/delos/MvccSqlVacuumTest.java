/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlVacuumTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for delos_mvcc vacuum behavior. */
public final class MvccSqlVacuumTest extends MvccSqlTestSupport {
    public void testMvccSqlInPlaceCompressVacuumPrunesUpdateVersionsAndPreservesVisibleRowAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-vacuum-update-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_vacuum_update_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_vacuum_update_t values (1, 'v1')");
            connection.commit();

            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v2' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v3' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection, "update mvcc_vacuum_update_t set name = 'v4' where id = 1"));
            connection.commit();

            DelosStorageDiagnostics diagnostics = mvccDiagnostics();
            long containerId = mvccContainerId(connection, "MVCC_VACUUM_UPDATE_T");
            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected multiple MVCC physical versions before vacuum, got " + versionsBeforeVacuum,
                    versionsBeforeVacuum >= 4);

            inPlaceCompressTable(connection, "MVCC_VACUUM_UPDATE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained SQL snapshot is active",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should prune at least one superseded update version",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertTrue("vacuum should reduce MVCC physical version count",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsBeforeVacuum);
            assertEquals("vacuum must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, name from mvcc_vacuum_update_t order by id",
                    "1|v4");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_vacuum_update_t order by id",
                    "1|v4");
        }
    }


    public void testMvccSqlInPlaceCompressVacuumPrunesDeletedRowsAndPreservesSurvivorAfterReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-vacuum-delete-db");

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_vacuum_delete_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (1, 'delete-me-1')");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (2, 'survivor')");
            executeUpdate(connection, "insert into mvcc_vacuum_delete_t values (3, 'delete-me-3')");
            connection.commit();

            assertEquals(2, executeUpdate(connection, "delete from mvcc_vacuum_delete_t where id in (1, 3)"));
            connection.commit();

            DelosStorageDiagnostics diagnostics = mvccDiagnostics();
            long containerId = mvccContainerId(connection, "MVCC_VACUUM_DELETE_T");
            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected deleted MVCC versions before vacuum, got " + versionsBeforeVacuum,
                    versionsBeforeVacuum >= 3);

            inPlaceCompressTable(connection, "MVCC_VACUUM_DELETE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained SQL snapshot is active",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should prune deleted physical versions",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertEquals("vacuum must preserve one logical survivor",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));
            assertTrue("vacuum should reduce MVCC physical version count",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsBeforeVacuum);

            assertRows(connection,
                    "select id, name from mvcc_vacuum_delete_t order by id",
                    "2|survivor");
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_vacuum_delete_t order by id",
                    "2|survivor");
        }
    }


    public void testMvccSqlVacuumPreservesActiveRepeatableReadSnapshotAndPrunesAfterReaderEnds() throws Exception {
        String databaseName = databaseName("mvcc-sql-vacuum-active-reader-db");

        long containerId;
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table mvcc_vacuum_active_reader_t "
                    + "(id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(setup, "insert into mvcc_vacuum_active_reader_t values (1, 'v1')");
            setup.commit();
            containerId = mvccContainerId(setup, "MVCC_VACUUM_ACTIVE_READER_T");
            setup.rollback();
        }

        try (Connection reader = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);

            assertRows(reader,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v1");

            assertEquals(1, executeUpdate(writer,
                    "update mvcc_vacuum_active_reader_t set name = 'v2' where id = 1"));
            writer.commit();
            assertEquals(1, executeUpdate(writer,
                    "update mvcc_vacuum_active_reader_t set name = 'v3' where id = 1"));
            writer.commit();
            assertEquals(1, executeUpdate(writer,
                    "update mvcc_vacuum_active_reader_t set name = 'v4' where id = 1"));
            writer.commit();
            assertEquals(1, executeUpdate(writer,
                    "update mvcc_vacuum_active_reader_t set name = 'v5' where id = 1"));
            writer.commit();

            assertRows(writer,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v5");

            int versionsBeforeActiveVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected multiple MVCC physical versions before active-snapshot vacuum, got "
                    + versionsBeforeActiveVacuum,
                    versionsBeforeActiveVacuum >= 5);

            inPlaceCompressTable(writer, "MVCC_VACUUM_ACTIVE_READER_T");
            writer.commit();

            assertRows(reader,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v1");
            assertRows(writer,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v5");

            int versionsWhileReaderActive = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("active snapshot vacuum must retain at least the old reader version and latest version, got "
                    + versionsWhileReaderActive,
                    versionsWhileReaderActive >= 2);
            assertEquals("vacuum with active reader must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            reader.rollback();

            inPlaceCompressTable(writer, "MVCC_VACUUM_ACTIVE_READER_T");
            writer.commit();

            assertFalse("vacuum should run after the retained SQL snapshot ends",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("final vacuum should prune at least one formerly snapshot-protected version",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertTrue("final vacuum should reduce versions after active reader ends",
                    diagnostics.physicalVersionCountForTesting(0, containerId) < versionsWhileReaderActive);
            assertEquals("final vacuum must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            assertRows(writer,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v5");
            writer.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_vacuum_active_reader_t where id = 1",
                    "1|v5");
        }
    }


}
