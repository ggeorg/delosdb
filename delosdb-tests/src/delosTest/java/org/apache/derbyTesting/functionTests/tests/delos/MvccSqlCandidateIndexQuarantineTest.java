/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlCandidateIndexQuarantineTest

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

/** Candidate-index quarantine gate after ordered-page equality/range promotion. */
public final class MvccSqlCandidateIndexQuarantineTest extends MvccSqlTestSupport {
    private static final String DIAGNOSTIC_FALLBACK_PROPERTY =
            "delosdb.mvcc.candidateIndex.diagnosticFallback";

    public void testCandidateIndexAuthorityIsQuarantinedBehindDiagnostics() throws Exception {
        try (SystemPropertyScope diagnosticFallbackProperty = clearSystemProperty(DIAGNOSTIC_FALLBACK_PROPERTY)) {
            assertCandidateIndexAuthorityIsQuarantinedBehindDiagnostics(diagnosticFallbackProperty);
        }
    }

    private static void assertCandidateIndexAuthorityIsQuarantinedBehindDiagnostics(
            SystemPropertyScope diagnosticFallbackProperty) throws Exception {
        String databaseName = databaseName("mvcc-candidate-index-quarantine-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table candidate_index_quarantine_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into candidate_index_quarantine_t values "
                    + "(10, 'theta', 'payload-theta')");
            executeUpdate(connection, "insert into candidate_index_quarantine_t values "
                    + "(20, 'alpha', 'payload-alpha')");
            executeUpdate(connection, "insert into candidate_index_quarantine_t values "
                    + "(30, 'gamma', 'payload-gamma')");
            executeUpdate(connection, "insert into candidate_index_quarantine_t values "
                    + "(40, 'beta', 'payload-beta')");
            executeUpdate(connection, "insert into candidate_index_quarantine_t values "
                    + "(50, 'zeta', 'payload-zeta')");
            connection.commit();

            containerId = mvccContainerId(connection, "CANDIDATE_INDEX_QUARANTINE_T");
            assertFalse("candidate-index fallback should be quarantined by default",
                    diagnostics.candidateIndexDiagnosticFallbackEnabledForTesting());
            assertTrue("candidate index should remain populated for diagnostics/fallback comparison",
                    diagnostics.candidateIndexKeyCountForTesting(0, containerId) > 0);
            assertTrue("ordered index pages should be populated for normal authority",
                    diagnostics.orderedIndexDistinctKeyCountForTesting(0, containerId) > 0);
            assertCandidateOrderedParityClean(diagnostics, containerId);

            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);

            assertRows(connection,
                    "select id, payload from candidate_index_quarantine_t where code = 'beta'",
                    "40|payload-beta");
            assertRows(connection,
                    "select code, id from candidate_index_quarantine_t "
                            + "where code >= 'beta' and code <= 'theta'",
                    "beta|40", "gamma|30", "theta|10");

            assertTrue("normal equality/range reads should use ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) >= lookupBefore + 2);
            assertTrue("covered equality/range reads should hit ordered index pages",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) >= hitBefore + 2);
            assertEquals("covered reads should not need ordered-index fallback",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertEquals("candidate-index fallback should remain cold on normal covered paths",
                    0, diagnostics.candidateIndexFallbackLookupCountForTesting());
            assertTrue("ordered authority should feed page-backed row-id reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("legacy snapshot fallback reads must remain closed",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot fallback scans must remain closed",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            assertCandidateOrderedParityClean(diagnostics, containerId);

            diagnosticFallbackProperty.set("true");
            assertFalse("candidate-index SQL authority should remain hard-quarantined "
                            + "even if the old diagnostic fallback property is set",
                    diagnostics.candidateIndexDiagnosticFallbackEnabledForTesting());
            assertCandidateOrderedParityClean(diagnostics, containerId);
            connection.rollback();
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
