/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlConsistencyCheckTest

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

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for delos_mvcc durable consistency diagnostics. */
public final class MvccSqlConsistencyCheckTest extends MvccSqlTestSupport {
    public void testComplexSqlWorkloadVacuumAndReopenPassesDurableConsistencyCheck() throws Exception {
        String databaseName = databaseName("mvcc-sql-consistency-complex-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_consistency_t "
                    + "(id int primary key, email varchar(64) unique, tag varchar(16), name varchar(32)) "
                    + "using delos_mvcc");
            executeUpdate(connection, "create index mvcc_consistency_tag_idx on mvcc_consistency_t(tag)");
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(1, 'a@example.com', 'blue', 'alpha')");
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(2, 'b@example.com', 'red', 'beta')");
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(3, 'c@example.com', 'blue', 'gamma')");
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(4, 'd@example.com', 'red', 'delta')");
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(5, 'e@example.com', 'blue', 'epsilon')");
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_CONSISTENCY_T");
            assertMvccConsistent(diagnostics, containerId);

            Savepoint savepoint = connection.setSavepoint("CONSISTENCY_SP");
            assertEquals(1, executeUpdate(connection,
                    "update mvcc_consistency_t set tag = 'rolled-back', name = 'alpha-rollback' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_consistency_t where id = 2"));
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(6, 'f@example.com', 'blue', 'zeta-rollback')");
            connection.rollback(savepoint);

            assertRows(connection,
                    "select id, email, tag, name from mvcc_consistency_t order by id",
                    "1|a@example.com|blue|alpha",
                    "2|b@example.com|red|beta",
                    "3|c@example.com|blue|gamma",
                    "4|d@example.com|red|delta",
                    "5|e@example.com|blue|epsilon");

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_consistency_t set tag = 'green', name = 'alpha-v2' where id = 1"));
            assertEquals(1, executeUpdate(connection,
                    "update mvcc_consistency_t set email = 'c2@example.com', tag = 'green' where id = 3"));
            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_consistency_t where id = 4"));
            executeUpdate(connection, "insert into mvcc_consistency_t values "
                    + "(6, 'f@example.com', 'blue', 'zeta')");
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_consistency_t set name = 'alpha-v3' where id = 1"));
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "update mvcc_consistency_t set tag = 'green', name = 'zeta-v2' where id = 6"));
            connection.commit();

            assertEquals(1, executeUpdate(connection,
                    "delete from mvcc_consistency_t where id = 2"));
            connection.commit();

            assertRows(connection,
                    "select id, email, tag, name from mvcc_consistency_t order by id",
                    "1|a@example.com|green|alpha-v3",
                    "3|c2@example.com|green|gamma",
                    "5|e@example.com|blue|epsilon",
                    "6|f@example.com|green|zeta-v2");
            assertRows(connection,
                    "select id, name from mvcc_consistency_t --DERBY-PROPERTIES index=mvcc_consistency_tag_idx\n "
                            + "where tag = 'green' order by id",
                    "1|alpha-v3",
                    "3|gamma",
                    "6|zeta-v2");

            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertTrue("expected complex workload to create superseded MVCC versions before vacuum, got "
                    + versionsBeforeVacuum,
                    versionsBeforeVacuum > diagnostics.logicalRowCountForTesting(0, containerId));
            assertMvccConsistent(diagnostics, containerId);

            inPlaceCompressTable(connection, "MVCC_CONSISTENCY_T");
            connection.commit();

            assertFalse("vacuum should run when the complex workload has no retained SQL snapshot",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertTrue("vacuum should remove at least one obsolete version after the complex workload",
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId) >= 1);
            assertEquals("vacuum must preserve the four visible logical rows",
                    4, diagnostics.logicalRowCountForTesting(0, containerId));
            assertMvccConsistent(diagnostics, containerId);
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_CONSISTENCY_T");
            assertMvccConsistent(diagnostics, reopenedContainerId);
            assertRows(reopened,
                    "select id, email, tag, name from mvcc_consistency_t order by id",
                    "1|a@example.com|green|alpha-v3",
                    "3|c2@example.com|green|gamma",
                    "5|e@example.com|blue|epsilon",
                    "6|f@example.com|green|zeta-v2");
            assertRows(reopened,
                    "select id, name from mvcc_consistency_t --DERBY-PROPERTIES index=mvcc_consistency_tag_idx\n "
                            + "where tag = 'green' order by id",
                    "1|alpha-v3",
                    "3|gamma",
                    "6|zeta-v2");
        }
    }

    private static void assertMvccConsistent(DelosStorageDiagnostics diagnostics, long containerId) {
        String summary = diagnostics.consistencySummaryForTesting(0, containerId);
        assertEquals("expected valid durable MVCC state, got " + summary,
                0, diagnostics.consistencyErrorCountForTesting(0, containerId));
        diagnostics.assertConsistentForTesting(0, containerId);
    }
}
