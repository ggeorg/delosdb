/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlPageBackedCommittedReadTest

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

/** SQL gate for routing safe current-committed MVCC reads through the page-backed image. */
public final class MvccSqlPageBackedCommittedReadTest extends MvccSqlTestSupport {
    public void testCurrentCommittedReadsUsePageBackedImageButStableSnapshotsDoNot() throws Exception {
        String databaseName = databaseName("mvcc-page-backed-committed-read-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table committed_read_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "create index committed_read_code_idx on committed_read_t(code)");
            executeUpdate(setup, "insert into committed_read_t values (1, 'alpha', 'payload-1')");
            executeUpdate(setup, "insert into committed_read_t values (2, 'beta', 'payload-2')");
            setup.commit();

            diagnostics.resetScanCountersForTesting();
            assertRows(setup,
                    "select id, code, payload from committed_read_t order by id",
                    "1|alpha|payload-1",
                    "2|beta|payload-2");
            assertTrue("current committed full scan should use the page-backed committed image",
                    diagnostics.pageBackedCommittedScanCountForTesting() > 0);

            diagnostics.resetScanCountersForTesting();
            assertRows(setup,
                    "select id, code, payload from committed_read_t where code = 'alpha'",
                    "1|alpha|payload-1");
            assertTrue("current committed candidate lookup should read rows from the page-backed committed image",
                    diagnostics.pageBackedCommittedReadCountForTesting() > 0);
            setup.commit();
        }

        try (Connection reader = openDatabase(databaseName, false);
                Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader,
                    "select id, code from committed_read_t where code = 'alpha'",
                    "1|alpha");

            executeUpdate(writer, "update committed_read_t set code = 'alpha-new' where id = 1");
            writer.commit();

            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, code from committed_read_t where code = 'alpha'",
                    "1|alpha");
            assertEquals("stable transaction-scoped snapshots must not use the current page-backed image",
                    0,
                    diagnostics.pageBackedCommittedScanCountForTesting());
            assertEquals("stable transaction-scoped snapshots must not use page-backed committed row reads",
                    0,
                    diagnostics.pageBackedCommittedReadCountForTesting());
            reader.commit();
        }

        shutdownDatabase(databaseName);
    }
}
