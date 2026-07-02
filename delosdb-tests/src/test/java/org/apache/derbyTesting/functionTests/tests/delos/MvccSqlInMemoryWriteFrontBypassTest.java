/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlInMemoryWriteFrontBypassTest

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

/** SQL gate proving normal MVCC writes bypass the inherited in-memory write front. */
public final class MvccSqlInMemoryWriteFrontBypassTest extends MvccSqlTestSupport {
    private static final String LEGACY_WRITE_FRONT_SHADOW_PROPERTY = "delosdb.mvcc.legacyWriteFrontShadow";

    public void testProviderWritePathBypassesInheritedWriteFrontByDefault() throws Exception {
        String previousShadowProperty = System.getProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
        System.clearProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
        try {
            assertProviderWritePathBypassesInheritedWriteFrontByDefault();
        } finally {
            if (previousShadowProperty == null) {
                System.clearProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
            } else {
                System.setProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY, previousShadowProperty);
            }
        }
    }



    public void testProviderBypassKeepsActiveWriterConflictGuard() throws Exception {
        String previousShadowProperty = System.getProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
        System.clearProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
        try {
            assertProviderBypassKeepsActiveWriterConflictGuard();
        } finally {
            if (previousShadowProperty == null) {
                System.clearProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY);
            } else {
                System.setProperty(LEGACY_WRITE_FRONT_SHADOW_PROPERTY, previousShadowProperty);
            }
        }
    }

    private void assertProviderBypassKeepsActiveWriterConflictGuard() throws Exception {
        String databaseName = databaseName("mvcc-write-front-bypass-conflict-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table write_front_bypass_conflict_t "
                    + "(id int primary key, payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into write_front_bypass_conflict_t values (1, 'base')");
            connection.commit();
        }

        try (Connection writerA = openDatabase(databaseName, false);
             Connection writerB = openDatabase(databaseName, false);
             Connection reader = openDatabase(databaseName, false)) {
            writerA.setAutoCommit(false);
            writerB.setAutoCommit(false);

            long containerId = mvccContainerId(writerA, "WRITE_FRONT_BYPASS_CONFLICT_T");
            assertFalse("legacy in-memory write-front shadow must be disabled for provider conflict guard",
                    diagnostics.legacyWriteFrontShadowEnabledForTesting(0, containerId));
            int startingProviderWrites = diagnostics.providerFirstWriteAppendCountForTesting(0, containerId);
            int startingShadowWrites = diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId);
            int startingShadowBypasses = diagnostics.legacyWriteFrontShadowBypassCountForTesting(0, containerId);
            int startingQuarantineViolations = diagnostics.legacyWriteFrontQuarantineViolationCountForTesting(
                    0, containerId);
            int startingFailureRollbacks = diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(
                    0, containerId);

            assertEquals(1, executeUpdate(writerA,
                    "update write_front_bypass_conflict_t set payload = 'from-a' where id = 1"));
            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    1);

            assertRows(reader,
                    "select id, payload from write_front_bypass_conflict_t where id = 1",
                    "1|base");

            assertWriteConflict(() -> executeUpdate(writerB,
                    "update write_front_bypass_conflict_t set payload = 'from-b' where id = 1"));
            rollbackAfterExpectedConflict(writerB);

            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    1);

            writerA.commit();
            assertRows(reader,
                    "select id, payload from write_front_bypass_conflict_t where id = 1",
                    "1|from-a");
        }

        shutdownDatabase(databaseName);
    }


    private void assertProviderWritePathBypassesInheritedWriteFrontByDefault() throws Exception {
        String databaseName = databaseName("mvcc-write-front-bypass-db");
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
            executeUpdate(connection, "create table write_front_bypass_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index write_front_bypass_code_idx on write_front_bypass_t(code)");
            connection.commit();

            containerId = mvccContainerId(connection, "WRITE_FRONT_BYPASS_T");
            assertFalse("legacy in-memory write-front shadow must be disabled by default",
                    diagnostics.legacyWriteFrontShadowEnabledForTesting(0, containerId));
            startingProviderWrites = diagnostics.providerFirstWriteAppendCountForTesting(0, containerId);
            startingShadowWrites = diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId);
            startingShadowBypasses = diagnostics.legacyWriteFrontShadowBypassCountForTesting(0, containerId);
            startingQuarantineViolations = diagnostics.legacyWriteFrontQuarantineViolationCountForTesting(0, containerId);
            startingFailureRollbacks = diagnostics.providerFirstWriteAppendFailureRollbackCountForTesting(0, containerId);

            executeUpdate(connection, "insert into write_front_bypass_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into write_front_bypass_t values (2, 'code-2', 'payload-2')");
            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
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
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());

            startingPhysicalVersions = diagnostics.physicalVersionCountForTesting(0, containerId);
            startingCandidateRebuilds = diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId);

            executeUpdate(connection, "update write_front_bypass_t set payload = 'payload-1-a' where id = 1");
            executeUpdate(connection, "update write_front_bypass_t set payload = 'payload-1-b' where id = 1");
            Savepoint savepoint = connection.setSavepoint("WRITE_FRONT_BYPASS_SP");
            executeUpdate(connection, "delete from write_front_bypass_t where id = 2");
            executeUpdate(connection, "insert into write_front_bypass_t values (3, 'code-3', 'rolled-back')");
            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    6);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE",
                            "3|UPSERT|3|code-3|rolled-back"),
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-b",
                            "2|DELETE",
                            "3|UPSERT|3|code-3|rolled-back"));

            connection.rollback(savepoint);
            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    6);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-a",
                            "1|UPSERT|1|code-1|payload-1-b"),
                    List.of("1|UPSERT|1|code-1|payload-1-b"));

            connection.commit();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("commit should persist only the final surviving provider intent",
                    List.of("1|UPSERT|1|code-1|payload-1-b"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));
            assertEquals("commit should append one page-backed version for the surviving write intent",
                    startingPhysicalVersions + 1,
                    diagnostics.physicalVersionCountForTesting(0, containerId));
            assertTrue("candidate index should rebuild from page-backed committed rows after provider-only writes",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId)
                            > startingCandidateRebuilds);
            assertEquals("legacy candidate-index rebuild path must remain quarantined",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));

            executeUpdate(connection, "delete from write_front_bypass_t where id = 2");
            assertProviderBypassCounts(
                    diagnostics,
                    containerId,
                    startingProviderWrites,
                    startingShadowWrites,
                    startingShadowBypasses,
                    startingQuarantineViolations,
                    startingFailureRollbacks,
                    7);
            assertActiveProviderAppendState(diagnostics, containerId,
                    List.of("2|DELETE"),
                    List.of("2|DELETE"));
            connection.rollback();
            assertActiveProviderAppendState(diagnostics, containerId, List.of(), List.of());
            assertEquals("rollback must discard provider intents without appending page-backed versions",
                    startingPhysicalVersions + 1,
                    diagnostics.physicalVersionCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, code, payload from write_front_bypass_t order by id",
                    "1|code-1|payload-1-b",
                    "2|code-2|payload-2");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from write_front_bypass_t where code = 'code-1'",
                    "1|payload-1-b");
            assertEquals("candidate index should resolve through page-backed committed rows",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("legacy snapshot read fallback must remain zero with provider-only writes",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero with provider-only writes",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "WRITE_FRONT_BYPASS_T");
            assertFalse("legacy in-memory write-front shadow must remain disabled after reopen",
                    diagnostics.legacyWriteFrontShadowEnabledForTesting(0, reopenedContainerId));
            assertRows(reopened,
                    "select id, code, payload from write_front_bypass_t order by id",
                    "1|code-1|payload-1-b",
                    "2|code-2|payload-2");
            assertEquals("shutdown/reopen must keep legacy snapshot read fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, reopenedContainerId));
            assertEquals("shutdown/reopen must keep legacy snapshot scan fallback quarantined",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static void assertProviderBypassCounts(
            DelosStorageDiagnostics diagnostics,
            long containerId,
            int startingProviderWrites,
            int startingShadowWrites,
            int startingShadowBypasses,
            int startingQuarantineViolations,
            int startingFailureRollbacks,
            int expectedSuccessfulWrites) {
        assertEquals("SQL writes should still append provider intents first",
                startingProviderWrites + expectedSuccessfulWrites,
                diagnostics.providerFirstWriteAppendCountForTesting(0, containerId));
        assertEquals("normal SQL writes should bypass the inherited in-memory write front",
                startingShadowWrites,
                diagnostics.legacyWriteFrontShadowMutationCountForTesting(0, containerId));
        assertEquals("each provider-first SQL write should record a legacy write-front bypass",
                startingShadowBypasses + expectedSuccessfulWrites,
                diagnostics.legacyWriteFrontShadowBypassCountForTesting(0, containerId));
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
