/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageLocalPruningTest

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

/** SQL gate for first one-page MVCC pruning. */
public final class MvccSqlPageLocalPruningTest extends MvccSqlTestSupport {
    public void testVacuumPrunesSingleEligiblePageWithoutFullTableRewrite() throws Exception {
        String databaseName = databaseName("mvcc-page-local-pruning-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table page_local_prune_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "PAGE_LOCAL_PRUNE_T");
            connection.rollback();

            executeUpdate(connection, "insert into page_local_prune_t values (1, 'v1')");
            connection.commit();
            executeUpdate(connection, "update page_local_prune_t set payload = 'v2' where id = 1");
            connection.commit();
            executeUpdate(connection, "update page_local_prune_t set payload = 'v3' where id = 1");
            connection.commit();

            assertTrue("the setup should produce an eligible prunable page",
                    diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId) > 0L);
            long pageCountBefore = diagnostics.pageCountForTesting(0, containerId);
            long freeBytesBefore = diagnostics.freeSpaceMapMaxFreeBytesForTesting(0, containerId);
            long localPrunesBefore = diagnostics.pageLocalPruneSuccessCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.pageLocalPruneFallbackCountForTesting(0, containerId);

            inPlaceCompressTable(connection, "PAGE_LOCAL_PRUNE_T");
            connection.commit();

            assertFalse("vacuum should run when no retained snapshot exists",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("page-local pruning should remove obsolete versions",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) > 0);
            assertTrue("vacuum should attempt page-local pruning",
                    diagnostics.pageLocalPruneAttemptCountForTesting(0, containerId) > 0L);
            assertTrue("one eligible page should be locally pruned",
                    diagnostics.pageLocalPruneSuccessCountForTesting(0, containerId) > localPrunesBefore);
            assertEquals("single-page setup should not need the full-table vacuum rewrite fallback",
                    fallbackBefore, diagnostics.pageLocalPruneFallbackCountForTesting(0, containerId));
            assertTrue("page-local pruning should report removed versions",
                    diagnostics.pageLocalPruneRemovedVersionCountForTesting(0, containerId) > 0L);
            assertEquals("page-local pruning should not change the page volume size",
                    pageCountBefore, diagnostics.pageCountForTesting(0, containerId));
            assertTrue("page-local pruning should free local page space and update the FSM",
                    diagnostics.freeSpaceMapMaxFreeBytesForTesting(0, containerId) >= freeBytesBefore);
            assertEquals("locally pruned image should clear prunable page markings",
                    0L, diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId));
            assertEquals("legacy snapshot read fallback must remain zero after page-local pruning",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after page-local pruning",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, payload from page_local_prune_t", "1|v3");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, payload from page_local_prune_t", "1|v3");
            assertEquals("reopened locally pruned image should remain non-prunable",
                    0L, diagnostics.visibilityMapPrunablePageCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }
}
