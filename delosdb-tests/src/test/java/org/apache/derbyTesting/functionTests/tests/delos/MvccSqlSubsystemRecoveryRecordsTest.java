/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlSubsystemRecoveryRecordsTest

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

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageRecoveryDiagnostics;

/** SQL proof for MVCC subsystem recovery/checkpoint metadata records. */
public final class MvccSqlSubsystemRecoveryRecordsTest extends MvccSqlTestSupport {
    public void testSubsystemRecoveryRecordsExistAndSurviveReopen() throws Exception {
        String databaseName = databaseName("mvcc-sql-subsystem-recovery-records-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        String large = repeated('r', 24000);
        long containerId;
        long firstSequence;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_recovery_records_t "
                    + "(id int primary key, name varchar(32), payload varchar(32672)) using delos_mvcc");
            insertRow(connection, 1, "alpha", "small");
            insertRow(connection, 2, "beta", large);
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_RECOVERY_RECORDS_T");
            DelosStorageRecoveryDiagnostics recovery = diagnostics.recoveryDiagnosticsForTesting(0, containerId);
            assertTrue("recovery metadata file should exist", Files.exists(recovery.recordFile()));
            assertTrue("row page redo metadata should exist", recovery.hasRowPageRedoMetadata());
            assertTrue("index page redo metadata should exist", recovery.hasIndexPageRedoMetadata());
            assertTrue("overflow page redo metadata should exist", recovery.hasOverflowPageRedoMetadata());
            assertTrue("free-space map redo metadata should exist", recovery.hasFreeSpaceMapRedoMetadata());
            assertTrue("transaction outcome redo metadata should exist", recovery.hasTransactionOutcomeRedoMetadata());
            assertTrue("checkpoint metadata should exist", recovery.hasCheckpointMetadata());
            assertTrue("complete checkpoint boundary should be advertised", recovery.completeCheckpointBoundary());
            assertFalse("record summaries should be exposed", recovery.recordSummaries().isEmpty());
            assertRows(connection,
                    "select id, name, length(payload) from mvcc_recovery_records_t order by id",
                    "1|alpha|5",
                    "2|beta|" + large.length());
            diagnostics.assertConsistentForTesting(0, containerId);
            firstSequence = recovery.lastSequence();
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_RECOVERY_RECORDS_T");
            assertRows(reopened,
                    "select id, name, length(payload) from mvcc_recovery_records_t order by id",
                    "1|alpha|5",
                    "2|beta|" + large.length());
            DelosStorageRecoveryDiagnostics reopenedRecovery = diagnostics.recoveryDiagnosticsForTesting(
                    0, reopenedContainerId);
            assertTrue("reopen should preserve recovery metadata records",
                    reopenedRecovery.recordCount() >= 6L);
            assertTrue("reopen should preserve monotonic recovery sequence",
                    reopenedRecovery.lastSequence() >= firstSequence);
            assertTrue("reopen recovery metadata should still be complete",
                    reopenedRecovery.completeCheckpointBoundary());
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
        }
    }

    private static void insertRow(Connection connection, int id, String name, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_recovery_records_t values (?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setString(3, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String repeated(char value, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
