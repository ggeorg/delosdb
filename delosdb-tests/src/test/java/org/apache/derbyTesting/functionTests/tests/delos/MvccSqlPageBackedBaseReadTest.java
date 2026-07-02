/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedBaseReadTest

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
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL gate proving current transaction-local reads use the page-backed committed base. */
public final class MvccSqlPageBackedBaseReadTest extends MvccSqlTestSupport {
    public void testTransactionLocalReadsFallBackToPageBackedCommittedBase() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-base-read-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table page_backed_base_read_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into page_backed_base_read_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into page_backed_base_read_t values (2, 'code-2', 'payload-2')");
            executeUpdate(connection, "insert into page_backed_base_read_t values (3, 'code-3', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "PAGE_BACKED_BASE_READ_T");
            assertEquals(List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            int beforeUpdate = diagnostics.transactionLocalPageBackedBaseReadCountForTesting(0, containerId);
            executeUpdate(connection,
                    "update page_backed_base_read_t set payload = 'payload-2-uncommitted' where id = 2");
            assertTrue("update should read the committed base row through the page-backed provider path",
                    diagnostics.transactionLocalPageBackedBaseReadCountForTesting(0, containerId) > beforeUpdate);
            assertEquals("uncommitted update must not change the page-backed committed image",
                    List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));
            assertRows(connection,
                    "select id, code, payload from page_backed_base_read_t order by id",
                    "1|code-1|payload-1",
                    "2|code-2|payload-2-uncommitted",
                    "3|code-3|payload-3");
            connection.commit();
            assertEquals(List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2-uncommitted",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            int beforeDelete = diagnostics.transactionLocalPageBackedBaseReadCountForTesting(0, containerId);
            executeUpdate(connection, "delete from page_backed_base_read_t where id = 1");
            assertTrue("delete should read the committed base row through the page-backed provider path",
                    diagnostics.transactionLocalPageBackedBaseReadCountForTesting(0, containerId) > beforeDelete);
            assertRows(connection,
                    "select id, code, payload from page_backed_base_read_t order by id",
                    "2|code-2|payload-2-uncommitted",
                    "3|code-3|payload-3");
            connection.rollback();
            assertEquals("rollback must leave the page-backed committed image unchanged",
                    List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2-uncommitted",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            assertRows(reopened,
                    "select id, code, payload from page_backed_base_read_t order by id",
                    "1|code-1|payload-1",
                    "2|code-2|payload-2-uncommitted",
                    "3|code-3|payload-3");
            diagnostics.assertConsistentForTesting(0, mvccContainerId(reopened, "PAGE_BACKED_BASE_READ_T"));
            reopened.commit();
        }
    }
}
