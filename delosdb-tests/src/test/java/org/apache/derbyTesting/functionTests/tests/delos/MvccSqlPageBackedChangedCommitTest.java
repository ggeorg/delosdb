/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedChangedCommitTest

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

/** SQL gate proving page-backed committed persistence uses the transaction changed-row set. */
public final class MvccSqlPageBackedChangedCommitTest extends MvccSqlTestSupport {
    public void testPageBackedCommitPersistsOnlyChangedRows() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-changed-commit-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table changed_commit_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            for (int i = 1; i <= 5; i++) {
                executeUpdate(connection, "insert into changed_commit_t values ("
                        + i + ", 'code-" + i + "', 'payload-" + i + "')");
            }
            connection.commit();

            containerId = mvccContainerId(connection, "CHANGED_COMMIT_T");
            assertEquals("initial insert commit should persist five changed row ids",
                    5, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int initialVersions = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("initial insert commit should create five page-backed versions",
                    5, initialVersions);

            executeUpdate(connection, "update changed_commit_t set payload = 'payload-3-updated' where id = 3");
            connection.commit();
            assertEquals("single-row update commit should persist one changed row id",
                    1, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int afterOneUpdate = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("single-row update should append one page-backed version",
                    initialVersions + 1, afterOneUpdate);

            executeUpdate(connection, "update changed_commit_t set payload = 'payload-1-updated' where id = 1");
            executeUpdate(connection, "delete from changed_commit_t where id = 2");
            executeUpdate(connection, "insert into changed_commit_t values (6, 'code-6', 'payload-6')");
            connection.commit();
            assertEquals("mixed update/delete/insert commit should persist exactly three changed row ids",
                    3, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int afterMixedCommit = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("mixed update/delete/insert should append three page-backed versions",
                    afterOneUpdate + 3, afterMixedCommit);

            executeUpdate(connection, "update changed_commit_t set payload = 'payload-4-before-savepoint' where id = 4");
            Savepoint savepoint = connection.setSavepoint("CHANGED_COMMIT_SAVEPOINT");
            executeUpdate(connection, "update changed_commit_t set payload = 'rolled-back-payload' where id = 5");
            executeUpdate(connection, "insert into changed_commit_t values (7, 'code-7', 'rolled-back')");
            connection.rollback(savepoint);
            connection.commit();
            assertEquals("rollback-to-savepoint must trim discarded changed row ids before commit",
                    1, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int afterSavepointCommit = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("only the pre-savepoint update should append a page-backed version",
                    afterMixedCommit + 1, afterSavepointCommit);

            assertRows(connection,
                    "select id, code, payload from changed_commit_t order by id",
                    "1|code-1|payload-1-updated",
                    "3|code-3|payload-3-updated",
                    "4|code-4|payload-4-before-savepoint",
                    "5|code-5|payload-5",
                    "6|code-6|payload-6");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "CHANGED_COMMIT_T");
            assertRows(reopened,
                    "select id, code, payload from changed_commit_t order by id",
                    "1|code-1|payload-1-updated",
                    "3|code-3|payload-3-updated",
                    "4|code-4|payload-4-before-savepoint",
                    "5|code-5|payload-5",
                    "6|code-6|payload-6");
            assertEquals("page-backed version count should survive reopen",
                    10, diagnostics.physicalVersionCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }
}
