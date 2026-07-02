/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedWriteIntentTest

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

/** SQL gate proving MVCC commit persistence is driven by provider-owned write intents. */
public final class MvccSqlPageBackedWriteIntentTest extends MvccSqlTestSupport {
    public void testPageBackedCommitConsumesSurvivingWriteIntents() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-write-intent-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table write_intent_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into write_intent_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into write_intent_t values (2, 'code-2', 'payload-2')");
            executeUpdate(connection, "insert into write_intent_t values (3, 'code-3', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "WRITE_INTENT_T");
            assertEquals("initial insert commit should consume three write intents",
                    3, diagnostics.lastCommittedWriteIntentCountForTesting(0, containerId));
            assertEquals("initial insert commit should persist three changed rows",
                    3, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int initialVersions = diagnostics.physicalVersionCountForTesting(0, containerId);

            executeUpdate(connection, "update write_intent_t set payload = 'payload-1-a' where id = 1");
            executeUpdate(connection, "update write_intent_t set payload = 'payload-1-b' where id = 1");
            executeUpdate(connection, "delete from write_intent_t where id = 2");
            executeUpdate(connection, "insert into write_intent_t values (4, 'code-4', 'payload-4')");
            connection.commit();
            assertEquals("repeated row updates should collapse to one write intent per logical row",
                    3, diagnostics.lastCommittedWriteIntentCountForTesting(0, containerId));
            assertEquals("commit should persist the same surviving logical row set",
                    3, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            int afterMixedCommit = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertEquals("update/delete/insert should append one version per changed logical row",
                    initialVersions + 3, afterMixedCommit);

            executeUpdate(connection, "update write_intent_t set payload = 'rolled-back-1' where id = 1");
            executeUpdate(connection, "delete from write_intent_t where id = 4");
            executeUpdate(connection, "insert into write_intent_t values (5, 'code-5', 'rolled-back')");
            connection.rollback();
            assertEquals("connection rollback must not consume page-backed write intents",
                    afterMixedCommit, diagnostics.physicalVersionCountForTesting(0, containerId));

            executeUpdate(connection, "update write_intent_t set payload = 'payload-3-before-savepoint' where id = 3");
            Savepoint savepoint = connection.setSavepoint("WRITE_INTENT_SP");
            executeUpdate(connection, "update write_intent_t set payload = 'rolled-back-3' where id = 3");
            executeUpdate(connection, "insert into write_intent_t values (6, 'code-6', 'rolled-back')");
            connection.rollback(savepoint);
            connection.commit();
            assertEquals("write intent created before savepoint must survive rollback-to-savepoint",
                    1, diagnostics.lastCommittedWriteIntentCountForTesting(0, containerId));
            assertEquals("only the surviving pre-savepoint row should be persisted",
                    1, diagnostics.lastCommittedChangedRowCountForTesting(0, containerId));
            assertEquals("savepoint rollback should append only one committed page-backed version",
                    afterMixedCommit + 1, diagnostics.physicalVersionCountForTesting(0, containerId));

            assertRows(connection,
                    "select id, code, payload from write_intent_t order by id",
                    "1|code-1|payload-1-b",
                    "3|code-3|payload-3-before-savepoint",
                    "4|code-4|payload-4");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            long reopenedContainerId = mvccContainerId(reopened, "WRITE_INTENT_T");
            assertRows(reopened,
                    "select id, code, payload from write_intent_t order by id",
                    "1|code-1|payload-1-b",
                    "3|code-3|payload-3-before-savepoint",
                    "4|code-4|payload-4");
            assertEquals("page-backed write-intent version count should survive reopen",
                    afterAllCommits(), diagnostics.physicalVersionCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }

    private static int afterAllCommits() {
        return 7;
    }
}
