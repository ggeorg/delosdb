/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPurgeQueueTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL gate for the first MVCC purge-queue boundary. */
public final class MvccSqlPurgeQueueTest extends MvccSqlTestSupport {
    public void testVacuumEnqueuesAndDrainsObsoleteVersionsThroughPurgeQueue() throws Exception {
        String databaseName = databaseName("mvcc-purge-queue-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table purge_queue_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "PURGE_QUEUE_T");
            connection.rollback();

            executeUpdate(connection, "insert into purge_queue_t values (1, 'v1')");
            connection.commit();
            executeUpdate(connection, "update purge_queue_t set payload = 'v2' where id = 1");
            connection.commit();
            executeUpdate(connection, "update purge_queue_t set payload = 'v3' where id = 1");
            connection.commit();

            assertTrue("purge queue sidecar path should exist diagnostically",
                    diagnostics.purgeQueueFileForTesting(0, containerId) != null);
            assertEquals("purge queue should start drained",
                    0L, diagnostics.purgeQueuePendingCountForTesting(0, containerId));
            assertTrue("setup should create prunable versions before vacuum",
                    diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId) > 0L);
            long enqueuesBefore = diagnostics.purgeQueueEnqueueCountForTesting(0, containerId);
            long drainsBefore = diagnostics.purgeQueueDrainCountForTesting(0, containerId);

            inPlaceCompressTable(connection, "PURGE_QUEUE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained snapshot exists",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should remove obsolete versions",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) > 0);
            assertTrue("vacuum should enqueue obsolete versions before purge drain",
                    diagnostics.purgeQueueEnqueueCountForTesting(0, containerId) > enqueuesBefore);
            assertTrue("vacuum should drain the purge queue after page cleanup",
                    diagnostics.purgeQueueDrainCountForTesting(0, containerId) > drainsBefore);
            assertEquals("last purge drain should match the removed-version count",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId),
                    diagnostics.purgeQueueLastDrainCountForTesting(0, containerId));
            assertEquals("purge queue should be empty after successful vacuum",
                    0L, diagnostics.purgeQueuePendingCountForTesting(0, containerId));
            assertEquals("purge queue summaries should be empty after drain",
                    java.util.List.of(), diagnostics.purgeQueueEntrySummariesForTesting(0, containerId));
            assertTrue("purge queue sidecar should be durable even when drained",
                    Files.exists(diagnostics.purgeQueueFileForTesting(0, containerId)));
            assertEquals("legacy snapshot read fallback must remain zero after purge drain",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after purge drain",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, payload from purge_queue_t", "1|v3");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, payload from purge_queue_t", "1|v3");
            assertEquals("reopened purge queue should remain drained",
                    0L, diagnostics.purgeQueuePendingCountForTesting(0, containerId));
            assertEquals("reopened purge queue summaries should remain empty",
                    java.util.List.of(), diagnostics.purgeQueueEntrySummariesForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }
}
