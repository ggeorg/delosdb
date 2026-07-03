/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlOrderedIndexAuthorityCheckpointTest

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

/** Checkpoint gate for promoting ordered MVCC index pages over candidate indexes. */
public final class MvccSqlOrderedIndexAuthorityCheckpointTest extends MvccSqlTestSupport {
    public void testCurrentCommittedReadsPreferOrderedPagesAndKeepCandidateFallbackCold() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-authority-checkpoint-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_authority_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into ordered_index_authority_t values (10, 'theta', 'payload-theta')");
            executeUpdate(connection, "insert into ordered_index_authority_t values (20, 'alpha', 'payload-alpha')");
            executeUpdate(connection, "insert into ordered_index_authority_t values (30, 'gamma', 'payload-gamma')");
            executeUpdate(connection, "insert into ordered_index_authority_t values (40, 'beta', 'payload-beta')");
            executeUpdate(connection, "insert into ordered_index_authority_t values (50, 'zeta', 'payload-zeta')");
            connection.commit();

            containerId = mvccContainerId(connection, "ORDERED_INDEX_AUTHORITY_T");
            assertCandidateOrderedParityClean(diagnostics, containerId);
            assertTrue("candidate index should remain populated for diagnostic/fallback comparison",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId) > 0);
            assertTrue("ordered index pages should be populated before authority checkpoint reads",
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId) > 0);

            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);

            assertRows(connection,
                    "select id, payload from ordered_index_authority_t where code = 'beta'",
                    "40|payload-beta");
            assertRows(connection,
                    "select code, id from ordered_index_authority_t "
                            + "where code >= 'beta' and code <= 'theta'",
                    "beta|40", "gamma|30", "theta|10");

            assertTrue("normal equality/range reads should prefer ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) >= lookupBefore + 2);
            assertTrue("covered equality/range reads should hit ordered pages",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) >= hitBefore + 2);
            assertEquals("candidate fallback should stay cold for covered equality/range reads",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertEquals("actual candidate-index fallback should not be required on covered reads",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            assertTrue("ordered authority still feeds page-backed row-id reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("legacy snapshot fallback reads must remain closed",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot fallback scans must remain closed",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            assertCandidateOrderedParityClean(diagnostics, containerId);
            diagnostics.assertConsistentForTesting(0, containerId);

            assertDuplicateKey(() -> executeUpdate(connection,
                    "insert into ordered_index_authority_t values (40, 'duplicate', 'payload-duplicate')"));
            connection.rollback();
            assertRows(connection,
                    "select id, payload from ordered_index_authority_t where code = 'beta'",
                    "40|payload-beta");

            executeUpdate(connection, "update ordered_index_authority_t set code = 'delta' where id = 30");
            executeUpdate(connection, "delete from ordered_index_authority_t where id = 10");
            connection.commit();
            assertCandidateOrderedParityClean(diagnostics, containerId);

            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            assertRows(connection,
                    "select code, id from ordered_index_authority_t "
                            + "where code >= 'beta' and code <= 'epsilon'",
                    "beta|40", "delta|30");
            assertTrue("post-update range reads should still use ordered pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("post-update covered reads should not fall back to candidate indexes",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertEquals("post-update actual candidate fallback should stay cold",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertCandidateOrderedParityClean(diagnostics, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);

            assertRows(reopened,
                    "select code, id from ordered_index_authority_t "
                            + "where code >= 'beta' and code <= 'epsilon'",
                    "beta|40", "delta|30");
            assertTrue("reopened checkpoint reads should still use ordered pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("reopened covered reads should keep candidate fallback cold",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertEquals("reopened actual candidate-index fallback should stay cold",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    private static void assertCandidateOrderedParityClean(
            DelosStorageDiagnostics diagnostics,
            long containerId) {
        assertEquals("candidate/ordered parity checker should be clean: "
                        + diagnostics.orderedIndexCandidateParityErrorSummariesForTesting(0, containerId),
                0,
                diagnostics.orderedIndexCandidateParityErrorCountForTesting(0, containerId));
        assertTrue("candidate/ordered parity error summaries should be empty",
                diagnostics.orderedIndexCandidateParityErrorSummariesForTesting(0, containerId).isEmpty());
    }
}
