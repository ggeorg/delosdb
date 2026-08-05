/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedCommitVersionGateTest

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

/** SQL gate proving page-backed physical versions are created only by real storage commits. */
public final class MvccSqlPageBackedCommitVersionGateTest extends MvccSqlTestSupport {
    public void testPageBackedPhysicalVersionsAdvanceOnlyForCommittedRowChanges() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-commit-version-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table commit_version_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into commit_version_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into commit_version_t values (2, 'beta', 'payload-2')");
            connection.commit();

            containerId = mvccContainerId(connection, "COMMIT_VERSION_T");
            int initialVersions = physicalVersions(diagnostics, containerId);
            assertEquals("initial committed rows should create one page-backed version each",
                    2, initialVersions);

            assertRows(connection,
                    "select id, code, payload from commit_version_t order by id",
                    "1|alpha|payload-1",
                    "2|beta|payload-2");
            connection.commit();
            assertEquals("read-only/no-op commits must not append page-backed versions",
                    initialVersions, physicalVersions(diagnostics, containerId));

            executeUpdate(connection, "update commit_version_t set payload = 'dirty-payload' where id = 1");
            executeUpdate(connection, "delete from commit_version_t where id = 2");
            executeUpdate(connection, "insert into commit_version_t values (3, 'gamma-dirty', 'payload-3')");
            assertEquals("uncommitted row changes must not append page-backed versions",
                    initialVersions, physicalVersions(diagnostics, containerId));
            connection.rollback();
            assertEquals("rollback must not append page-backed versions",
                    initialVersions, physicalVersions(diagnostics, containerId));

            Savepoint savepoint = connection.setSavepoint("PAGE_BACKED_VERSION_SAVEPOINT");
            executeUpdate(connection, "update commit_version_t set payload = 'savepoint-payload' where id = 1");
            executeUpdate(connection, "insert into commit_version_t values (4, 'delta-savepoint', 'payload-4')");
            assertEquals("savepoint-scoped writes must not append page-backed versions before commit",
                    initialVersions, physicalVersions(diagnostics, containerId));
            connection.rollback(savepoint);
            connection.commit();
            assertEquals("committing after rollback-to-savepoint must not append discarded versions",
                    initialVersions, physicalVersions(diagnostics, containerId));

            executeUpdate(connection, "update commit_version_t set payload = 'payload-1-committed' where id = 1");
            connection.commit();
            int afterUpdate = physicalVersions(diagnostics, containerId);
            assertEquals("one committed update should append exactly one page-backed version",
                    initialVersions + 1, afterUpdate);

            executeUpdate(connection, "insert into commit_version_t values (3, 'gamma', 'payload-3')");
            connection.commit();
            int afterInsert = physicalVersions(diagnostics, containerId);
            assertEquals("one committed insert should append exactly one page-backed version",
                    afterUpdate + 1, afterInsert);

            executeUpdate(connection, "delete from commit_version_t where id = 2");
            connection.commit();
            int afterDelete = physicalVersions(diagnostics, containerId);
            assertEquals("one committed delete should append exactly one page-backed tombstone version",
                    afterInsert + 1, afterDelete);

            assertRows(connection,
                    "select id, code, payload from commit_version_t order by id",
                    "1|alpha|payload-1-committed",
                    "3|gamma|payload-3");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "COMMIT_VERSION_T");
            assertEquals("page-backed committed version count must survive reopen",
                    5, physicalVersions(diagnostics, reopenedContainerId));
            assertRows(reopened,
                    "select id, code, payload from commit_version_t order by id",
                    "1|alpha|payload-1-committed",
                    "3|gamma|payload-3");
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static int physicalVersions(DelosStorageDiagnostics diagnostics, long containerId) {
        return diagnostics.physicalVersionCountForTesting(0, containerId);
    }
}
