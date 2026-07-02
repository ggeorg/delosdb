/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlWriteIntentReadTest

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

/** SQL gate proving transaction-local reads are served from provider-owned write intents. */
public final class MvccSqlWriteIntentReadTest extends MvccSqlTestSupport {
    public void testSameTransactionReadsUseProviderOwnedWriteIntentOverlay() throws Exception {
        String databaseName = databaseName("mvcc-write-intent-read-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table write_intent_read_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into write_intent_read_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into write_intent_read_t values (2, 'code-2', 'payload-2')");
            connection.commit();

            containerId = mvccContainerId(connection, "WRITE_INTENT_READ_T");
            assertEquals("no transaction-local write-intent scan should be needed after initial commit",
                    0,
                    diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId));
            assertEquals(List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            executeUpdate(connection, "update write_intent_read_t set payload = 'payload-1-uncommitted' where id = 1");
            executeUpdate(connection, "delete from write_intent_read_t where id = 2");
            executeUpdate(connection, "insert into write_intent_read_t values (3, 'code-3', 'payload-3-uncommitted')");

            assertRows(connection,
                    "select id, code, payload from write_intent_read_t order by id",
                    "1|code-1|payload-1-uncommitted",
                    "3|code-3|payload-3-uncommitted");
            assertTrue("same-transaction scan should use provider-owned write-intent overlay",
                    diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId) > 0);
            assertEquals("uncommitted write-intent reads must not change the page-backed committed image",
                    List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            Savepoint savepoint = connection.setSavepoint("WRITE_INTENT_READ_SP");
            executeUpdate(connection, "update write_intent_read_t set payload = 'payload-1-rolled-back' where id = 1");
            executeUpdate(connection, "insert into write_intent_read_t values (4, 'code-4', 'rolled-back')");
            connection.rollback(savepoint);

            assertRows(connection,
                    "select id, code, payload from write_intent_read_t order by id",
                    "1|code-1|payload-1-uncommitted",
                    "3|code-3|payload-3-uncommitted");

            connection.commit();
            assertEquals("commit should consume the surviving write-intent payloads",
                    List.of(
                            "1|UPSERT|1|code-1|payload-1-uncommitted",
                            "2|DELETE",
                            "3|UPSERT|3|code-3|payload-3-uncommitted"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));
            assertEquals(List.of(
                            "1|1|code-1|payload-1-uncommitted",
                            "3|3|code-3|payload-3-uncommitted"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            assertRows(reopened,
                    "select id, code, payload from write_intent_read_t order by id",
                    "1|code-1|payload-1-uncommitted",
                    "3|code-3|payload-3-uncommitted");
            diagnostics.assertConsistentForTesting(0, mvccContainerId(reopened, "WRITE_INTENT_READ_T"));
            reopened.commit();
        }
    }
}
