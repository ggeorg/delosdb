/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlFreeSpaceMapTest

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

/** SQL gate for the first MVCC free-space map allocation path. */
public final class MvccSqlFreeSpaceMapTest extends MvccSqlTestSupport {
    public void testFreeSpaceMapTracksAndRoutesPartialPageReuse() throws Exception {
        String databaseName = databaseName("mvcc-free-space-map-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table free_space_map_t "
                    + "(id int primary key, payload varchar(7000)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "FREE_SPACE_MAP_T");
            connection.rollback();

            insertPayload(connection, 1, repeated('A', 6200));
            insertPayload(connection, 2, repeated('B', 6200));
            connection.commit();

            assertTrue("free-space map sidecar should be created with the page-backed table",
                    Files.exists(diagnostics.freeSpaceMapFileForTesting(0, containerId)));
            assertTrue("two large rows should force more than one page before partial-page reuse proof",
                    diagnostics.pageCountForTesting(0, containerId) >= 2L);
            assertEquals("free-space map should track every MVCC data page",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.freeSpaceMapPageCountForTesting(0, containerId));
            assertTrue("free-space map should be updated by durable writes",
                    diagnostics.freeSpaceMapUpdateCountForTesting(0, containerId) >= 2L);
            assertTrue("free-space map should expose page free-byte summaries",
                    diagnostics.freeSpaceMapPageSummariesForTesting(0, containerId).stream()
                            .anyMatch(summary -> summary.startsWith("0:")));

            long nonLastHitsBeforeSmallInsert = diagnostics.freeSpaceMapNonLastHitCountForTesting(0, containerId);
            insertPayload(connection, 3, "small-payload");
            connection.commit();

            assertTrue("small row should be routed to an earlier partially-free page by the free-space map",
                    diagnostics.freeSpaceMapNonLastHitCountForTesting(0, containerId) > nonLastHitsBeforeSmallInsert);
            assertTrue("free-space map should be consulted for MVCC page allocation",
                    diagnostics.freeSpaceMapLookupCountForTesting(0, containerId) > 0L);
            assertTrue("free-space map should produce allocation hits",
                    diagnostics.freeSpaceMapHitCountForTesting(0, containerId) > 0L);
            assertEquals("free-space map must remain synchronized with the page volume after reuse",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.freeSpaceMapPageCountForTesting(0, containerId));
            assertEquals("legacy snapshot read fallback must remain zero after free-space-map writes",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after free-space-map writes",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, length(payload) from free_space_map_t order by id",
                    "1|6200", "2|6200", "3|13");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, payload from free_space_map_t where id = 3", "3|small-payload");
            assertTrue("reopen should rebuild/reconcile the free-space map from page images",
                    diagnostics.freeSpaceMapRebuildCountForTesting(0, containerId) > 0L);
            assertEquals("reopened free-space map should still track every MVCC data page",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.freeSpaceMapPageCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    private static void insertPayload(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into free_space_map_t values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String repeated(char value, int count) {
        return String.valueOf(value).repeat(count);
    }
}
