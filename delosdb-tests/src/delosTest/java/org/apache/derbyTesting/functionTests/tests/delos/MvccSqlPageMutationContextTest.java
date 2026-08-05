/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageMutationContextTest

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

/** SQL gate for the first MVCC page mutation context boundary. */
public final class MvccSqlPageMutationContextTest extends MvccSqlTestSupport {
    public void testAppendAndPageLocalPruneUsePageMutationContext() throws Exception {
        String databaseName = databaseName("mvcc-page-mutation-context-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table page_mutation_context_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            connection.commit();
            containerId = mvccContainerId(connection, "PAGE_MUTATION_CONTEXT_T");
            connection.rollback();

            executeUpdate(connection, "insert into page_mutation_context_t values (1, 'v1')");
            connection.commit();

            assertTrue("insert should begin at least one MVCC page mutation context",
                    diagnostics.pageMutationContextBeginCountForTesting(0, containerId) > 0L);
            assertEquals("insert contexts should close cleanly",
                    diagnostics.pageMutationContextBeginCountForTesting(0, containerId),
                    diagnostics.pageMutationContextCommitCountForTesting(0, containerId));
            assertEquals("insert contexts should not abort",
                    0L, diagnostics.pageMutationContextAbortCountForTesting(0, containerId));
            assertTrue("insert should reserve page capacity through the mutation context",
                    diagnostics.pageMutationContextPageReservationCountForTesting(0, containerId) > 0L);
            assertTrue("insert should record reserved bytes through the mutation context",
                    diagnostics.pageMutationContextReservedBytesForTesting(0, containerId) > 0L);
            assertTrue("insert should write pages through the mutation context",
                    diagnostics.pageMutationContextPageWriteCountForTesting(0, containerId) > 0L);
            assertTrue("insert should update the free-space map through the mutation context",
                    diagnostics.pageMutationContextFreeSpaceMapUpdateCountForTesting(0, containerId) > 0L);
            assertEquals("last transaction append should be identified by the context",
                    "append-transaction-batch",
                    diagnostics.lastPageMutationContextOperationForTesting(0, containerId));

            executeUpdate(connection, "update page_mutation_context_t set payload = 'v2' where id = 1");
            connection.commit();
            executeUpdate(connection, "update page_mutation_context_t set payload = 'v3' where id = 1");
            connection.commit();

            long beginBeforePrune = diagnostics.pageMutationContextBeginCountForTesting(0, containerId);
            long pageWritesBeforePrune = diagnostics.pageMutationContextPageWriteCountForTesting(0, containerId);
            long freeSpaceUpdatesBeforePrune = diagnostics.pageMutationContextFreeSpaceMapUpdateCountForTesting(0, containerId);
            long reusableIndexUpdatesBeforePrune = diagnostics.pageMutationContextReusableIndexUpdateCountForTesting(
                    0, containerId);
            long localPrunesBefore = diagnostics.pageLocalPruneSuccessCountForTesting(0, containerId);

            inPlaceCompressTable(connection, "PAGE_MUTATION_CONTEXT_T");
            connection.commit();

            assertFalse("vacuum should run when no retained snapshot exists",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("setup should use the page-local prune path",
                    diagnostics.pageLocalPruneSuccessCountForTesting(0, containerId) > localPrunesBefore);
            assertTrue("page-local prune should begin another page mutation context",
                    diagnostics.pageMutationContextBeginCountForTesting(0, containerId) > beginBeforePrune);
            assertEquals("all page mutation contexts should commit cleanly",
                    diagnostics.pageMutationContextBeginCountForTesting(0, containerId),
                    diagnostics.pageMutationContextCommitCountForTesting(0, containerId));
            assertEquals("no page mutation context should abort on the green path",
                    0L, diagnostics.pageMutationContextAbortCountForTesting(0, containerId));
            assertTrue("page-local prune should write the pruned page through the context",
                    diagnostics.pageMutationContextPageWriteCountForTesting(0, containerId) > pageWritesBeforePrune);
            assertTrue("page-local prune should update the FSM through the context",
                    diagnostics.pageMutationContextFreeSpaceMapUpdateCountForTesting(0, containerId)
                            > freeSpaceUpdatesBeforePrune);
            assertTrue("page-local prune should update the reusable-page index through the context",
                    diagnostics.pageMutationContextReusableIndexUpdateCountForTesting(0, containerId)
                            > reusableIndexUpdatesBeforePrune);
            assertEquals("last local pruning operation should be identified by the context",
                    "rewrite-page", diagnostics.lastPageMutationContextOperationForTesting(0, containerId));
            assertEquals("legacy snapshot read fallback must remain zero after mutation-context writes",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after mutation-context writes",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection, "select id, payload from page_mutation_context_t", "1|v3");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, payload from page_mutation_context_t", "1|v3");
            assertEquals("reopened table should remain clean after mutation-context writes",
                    0, diagnostics.consistencyErrorCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }
}
