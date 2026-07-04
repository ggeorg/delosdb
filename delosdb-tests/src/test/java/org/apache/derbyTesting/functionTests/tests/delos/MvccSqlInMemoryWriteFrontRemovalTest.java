/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlInMemoryWriteFrontRemovalTest

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
import java.sql.Savepoint;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL closeout gate proving the inherited in-memory MVCC write front is no longer reachable. */
public final class MvccSqlInMemoryWriteFrontRemovalTest extends MvccSqlTestSupport {
    private static final String LEGACY_WRITE_FRONT_SHADOW_PROPERTY = "delosdb.mvcc.legacyWriteFrontShadow";

    public void testRemovedInheritedWriteFrontCannotBeReenabledByProperty() throws Exception {
        try (SystemPropertyScope ignored = setSystemProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY, "true")) {
            assertRemovedInheritedWriteFrontCannotBeReenabledByProperty();
        }
    }

    private void assertRemovedInheritedWriteFrontCannotBeReenabledByProperty() throws Exception {
        String databaseName = databaseName("mvcc-write-front-removal-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        int startingProviderWrites;
        int startingShadowWrites;
        int startingShadowBypasses;
        int startingQuarantineViolations;
        int startingFailureRollbacks;
        int startingPhysicalVersions;
        int startingCandidateRebuilds;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table write_front_removal_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index write_front_removal_code_idx on write_front_removal_t(code)");
            connection.commit();

            containerId = mvccContainerId(connection, "WRITE_FRONT_REMOVAL_T");
            assertFalse("removed inherited write front must not be re-enabled by the old shadow property",
                    diagnostics.legacyWriteFrontShadowEnabledForTesting(0, containerId));
            startingProviderWrites = diagnostics.providerFirstWriteAppendCountForTesting(0, containerId);
            startingShadowWrites = diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId);
            startingShadowBypasses = diagnostics.legacyWriteFrontShadowBypassCountForTesting(0, containerId);
            startingQuarantineViolations = diagnostics.legacyWriteFrontQuarantineViolationCountForTesting(0, containerId);
            startingFailureRollbacks = diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(0, containerId);
            startingPhysicalVersions = diagnostics.physicalVersionCountForTesting(0, containerId);
            startingCandidateRebuilds = diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId);

            executeUpdate(connection, "insert into write_front_removal_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into write_front_removal_t values (2, 'code-2', 'payload-2')");
            executeUpdate(connection, "update write_front_removal_t set payload = 'payload-1-live' where id = 1");
            Savepoint savepoint = connection.setSavepoint("WRITE_FRONT_REMOVAL_SP");
            executeUpdate(connection, "update write_front_removal_t set payload = 'rolled-back' where id = 1");
            executeUpdate(connection, "delete from write_front_removal_t where id = 2");
            assertRemovedWriteFrontCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    5);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2",
                            "1|UPSERT|1|code-1|payload-1-live",
                            "1|UPSERT|1|code-1|rolled-back",
                            "2|DELETE"),
                    List.of(
                            "1|UPSERT|1|code-1|rolled-back",
                            "2|DELETE"));

            connection.rollback(savepoint);
            assertRemovedWriteFrontCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    5);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2",
                            "1|UPSERT|1|code-1|payload-1-live"),
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-live",
                            "2|UPSERT|2|code-2|payload-2"));

            connection.commit();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("commit should persist only surviving provider intents after write-front removal",
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-live",
                            "2|UPSERT|2|code-2|payload-2"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));
            assertEquals("commit should append one page-backed version per surviving provider intent",
                    startingPhysicalVersions + 2,
                    diagnostics.physicalVersionCountForTesting(0, containerId));
            assertTrue("candidate index should rebuild from page-backed committed rows after removed write-front writes",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId)
                            > startingCandidateRebuilds);
            assertEquals("legacy candidate-index rebuild path must remain quarantined",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));
            assertEquals("removed inherited write front must not materialize shadow mutations",
                    startingShadowWrites,
                    diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId));

            executeUpdate(connection, "delete from write_front_removal_t where id = 2");
            assertRemovedWriteFrontCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    6);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of("2|DELETE"),
                    List.of("2|DELETE"));
            connection.rollback();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("rollback must discard provider intents without appending page-backed versions",
                    startingPhysicalVersions + 2,
                    diagnostics.physicalVersionCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, code, payload from write_front_removal_t order by id",
                    "1|code-1|payload-1-live",
                    "2|code-2|payload-2");
            assertEquals("page-backed committed rows should be the only SQL authority",
                    List.of(
                            "1|1|code-1|payload-1-live",
                            "2|2|code-2|payload-2"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from write_front_removal_t where code = 'code-1'",
                    "1|payload-1-live");
            assertEquals("candidate index should resolve through page-backed committed rows",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("legacy snapshot read fallback must remain zero after write-front removal",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after write-front removal",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "WRITE_FRONT_REMOVAL_T");
            assertFalse("removed inherited write front must remain disabled after reopen",
                    diagnostics.legacyWriteFrontShadowEnabledForTesting(0, reopenedContainerId));
            assertRows(reopened,
                    "select id, code, payload from write_front_removal_t order by id",
                    "1|code-1|payload-1-live",
                    "2|code-2|payload-2");
            assertEquals("shutdown/reopen must keep legacy snapshot read fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, reopenedContainerId));
            assertEquals("shutdown/reopen must keep legacy snapshot scan fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static void assertRemovedWriteFrontCounts(
            DelosStorageDiagnostics diagnostics,
            long containerId,
            int startingProviderWrites,
            int startingShadowWrites,
            int startingShadowBypasses,
            int startingQuarantineViolations,
            int startingFailureRollbacks,
            int expectedSuccessfulWrites) {
        assertEquals("SQL writes should append provider intents before the removed write-front boundary",
                startingProviderWrites + expectedSuccessfulWrites,
                diagnostics.providerFirstWriteAppendCountForTesting(0, containerId));
        assertEquals("removed inherited write front must not receive shadow mutations",
                startingShadowWrites,
                diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId));
        assertEquals("each provider-first SQL write should cross the removed write-front bypass boundary",
                startingShadowBypasses + expectedSuccessfulWrites,
                diagnostics.legacyWriteFrontShadowBypassCountForTesting(0, containerId));
        assertEquals("normal SQL writes must not trip the removed write-front quarantine guard",
                startingQuarantineViolations,
                diagnostics.legacyWriteFrontQuarantineViolationCountForTesting(0, containerId));
        assertEquals("normal SQL writes should not need provider-first failure rewind",
                startingFailureRollbacks,
                diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(0, containerId));
    }

    private static void assertActiveProviderAppendState(
            DelosStorageDiagnostics diagnostics,
            long containerId,
            List<String> expectedAppended,
            List<String> expectedSurviving) {
        assertEquals("provider append stream should match SQL mutations before commit",
                expectedAppended,
                diagnostics.activeProviderWriteAppendPayloadSummariesForTesting(0, containerId));
        assertEquals("provider append count should match SQL mutations before commit",
                expectedAppended.size(),
                diagnostics.activeProviderWriteAppendCountForTesting(0, containerId));
        assertEquals("provider surviving intent set should collapse same-row mutations",
                expectedSurviving,
                diagnostics.activeProviderSurvivingWriteIntentPayloadSummariesForTesting(0, containerId));
        assertEquals("provider surviving intent count should collapse same-row mutations",
                expectedSurviving.size(),
                diagnostics.activeProviderSurvivingWriteIntentCountForTesting(0, containerId));
    }
}
