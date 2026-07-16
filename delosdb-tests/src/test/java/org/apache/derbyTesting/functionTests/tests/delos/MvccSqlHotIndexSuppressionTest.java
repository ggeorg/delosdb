/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlHotIndexSuppressionTest

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

/** SQL gate proving candidate-index state does not grow with non-key MVCC version churn. */
public final class MvccSqlHotIndexSuppressionTest extends MvccSqlTestSupport {
    public void testNonKeyUpdatesDoNotAccumulateCandidateIndexEntries() throws Exception {
        String databaseName = databaseName("mvcc-hot-index-suppression-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table hot_index_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index hot_index_code_idx on hot_index_t(code)");
            executeUpdate(connection, "insert into hot_index_t values (1, 'stable-code', 'payload-0')");
            connection.commit();

            containerId = mvccContainerId(connection, "HOT_INDEX_T");
            int initialCandidateKeys = diagnostics.candidateIndexKeyCountForTesting(0, containerId);
            int initialPhysicalVersions = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("initial table should expose one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            for (int i = 1; i <= 20; i++) {
                executeUpdate(connection, "update hot_index_t set payload = 'payload-" + i + "' where id = 1");
                connection.commit();
            }

            assertEquals("non-key updates must preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));
            assertTrue("non-key updates should still create MVCC version churn",
                    diagnostics.physicalVersionCountForTesting(0, containerId) > initialPhysicalVersions);
            assertEquals("candidate-index key set must not grow with every non-key update",
                    initialCandidateKeys, diagnostics.candidateIndexKeyCountForTesting(0, containerId));

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from hot_index_t where code = 'stable-code'",
                    "1|payload-20");
            assertEquals("stable key lookup should use one candidate-index probe",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stable key should resolve one logical row id despite many physical versions",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("stable key lookup should not reject stale physical versions",
                    0, diagnostics.candidateIndexVisibilityRejectCountForTesting());
            assertEquals("stable key lookup should not reject stale payload versions",
                    0, diagnostics.candidateIndexQualifierRejectCountForTesting());

            inPlaceCompressTable(connection, "HOT_INDEX_T");
            connection.commit();
            diagnostics.assertConsistentForTesting(0, containerId);
            assertRows(connection,
                    "select id, payload from hot_index_t where code = 'stable-code'",
                    "1|payload-20");
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "HOT_INDEX_T");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(reopened,
                    "select id, payload from hot_index_t where code = 'stable-code'",
                    "1|payload-20");
            assertEquals("hydrated candidate index should still expose one logical row id",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }
}
