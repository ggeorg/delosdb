/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlInMemoryWriteFrontQuarantineTest

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

/** SQL gate proving the inherited MVCC write-front is quarantined as a guarded shadow only. */
public final class MvccSqlInMemoryWriteFrontQuarantineTest extends MvccSqlTestSupport {
    public void testInheritedWriteFrontIsGuardedShadowOnly() throws Exception {
        String databaseName = databaseName("mvcc-write-front-quarantine-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        int startingProviderFirstWrites;
        int startingShadowWrites;
        int startingQuarantineViolations;
        int startingFailureRollbacks;
        int startingPhysicalVersions;
        int startingCandidateRebuilds;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table quarantine_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index quarantine_code_idx on quarantine_t(code)");
            connection.commit();

            containerId = mvccContainerId(connection, "QUARANTINE_T");
            startingProviderFirstWrites = diagnostics.providerFirstWriteAppendCountForTesting(0, containerId);
            startingShadowWrites = diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId);
            startingQuarantineViolations = diagnostics.legacyWriteFrontQuarantineViolationCountForTesting(0, containerId);
            startingFailureRollbacks = diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(0, containerId);
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    0);

            executeUpdate(connection, "insert into quarantine_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into quarantine_t values (2, 'code-2', 'payload-2')");
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    2);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2"),
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2"));
            connection.commit();

            startingPhysicalVersions = diagnostics.physicalVersionCountForTesting(0, containerId);
            startingCandidateRebuilds = diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId);

            executeUpdate(connection, "update quarantine_t set payload = 'payload-1-a' where id = 1");
            Savepoint savepoint = connection.setSavepoint("QUARANTINE_SP");
            executeUpdate(connection, "update quarantine_t set payload = 'rolled-back' where id = 1");
            executeUpdate(connection, "delete from quarantine_t where id = 2");
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    5);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|rolled-back",
                            "2|DELETE"),
                    List.of(
                            "1|UPSERT|1|code-1|rolled-back",
                            "2|DELETE"));

            connection.rollback(savepoint);
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    5);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of("1|UPSERT|1|code-1|payload-1-a"),
                    List.of("1|UPSERT|1|code-1|payload-1-a"));

            connection.commit();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("commit should persist only the surviving provider-first intent after savepoint rollback",
                    List.of("1|UPSERT|1|code-1|payload-1-a"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));
            assertEquals("commit should append one page-backed version for the surviving write intent",
                    startingPhysicalVersions + 1,
                    diagnostics.physicalVersionCountForTesting(0, containerId));
            assertTrue("candidate index should rebuild from page-backed committed rows after quarantined shadow writes",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId)
                            > startingCandidateRebuilds);
            assertEquals("legacy candidate-index rebuild path must remain quarantined",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));

            executeUpdate(connection, "delete from quarantine_t where id = 2");
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    6);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of("2|DELETE"),
                    List.of("2|DELETE"));
            connection.rollback();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertQuarantinedShadowCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    6);
            assertEquals("rollback must not append a page-backed delete version",
                    startingPhysicalVersions + 1,
                    diagnostics.physicalVersionCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, code, payload from quarantine_t order by id",
                    "1|code-1|payload-1-a",
                    "2|code-2|payload-2");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from quarantine_t where code = 'code-1'",
                    "1|payload-1-a");
            assertEquals("candidate index should resolve through page-backed committed rows",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("legacy snapshot read fallback must remain zero under write-front quarantine",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero under write-front quarantine",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "QUARANTINE_T");
            assertRows(reopened,
                    "select id, code, payload from quarantine_t order by id",
                    "1|code-1|payload-1-a",
                    "2|code-2|payload-2");
            assertEquals("shutdown/reopen must keep legacy snapshot read fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, reopenedContainerId));
            assertEquals("shutdown/reopen must keep legacy snapshot scan fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static void assertQuarantinedShadowCounts(
            DelosStorageDiagnostics diagnostics,
            long containerId,
            int startingProviderFirstWrites,
            int startingShadowWrites,
            int startingQuarantineViolations,
            int startingFailureRollbacks,
            int expectedSuccessfulWrites) {
        assertEquals("SQL writes should append provider intents before the inherited shadow can mutate",
                startingProviderFirstWrites + expectedSuccessfulWrites,
                diagnostics.providerFirstWriteAppendCountForTesting(0, containerId));
        int expectedShadowWrites = diagnostics.legacyWriteFrontShadowEnabledForTesting(0, containerId)
                ? startingShadowWrites + expectedSuccessfulWrites
                : startingShadowWrites;
        assertEquals("the inherited in-memory write front should either be bypassed or touched only as a shadow",
                expectedShadowWrites,
                diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId));
        assertEquals("normal SQL writes must not trip the inherited write-front quarantine guard",
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
