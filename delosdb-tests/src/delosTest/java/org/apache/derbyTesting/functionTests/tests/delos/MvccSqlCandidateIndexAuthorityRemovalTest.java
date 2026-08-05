/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlCandidateIndexAuthorityRemovalTest

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

/** Closeout gate for removing candidate indexes as MVCC SQL read authority. */
public final class MvccSqlCandidateIndexAuthorityRemovalTest extends MvccSqlTestSupport {
    private static final String DIAGNOSTIC_FALLBACK_PROPERTY =
            "delosdb.mvcc.candidateIndex.diagnosticFallback";

    public void testCandidateIndexIsNotSqlAuthorityEvenWhenLegacyPropertyIsSet() throws Exception {
        try (SystemPropertyScope ignored = setSystemProperty(DIAGNOSTIC_FALLBACK_PROPERTY, "true")) {
            assertCandidateIndexIsNotSqlAuthorityEvenWhenLegacyPropertyIsSet();
        }
    }

    private static void assertCandidateIndexIsNotSqlAuthorityEvenWhenLegacyPropertyIsSet() throws Exception {
        String databaseName = databaseName("mvcc-candidate-index-authority-removal-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table candidate_index_authority_removal_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into candidate_index_authority_removal_t values "
                    + "(10, 'theta', 'payload-theta')");
            executeUpdate(connection, "insert into candidate_index_authority_removal_t values "
                    + "(20, 'alpha', 'payload-alpha')");
            executeUpdate(connection, "insert into candidate_index_authority_removal_t values "
                    + "(30, 'gamma', 'payload-gamma')");
            executeUpdate(connection, "insert into candidate_index_authority_removal_t values "
                    + "(40, 'beta', 'payload-beta')");
            executeUpdate(connection, "insert into candidate_index_authority_removal_t values "
                    + "(50, 'zeta', 'payload-zeta')");
            connection.commit();

            containerId = mvccContainerId(connection, "CANDIDATE_INDEX_AUTHORITY_REMOVAL_T");
            assertFalse("legacy candidate-index fallback property must no longer enable SQL authority",
                    diagnostics.candidateIndexDiagnosticFallbackEnabledForTesting());
            assertTrue("candidate index remains populated for parity diagnostics only",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId) > 0);
            assertTrue("ordered index pages are the current-committed authority",
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId) > 0);
            assertCandidateOrderedParityClean(diagnostics, containerId);

            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            long orderedFallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);

            assertRows(connection,
                    "select id, payload from candidate_index_authority_removal_t where code = 'beta'",
                    "40|payload-beta");
            assertRows(connection,
                    "select code, id from candidate_index_authority_removal_t "
                            + "where code >= 'beta' and code <= 'theta'",
                    "beta|40", "gamma|30", "theta|10");
            assertRows(connection,
                    "select id from candidate_index_authority_removal_t where code = 'missing'");

            assertTrue("normal equality/range reads should use ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) >= lookupBefore + 3);
            assertTrue("covered equality/range reads should hit ordered index pages",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) >= hitBefore + 2);
            assertEquals("candidate-index fallback must be removed as SQL authority",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            assertEquals("covered reads should not need ordered fallback",
                    orderedFallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertTrue("ordered authority should feed page-backed row-id reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("legacy snapshot fallback reads must remain closed",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot fallback scans must remain closed",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertCandidateOrderedParityClean(diagnostics, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertFalse("legacy candidate-index fallback property must remain ignored after reopen",
                    diagnostics.candidateIndexDiagnosticFallbackEnabledForTesting());
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);

            assertRows(reopened,
                    "select code, id from candidate_index_authority_removal_t "
                            + "where code >= 'beta' and code <= 'theta'",
                    "beta|40", "gamma|30", "theta|10");
            assertTrue("reopened reads should still use ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("candidate-index fallback must stay removed after reopen",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);
            assertCandidateOrderedParityClean(diagnostics, containerId);
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
