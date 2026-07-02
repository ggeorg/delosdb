/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlVisibilityPruneMapTest

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

/** SQL gate for the first MVCC page visibility/prune map. */
public final class MvccSqlVisibilityPruneMapTest extends MvccSqlTestSupport {
    public void testVisibilityPruneMapTracksPrunableAndAllVisiblePagesAcrossVacuumAndReopen() throws Exception {
        String databaseName = databaseName("mvcc-visibility-prune-map-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table visibility_prune_map_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "VISIBILITY_PRUNE_MAP_T");
            connection.rollback();

            executeUpdate(connection, "insert into visibility_prune_map_t values (1, 'v1')");
            executeUpdate(connection, "insert into visibility_prune_map_t values (2, 'delete-me')");
            executeUpdate(connection, "insert into visibility_prune_map_t values (3, 'stable')");
            connection.commit();

            assertTrue("visibility/prune map sidecar should be created with the page-backed table",
                    Files.exists(diagnostics.visibilityMapFileForTesting(0, containerId)));
            assertEquals("visibility map should initially track every MVCC data page",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.visibilityMapPageCountForTesting(0, containerId));
            assertTrue("fresh committed insert pages should be all-visible before obsolete versions exist",
                    diagnostics.visibilityMapAllVisiblePageCountForTesting(0, containerId) > 0L);

            assertEquals(1, executeUpdate(connection,
                    "update visibility_prune_map_t set payload = 'v2' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection,
                    "update visibility_prune_map_t set payload = 'v3' where id = 1"));
            connection.commit();
            assertEquals(1, executeUpdate(connection,
                    "delete from visibility_prune_map_t where id = 2"));
            connection.commit();

            assertTrue("updates should mark at least one page as containing old versions",
                    diagnostics.visibilityMapOldVersionPageCountForTesting(0, containerId) > 0L);
            assertTrue("updates/deletes should mark at least one page prunable",
                    diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId) > 0L);
            assertTrue("delete should mark at least one page with tombstones",
                    diagnostics.visibilityMapTombstonePageCountForTesting(0, containerId) > 0L);
            assertTrue("visibility map should expose page summaries",
                    diagnostics.visibilityMapPageSummariesForTesting(0, containerId).stream()
                            .anyMatch(summary -> summary.contains("prunable")));
            assertTrue("visibility map should be updated by durable writes",
                    diagnostics.visibilityMapUpdateCountForTesting(0, containerId) > 0L);
            diagnostics.assertConsistentForTesting(0, containerId);

            inPlaceCompressTable(connection, "VISIBILITY_PRUNE_MAP_T");
            connection.commit();

            assertFalse("vacuum should run when no retained snapshot exists",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should remove obsolete versions before page-local pruning work starts",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) > 0);
            assertEquals("vacuum should clear prunable page markings for the compacted image",
                    0L, diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId));
            assertEquals("vacuum should clear tombstone page markings after deleted logical rows are removed",
                    0L, diagnostics.visibilityMapTombstonePageCountForTesting(0, containerId));
            assertTrue("vacuumed survivor pages should be all-visible",
                    diagnostics.visibilityMapAllVisiblePageCountForTesting(0, containerId) > 0L);
            assertEquals("visibility map must remain synchronized with the page volume after vacuum",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.visibilityMapPageCountForTesting(0, containerId));
            assertEquals("legacy snapshot read fallback must remain zero after visibility-map writes",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after visibility-map writes",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection,
                    "select id, payload from visibility_prune_map_t order by id",
                    "1|v3", "3|stable");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from visibility_prune_map_t order by id",
                    "1|v3", "3|stable");
            assertTrue("reopen should rebuild/reconcile the visibility/prune map from page images",
                    diagnostics.visibilityMapRebuildCountForTesting(0, containerId) > 0L);
            assertEquals("reopened visibility map should still track every MVCC data page",
                    diagnostics.pageCountForTesting(0, containerId),
                    diagnostics.visibilityMapPageCountForTesting(0, containerId));
            assertEquals("reopened compacted image should not have prunable page markings",
                    0L, diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }
}
