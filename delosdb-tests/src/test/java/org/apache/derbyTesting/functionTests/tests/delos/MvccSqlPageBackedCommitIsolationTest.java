/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedCommitIsolationTest

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

/** SQL gate proving page-backed committed state only changes at MVCC storage commit boundaries. */
public final class MvccSqlPageBackedCommitIsolationTest extends MvccSqlTestSupport {
    public void testPageBackedCommittedImageIgnoresRollbackAndSavepointMutations() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-commit-isolation-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table commit_isolation_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index commit_isolation_code_idx on commit_isolation_t(code)");
            executeUpdate(connection, "insert into commit_isolation_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into commit_isolation_t values (2, 'beta', 'payload-2')");
            connection.commit();

            containerId = mvccContainerId(connection, "COMMIT_ISOLATION_T");
            List<String> initialCommittedImage = pageBackedRows(diagnostics, containerId);
            assertEquals(List.of(
                    "1|1|alpha|payload-1",
                    "2|2|beta|payload-2"), initialCommittedImage);

            executeUpdate(connection,
                    "update commit_isolation_t set code = 'alpha-dirty', payload = 'payload-dirty' where id = 1");
            executeUpdate(connection, "delete from commit_isolation_t where id = 2");
            executeUpdate(connection, "insert into commit_isolation_t values (3, 'gamma-dirty', 'payload-3')");
            assertRows(connection,
                    "select id, code, payload from commit_isolation_t order by id",
                    "1|alpha-dirty|payload-dirty",
                    "3|gamma-dirty|payload-3");
            assertEquals("uncommitted writes must not update the page-backed committed image",
                    initialCommittedImage, pageBackedRows(diagnostics, containerId));

            connection.rollback();
            assertRows(connection,
                    "select id, code, payload from commit_isolation_t order by id",
                    "1|alpha|payload-1",
                    "2|beta|payload-2");
            assertEquals("connection rollback must leave the page-backed committed image unchanged",
                    initialCommittedImage, pageBackedRows(diagnostics, containerId));
            connection.commit();

            Savepoint savepoint = connection.setSavepoint("PAGE_BACKED_SAVEPOINT");
            executeUpdate(connection,
                    "update commit_isolation_t set code = 'alpha-savepoint', payload = 'payload-savepoint' where id = 1");
            executeUpdate(connection, "insert into commit_isolation_t values (4, 'delta-savepoint', 'payload-4')");
            assertEquals("savepoint-scoped writes must not update the page-backed committed image before commit",
                    initialCommittedImage, pageBackedRows(diagnostics, containerId));
            connection.rollback(savepoint);
            assertRows(connection,
                    "select id, code, payload from commit_isolation_t order by id",
                    "1|alpha|payload-1",
                    "2|beta|payload-2");
            connection.commit();
            assertEquals("savepoint rollback commit must preserve the previous committed image",
                    initialCommittedImage, pageBackedRows(diagnostics, containerId));

            executeUpdate(connection,
                    "update commit_isolation_t set code = 'alpha-committed', payload = 'payload-committed' where id = 1");
            executeUpdate(connection, "insert into commit_isolation_t values (3, 'gamma-committed', 'payload-3')");
            assertEquals("new committed image must not be visible until commit",
                    initialCommittedImage, pageBackedRows(diagnostics, containerId));
            connection.commit();

            assertEquals(List.of(
                    "1|alpha-committed|payload-committed",
                    "2|beta|payload-2",
                    "3|gamma-committed|payload-3"), pageBackedValueRows(diagnostics, containerId));
            assertRows(connection,
                    "select id, code, payload from commit_isolation_t order by id",
                    "1|alpha-committed|payload-committed",
                    "2|beta|payload-2",
                    "3|gamma-committed|payload-3");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "COMMIT_ISOLATION_T");
            assertEquals(List.of(
                    "1|alpha-committed|payload-committed",
                    "2|beta|payload-2",
                    "3|gamma-committed|payload-3"), pageBackedValueRows(diagnostics, reopenedContainerId));
            assertRows(reopened,
                    "select id, code, payload from commit_isolation_t order by id",
                    "1|alpha-committed|payload-committed",
                    "2|beta|payload-2",
                    "3|gamma-committed|payload-3");
            reopened.commit();
        }
    }

    private static List<String> pageBackedRows(DelosStorageDiagnostics diagnostics, long containerId) {
        return diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId);
    }

    private static List<String> pageBackedValueRows(DelosStorageDiagnostics diagnostics, long containerId) {
        return pageBackedRows(diagnostics, containerId).stream()
                .map(MvccSqlPageBackedCommitIsolationTest::dropDiagnosticRowId)
                .toList();
    }

    private static String dropDiagnosticRowId(String row) {
        int separator = row.indexOf('|');
        return separator < 0 ? row : row.substring(separator + 1);
    }
}
