/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlProviderFirstWriteAppendTest

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

/** SQL gate proving write mutations append provider intents before touching the inherited write-front shadow. */
public final class MvccSqlProviderFirstWriteAppendTest extends MvccSqlTestSupport {
    public void testProviderFirstWriteAppendPathKeepsInheritedWriteFrontAsShadow() throws Exception {
        String databaseName = databaseName("mvcc-provider-first-write-append-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        int startingProviderFirstWrites;
        int startingShadowWrites;
        int startingFailureRollbacks;
        int initialPhysicalVersionCount;
        int initialCandidateRebuildCount;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table provider_first_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index provider_first_code_idx on provider_first_t(code)");
            connection.commit();

            containerId = mvccContainerId(connection, "PROVIDER_FIRST_T");
            startingProviderFirstWrites = diagnostics.providerFirstWriteAppendCountForTesting(0, containerId);
            startingShadowWrites = diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId);
            startingFailureRollbacks = diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(0, containerId);
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    0);

            executeUpdate(connection, "insert into provider_first_t values (1, 'code-1', 'payload-1')");
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    1);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of("1|UPSERT|1|code-1|payload-1"),
                    List.of("1|UPSERT|1|code-1|payload-1"));

            executeUpdate(connection, "insert into provider_first_t values (2, 'code-2', 'payload-2')");
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    2);
            connection.commit();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("initial commit should persist both provider-first write intents",
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));

            initialPhysicalVersionCount = diagnostics.physicalVersionCountForTesting(0, containerId);
            initialCandidateRebuildCount = diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId);

            executeUpdate(connection, "update provider_first_t set payload = 'payload-1-a' where id = 1");
            executeUpdate(connection, "update provider_first_t set payload = 'payload-1-b' where id = 1");
            executeUpdate(connection, "delete from provider_first_t where id = 2");
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    5);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE"),
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE"));

            Savepoint savepoint = connection.setSavepoint("PROVIDER_FIRST_SP");
            executeUpdate(connection, "update provider_first_t set payload = 'rolled-back-1' where id = 1");
            executeUpdate(connection, "insert into provider_first_t values (3, 'code-3', 'rolled-back')");
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    7);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE",
                            "1|UPSERT|1|code-1|rolled-back-1",
                            "3|UPSERT|3|code-3|rolled-back"),
                    List.of(
                            "1|UPSERT|1|code-1|rolled-back-1",
                            "2|DELETE",
                            "3|UPSERT|3|code-3|rolled-back"));

            connection.rollback(savepoint);
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    7);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE"),
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE"));

            connection.commit();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("commit should persist only final surviving provider-first intents",
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));
            assertEquals("provider-first commit should append only one final upsert and one delete tombstone",
                    initialPhysicalVersionCount + 2,
                    diagnostics.physicalVersionCountForTesting(0, containerId));
            assertTrue("candidate index should rebuild from page-backed committed rows after provider-first commit",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId)
                            > initialCandidateRebuildCount);
            assertEquals("candidate-index rebuild must not use the legacy committed-image path",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));
            assertEquals("legacy snapshot read fallback must remain quarantined",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain quarantined",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from provider_first_t where code = 'code-1'",
                    "1|payload-1-b");
            assertEquals("candidate index should resolve through page-backed committed rows after commit",
                    1, diagnostics.candidateIndexRowIdCountForTesting());

            executeUpdate(connection, "update provider_first_t set payload = 'rolled-back-after-commit' where id = 1");
            executeUpdate(connection, "insert into provider_first_t values (4, 'code-4', 'rolled-back')");
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    9);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|rolled-back-after-commit",
                            "4|UPSERT|4|code-4|rolled-back"),
                    List.of(
                            "1|UPSERT|1|code-1|rolled-back-after-commit",
                            "4|UPSERT|4|code-4|rolled-back"));
            connection.rollback();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("rollback must discard provider-first write intents without appending page-backed versions",
                    initialPhysicalVersionCount + 2,
                    diagnostics.physicalVersionCountForTesting(0, containerId));
            assertProviderFirstRuntimeCounts(
                    diagnostics,
                    containerId,
                    startingProviderFirstWrites,
                    startingShadowWrites,
                    startingFailureRollbacks,
                    9);

            assertRows(connection,
                    "select id, code, payload from provider_first_t order by id",
                    "1|code-1|payload-1-b");
            assertEquals("legacy snapshot read fallback must remain zero after rollback",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after rollback",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "PROVIDER_FIRST_T");
            assertRows(reopened,
                    "select id, code, payload from provider_first_t order by id",
                    "1|code-1|payload-1-b");
            assertEquals("shutdown/reopen must keep legacy snapshot read fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, reopenedContainerId));
            assertEquals("shutdown/reopen must keep legacy snapshot scan fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static void assertProviderFirstRuntimeCounts(
            DelosStorageDiagnostics diagnostics,
            long containerId,
            int startingProviderFirstWrites,
            int startingShadowWrites,
            int startingFailureRollbacks,
            int expectedSuccessfulWrites) {
        assertEquals("SQL write mutations should append provider intents first",
                startingProviderFirstWrites + expectedSuccessfulWrites,
                diagnostics.providerFirstWriteAppendCountForTesting(0, containerId));
        assertEquals("the inherited in-memory write front should be touched only as a shadow after provider append",
                startingShadowWrites + expectedSuccessfulWrites,
                diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId));
        assertEquals("successful SQL writes should not need provider-first failure rewind",
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
