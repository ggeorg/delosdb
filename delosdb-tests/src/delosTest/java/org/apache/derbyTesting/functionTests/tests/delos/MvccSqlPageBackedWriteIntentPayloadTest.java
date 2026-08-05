/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedWriteIntentPayloadTest

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

/** SQL gate proving page-backed write intents carry the committed payload/tombstone. */
public final class MvccSqlPageBackedWriteIntentPayloadTest extends MvccSqlTestSupport {
    public void testPageBackedWriteIntentsCarryPayloadsAndTombstones() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-write-intent-payload-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table write_payload_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into write_payload_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into write_payload_t values (2, 'code-2', 'payload-2')");
            connection.commit();

            containerId = mvccContainerId(connection, "WRITE_PAYLOAD_T");
            assertEquals("initial commit should persist two write-intent payloads",
                    List.of(
                            "1|UPSERT|1|code-1|payload-1",
                            "2|UPSERT|2|code-2|payload-2"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));

            executeUpdate(connection, "update write_payload_t set payload = 'payload-1-before-savepoint' where id = 1");
            Savepoint savepoint = connection.setSavepoint("WRITE_PAYLOAD_SP");
            executeUpdate(connection, "update write_payload_t set payload = 'payload-1-rolled-back' where id = 1");
            executeUpdate(connection, "insert into write_payload_t values (3, 'code-3', 'rolled-back')");
            connection.rollback(savepoint);
            connection.commit();
            assertEquals("rollback-to-savepoint must restore the previous write-intent payload",
                    List.of("1|UPSERT|1|code-1|payload-1-before-savepoint"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));

            executeUpdate(connection, "update write_payload_t set code = 'code-1-after' where id = 1");
            executeUpdate(connection, "delete from write_payload_t where id = 2");
            connection.commit();
            assertEquals("commit should consume one upsert payload and one delete tombstone intent",
                    List.of(
                            "1|UPSERT|1|code-1-after|payload-1-before-savepoint",
                            "2|DELETE"),
                    diagnostics.lastCommittedWriteIntentPayloadSummariesForTesting(0, containerId));

            assertRows(connection,
                    "select id, code, payload from write_payload_t order by id",
                    "1|code-1-after|payload-1-before-savepoint");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            assertRows(reopened,
                    "select id, code, payload from write_payload_t order by id",
                    "1|code-1-after|payload-1-before-savepoint");
            long reopenedContainerId = mvccContainerId(reopened, "WRITE_PAYLOAD_T");
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            reopened.commit();
        }
    }
}
