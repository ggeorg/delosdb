/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccDatabaseStorageSnapshotTest

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

import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Phase 9 proof for immutable database-scoped MVCC storage observations. */
public final class MvccDatabaseStorageSnapshotTest extends MvccSqlTestSupport {
    public void testSnapshotsAndCleanupRemainDatabaseScoped() throws Exception {
        String databaseA = databaseName("mvcc-database-snapshot-a");
        String databaseB = databaseName("mvcc-database-snapshot-b");
        DelosStorageDiagnostics diagnosticsA = mvccDiagnostics(databaseA);
        DelosStorageDiagnostics diagnosticsB = mvccDiagnostics(databaseB);

        Connection connectionA = openDatabase(databaseA, true);
        Connection connectionB = openDatabase(databaseB, true);
        boolean databaseAShutdown = false;
        try {
            connectionA.setAutoCommit(false);
            connectionB.setAutoCommit(false);
            executeUpdate(connectionA,
                    "create table snapshot_a (id int primary key, code varchar(32)) using delos_mvcc");
            executeUpdate(connectionB,
                    "create table snapshot_b (id int primary key, code varchar(32)) using delos_mvcc");
            connectionA.commit();
            connectionB.commit();

            diagnosticsA.resetMutationCountersForTesting();
            diagnosticsA.resetScanCountersForTesting();
            diagnosticsB.resetMutationCountersForTesting();
            diagnosticsB.resetScanCountersForTesting();

            executeUpdate(connectionA, "insert into snapshot_a values (1, 'alpha')");
            connectionA.commit();
            assertRows(connectionA,
                    "select id from snapshot_a where code = 'alpha'",
                    "1");
            connectionA.rollback();

            DelosDatabaseStorageSnapshot snapshotA = diagnosticsA.databaseStorageSnapshot();
            DelosDatabaseStorageSnapshot snapshotB = diagnosticsB.databaseStorageSnapshot();

            assertEquals(DelosDatabaseStorageSnapshot.CURRENT_SCHEMA_VERSION, snapshotA.schemaVersion());
            assertEquals(DelosDatabaseStorageSnapshot.WEAKLY_CONSISTENT_COLLECTION,
                    snapshotA.collectionSemantics());
            assertEquals(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, snapshotA.providerId());
            assertTrue(snapshotA.runtimeActive());
            assertTrue(snapshotB.runtimeActive());
            assertFalse("database identities must be explicit and distinct",
                    snapshotA.databaseIdentity().equals(snapshotB.databaseIdentity()));
            assertTrue("database A should own its mutation observations",
                    snapshotA.insertCount() > 0L);
            assertEquals(0L, snapshotB.insertCount());
            assertTrue("database A should own its scan observations", snapshotA.scanOpenCount() > 0L);
            assertEquals("database B must not observe database A scans", 0L, snapshotB.scanOpenCount());
            assertFalse("database A should own its path history",
                    snapshotA.storagePathDiagnostics().isEmpty());
            assertTrue("database B must not observe database A path history",
                    snapshotB.storagePathDiagnostics().isEmpty());
            assertTrue(snapshotA.storagePathDiagnostics().size()
                    <= snapshotA.storagePathDiagnosticCapacity());

            try {
                snapshotA.storagePathDiagnostics().clear();
                fail("snapshot path history must be immutable");
            } catch (UnsupportedOperationException expected) {
                // Expected immutable snapshot collection.
            }

            DelosDatabaseStorageSnapshot nextA = diagnosticsA.databaseStorageSnapshot();
            assertTrue("capture sequence must advance within one database runtime",
                    nextA.captureSequence() > snapshotA.captureSequence());

            connectionA.close();
            connectionA = null;
            shutdownDatabase(databaseA);
            databaseAShutdown = true;
            diagnosticsA.clearRuntimeStateForTesting();
            assertFalse(diagnosticsA.runtimeActiveForTesting());
            assertTrue("database-scoped cleanup must not close database B",
                    diagnosticsB.runtimeActiveForTesting());
            assertRows(connectionB, "select count(*) from snapshot_b", "0");
            connectionB.rollback();
        } finally {
            if (connectionA != null) {
                connectionA.close();
            }
            if (connectionB != null) {
                connectionB.close();
            }
            if (!databaseAShutdown) {
                shutdownDatabase(databaseA);
            }
            shutdownDatabase(databaseB);
        }
    }
}
