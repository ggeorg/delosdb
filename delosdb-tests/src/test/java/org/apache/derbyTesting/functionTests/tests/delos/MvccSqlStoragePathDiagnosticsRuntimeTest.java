/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlStoragePathDiagnosticsRuntimeTest

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
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** Runtime SQL proof for DelosDB storage path diagnostics. */
public final class MvccSqlStoragePathDiagnosticsRuntimeTest extends MvccSqlTestSupport {
    public void testOrderedEqualityAndRowIdPathsAreRecorded() throws Exception {
        String databaseName = databaseName("mvcc-storage-path-diagnostics-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table storage_path_diag_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into storage_path_diag_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into storage_path_diag_t values (2, 'beta', 'payload-2')");
            executeUpdate(connection, "insert into storage_path_diag_t values (3, 'gamma', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "STORAGE_PATH_DIAG_T");
            diagnostics.resetScanCountersForTesting();
            diagnostics.resetStoragePathDiagnosticsForTesting();

            assertRows(connection,
                    "select id, payload from storage_path_diag_t where code = 'beta'",
                    "2|payload-2");

            List<String> lines = diagnostics.storagePathDiagnosticLinesForTesting();
            assertFalse("ordered equality must not open an unused committed full scan: " + lines,
                    containsStoragePath(lines, "storagePath=MVCC_FULL_SCAN state=CHOSEN"));
            assertContainsStoragePath(lines, "storagePath=MVCC_ORDERED_EQUALITY_LOOKUP state=CHOSEN", containerId);
            assertContainsStoragePath(lines, "storagePath=MVCC_ROW_ID_LOOKUP state=CHOSEN", containerId);
            assertFalse("candidate-index diagnostics must not be recorded as normal SQL authority: " + lines,
                    containsStoragePath(lines, "storagePath=DIAGNOSTIC_CANDIDATE_PARITY_SCAN state=CHOSEN"));
            assertTrue("runtime diagnostics should still agree with the existing row-id fast path counter",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            connection.rollback();
        }

        shutdownDatabase(databaseName);
    }

    public void testSnapshotOrderedShortcutRejectionIsRecordedAsExplicitFallback() throws Exception {
        String databaseName = databaseName("mvcc-storage-path-snapshot-diagnostics-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table storage_path_snapshot_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "insert into storage_path_snapshot_t values (1, 'alpha', 'payload-1')");
            executeUpdate(setup, "insert into storage_path_snapshot_t values (2, 'beta', 'payload-2')");
            setup.commit();
            containerId = mvccContainerId(setup, "STORAGE_PATH_SNAPSHOT_T");
            setup.rollback();
        }

        try (Connection reader = openDatabase(databaseName, false);
                Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader,
                    "select id, payload from storage_path_snapshot_t where code = 'alpha'",
                    "1|payload-1");
            executeUpdate(writer,
                    "update storage_path_snapshot_t set code = 'omega', payload = 'payload-1-new' where id = 1");
            writer.commit();

            diagnostics.resetScanCountersForTesting();
            diagnostics.resetStoragePathDiagnosticsForTesting();
            assertRows(reader,
                    "select id, payload from storage_path_snapshot_t where code = 'alpha'",
                    "1|payload-1");

            List<String> lines = diagnostics.storagePathDiagnosticLinesForTesting();
            assertContainsStoragePath(lines, "storagePath=EXPLICIT_COMPATIBILITY_FALLBACK state=FALLBACK", containerId);
            assertContainsStoragePath(lines, "readMode=transaction-scoped-snapshot", containerId);
            assertFalse("stable snapshots must not claim an unsafe ordered-index shortcut: " + lines,
                    containsStoragePath(lines, "storagePath=MVCC_ORDERED_EQUALITY_LOOKUP state=CHOSEN"));
            assertEquals("stable snapshots must not use current-committed row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            reader.commit();
        }

        shutdownDatabase(databaseName);
    }

    private static void assertContainsStoragePath(List<String> lines, String marker, long containerId) {
        assertTrue("expected storage path diagnostic marker " + marker + " for container "
                        + containerId + " in " + lines,
                containsStoragePath(lines, marker, "container=" + containerId));
    }

    private static boolean containsStoragePath(List<String> lines, String marker) {
        for (String line : lines) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsStoragePath(List<String> lines, String marker, String secondMarker) {
        for (String line : lines) {
            if (line.contains(marker) && line.contains(secondMarker)) {
                return true;
            }
        }
        return false;
    }
}
