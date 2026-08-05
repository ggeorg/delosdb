/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedTransactionScanTest

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

/** SQL gate proving transaction-local scans use the page-backed committed base. */
public final class MvccSqlPageBackedTransactionScanTest extends MvccSqlTestSupport {
    public void testTransactionLocalScansUsePageBackedCommittedBase() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-transaction-scan-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics(databaseName);
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table page_backed_tx_scan_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into page_backed_tx_scan_t values (1, 'code-1', 'payload-1')");
            executeUpdate(connection, "insert into page_backed_tx_scan_t values (2, 'code-2', 'payload-2')");
            executeUpdate(connection, "insert into page_backed_tx_scan_t values (3, 'code-3', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "PAGE_BACKED_TX_SCAN_T");
            int beforeBaseScan = diagnostics.transactionLocalPageBackedBaseScanCountForTesting(0, containerId);

            Savepoint savepoint = connection.setSavepoint("TX_SCAN_SP");
            executeUpdate(connection,
                    "update page_backed_tx_scan_t set payload = 'rolled-back' where id = 2");
            connection.rollback(savepoint);

            assertRows(connection,
                    "select id, code, payload from page_backed_tx_scan_t order by id",
                    "1|code-1|payload-1",
                    "2|code-2|payload-2",
                    "3|code-3|payload-3");
            assertTrue("transaction-local scan without surviving write intents should use "
                            + "the provider page-backed committed base",
                    diagnostics.transactionLocalPageBackedBaseScanCountForTesting(0, containerId) > beforeBaseScan);
            assertEquals("rolled-back write intent must not change the page-backed committed image",
                    List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            int beforeWriteIntentScan = diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId);
            executeUpdate(connection,
                    "update page_backed_tx_scan_t set payload = 'payload-1-uncommitted' where id = 1");
            executeUpdate(connection,
                    "insert into page_backed_tx_scan_t values (4, 'code-4', 'payload-4-uncommitted')");
            executeUpdate(connection, "delete from page_backed_tx_scan_t where id = 3");

            assertRows(connection,
                    "select id, code, payload from page_backed_tx_scan_t order by id",
                    "1|code-1|payload-1-uncommitted",
                    "2|code-2|payload-2",
                    "4|code-4|payload-4-uncommitted");
            assertTrue("transaction-local scan with surviving write intents should compose "
                            + "the provider write-intent overlay over the page-backed committed base",
                    diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId) > beforeWriteIntentScan);
            assertEquals("uncommitted write-intent scan must not mutate the page-backed committed image",
                    List.of(
                            "1|1|code-1|payload-1",
                            "2|2|code-2|payload-2",
                            "3|3|code-3|payload-3"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));

            connection.commit();
            assertEquals(List.of(
                            "1|1|code-1|payload-1-uncommitted",
                            "2|2|code-2|payload-2",
                            "4|4|code-4|payload-4-uncommitted"),
                    diagnostics.pageBackedVisibleRowSummariesForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            reopened.setAutoCommit(false);
            assertRows(reopened,
                    "select id, code, payload from page_backed_tx_scan_t order by id",
                    "1|code-1|payload-1-uncommitted",
                    "2|code-2|payload-2",
                    "4|code-4|payload-4-uncommitted");
            diagnostics.assertConsistentForTesting(0, mvccContainerId(reopened, "PAGE_BACKED_TX_SCAN_T"));
            reopened.commit();
        }
    }
}
