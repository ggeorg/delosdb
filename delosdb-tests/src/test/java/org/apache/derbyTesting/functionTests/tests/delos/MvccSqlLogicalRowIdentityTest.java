/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlLogicalRowIdentityTest

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

/** SQL gate proving MVCC candidate-index entries resolve stable logical row ids, not physical versions. */
public final class MvccSqlLogicalRowIdentityTest extends MvccSqlTestSupport {
    public void testCandidateIndexUsesStableLogicalRowIdentityAcrossVersionChurn() throws Exception {
        String databaseName = databaseName("mvcc-logical-row-identity-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table logical_identity_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into logical_identity_t values (1, 'stable-code', 'payload-0')");
            connection.commit();

            containerId = mvccContainerId(connection, "LOGICAL_IDENTITY_T");
            assertEquals("initial committed image should expose one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));

            for (int i = 1; i <= 5; i++) {
                executeUpdate(connection, "update logical_identity_t set payload = 'payload-" + i + "' where id = 1");
                connection.commit();
            }

            assertEquals("non-indexed updates should preserve one logical row",
                    1, diagnostics.logicalRowCountForTesting(0, containerId));
            assertTrue("non-indexed updates should create version churn behind the same logical row",
                    diagnostics.physicalVersionCountForTesting(0, containerId) > 1);

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, payload from logical_identity_t where code = 'stable-code'",
                    "1|payload-5");
            assertEquals("stable key lookup should use the candidate index",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("stable key should resolve one logical row id, not every physical version",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            assertEquals("stable logical-row lookup should not need visibility rejects",
                    0, diagnostics.candidateIndexVisibilityRejectCountForTesting());
            assertEquals("stable logical-row lookup should not need qualifier rejects",
                    0, diagnostics.candidateIndexQualifierRejectCountForTesting());

            executeUpdate(connection, "update logical_identity_t set code = 'moved-code' where id = 1");
            connection.commit();

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection, "select id from logical_identity_t where code = 'stable-code'");
            assertEquals("old indexed key should be known after refresh",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("old indexed key must not retain the logical row id after key movement",
                    0, diagnostics.candidateIndexRowIdCountForTesting());

            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(connection,
                    "select id, code, payload from logical_identity_t where code = 'moved-code'",
                    "1|moved-code|payload-5");
            assertEquals("new indexed key should resolve the same logical row id",
                    1, diagnostics.candidateIndexRowIdCountForTesting());

            inPlaceCompressTable(connection, "LOGICAL_IDENTITY_T");
            connection.commit();
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "LOGICAL_IDENTITY_T");
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(reopened,
                    "select id, code, payload from logical_identity_t where code = 'moved-code'",
                    "1|moved-code|payload-5");
            assertEquals("hydrated candidate index should preserve one logical row id after reopen",
                    1, diagnostics.candidateIndexRowIdCountForTesting());
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);

            executeUpdate(reopened, "delete from logical_identity_t where id = 1");
            reopened.commit();
            diagnostics.resetCandidateIndexCountersForTesting();
            assertRows(reopened, "select id from logical_identity_t where code = 'moved-code'");
            assertEquals("deleted key should be known after refresh",
                    1, diagnostics.candidateIndexLookupCountForTesting());
            assertEquals("delete must remove the logical row id from the candidate key",
                    0, diagnostics.candidateIndexRowIdCountForTesting());
            reopened.commit();
        }
    }
}
