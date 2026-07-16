/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedCandidateIndexRebuildTest

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

/** SQL gate proving MVCC candidate-index refresh is sourced from page-backed committed rows. */
public final class MvccSqlPageBackedCandidateIndexRebuildTest extends MvccSqlTestSupport {
    public void testCandidateIndexRebuildsFromPageBackedCommittedRows() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-candidate-index-rebuild-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;
        int initialPageBackedRebuildCount;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table page_backed_candidate_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index page_backed_candidate_code_idx "
                    + "on page_backed_candidate_t(code)");
            executeUpdate(connection, "insert into page_backed_candidate_t values (1, 'old-code', 'payload-1')");
            executeUpdate(connection, "insert into page_backed_candidate_t values (2, 'delete-code', 'payload-2')");
            connection.commit();

            containerId = mvccContainerId(connection, "PAGE_BACKED_CANDIDATE_T");
            initialPageBackedRebuildCount = diagnostics.pageBackedCandidateIndexRebuildCountForTesting(
                    0, containerId);
            assertTrue("initial commit should rebuild the candidate index from page-backed committed rows",
                    initialPageBackedRebuildCount > 0);
            assertEquals("candidate index must not use the retired in-memory committed-image rebuild path",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, code from page_backed_candidate_t where code = 'old-code'",
                    "1|old-code");
            assertEquals("fresh page-backed candidate key should narrow to one row id",
                    1, diagnostics.candidateIndexRowIdCountForTesting());

            executeUpdate(connection,
                    "update page_backed_candidate_t set code = 'new-code', payload = 'payload-1-new' "
                            + "where id = 1");
            executeUpdate(connection, "delete from page_backed_candidate_t where id = 2");
            executeUpdate(connection, "insert into page_backed_candidate_t values (3, 'fresh-code', 'payload-3')");
            connection.commit();

            assertTrue("changed commit should rebuild candidate index from page-backed committed rows",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId)
                            > initialPageBackedRebuildCount);
            assertEquals("legacy candidate-index rebuild path must remain unused after changed commits",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));
            assertRows(connection,
                    "select id, code, payload from page_backed_candidate_t order by id",
                    "1|new-code|payload-1-new",
                    "3|fresh-code|payload-3");

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from page_backed_candidate_t where code = 'old-code'");
            assertEquals("stale updated key should remain a known empty candidate lookup",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stale updated key must not retain the old row id after page-backed rebuild",
                    0, diagnostics.candidateIndexRowIdCountForTesting());

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from page_backed_candidate_t where code = 'delete-code'");
            assertEquals("stale deleted key should remain a known empty candidate lookup",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stale deleted key must not retain the deleted row id after page-backed rebuild",
                    0, diagnostics.candidateIndexRowIdCountForTesting());

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id from page_backed_candidate_t where code = 'fresh-code'",
                    "3");
            assertEquals("fresh inserted key should resolve through the rebuilt candidate index",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "PAGE_BACKED_CANDIDATE_T");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(reopened,
                    "select id, payload from page_backed_candidate_t where code = 'new-code'",
                    "1|payload-1-new");
            assertEquals("hydrated candidate index should be rebuilt from page-backed committed rows",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertTrue("reopened table should rebuild candidate index from page-backed durable rows",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, reopenedContainerId) > 0);
            assertEquals("reopened table must not use the retired in-memory committed-image rebuild path",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }
}
