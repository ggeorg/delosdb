/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlRowIdFastPathTest

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

/** SQL gate for current-committed MVCC row-id point reads through the page-backed image. */
public final class MvccSqlRowIdFastPathTest extends MvccSqlTestSupport {
    public void testCurrentCommittedPrimaryKeyAndIndexLookupsUseRowIdFastPath() throws Exception {
        String databaseName = databaseName("mvcc-row-id-fast-path-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table row_id_fast_path_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index row_id_fast_path_code_idx on row_id_fast_path_t(code)");
            executeUpdate(connection, "insert into row_id_fast_path_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into row_id_fast_path_t values (2, 'beta', 'payload-2')");
            executeUpdate(connection, "insert into row_id_fast_path_t values (3, 'gamma', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "ROW_ID_FAST_PATH_T");
            assertTrue("candidate index should be rebuilt from page-backed committed rows",
                    diagnostics.pageBackedCandidateIndexRebuildCountForTesting(0, containerId) > 0);
            assertEquals("legacy candidate-index rebuild path must remain quarantined",
                    0, diagnostics.legacyCandidateIndexRebuildCountForTesting(0, containerId));

            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, code, payload from row_id_fast_path_t where id = 2",
                    "2|beta|payload-2");
            assertTrue("primary-key equality should use a row-id point-read fast path",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("primary-key equality must not materialize an unused committed full scan",
                    0, diagnostics.pageBackedCommittedScanCountForTesting());
            assertTrue("primary-key equality fast path should find the page-backed row",
                    diagnostics.rowIdFastPathHitCountForTesting() > 0);
            assertEquals("primary-key equality should not reject rows by full-scan qualifier filtering",
                    0, diagnostics.qualifierRejectCountForTesting());
            assertEquals("legacy snapshot read fallback must remain zero after row-id fast path lookup",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero after row-id fast path lookup",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);

            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, payload from row_id_fast_path_t "
                            + "--DERBY-PROPERTIES index=row_id_fast_path_code_idx\n "
                            + "where code = 'gamma'",
                    "3|payload-3");
            assertTrue("secondary equality should reuse the same row-id point-read fast path",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("secondary equality must not materialize an unused committed full scan",
                    0, diagnostics.pageBackedCommittedScanCountForTesting());
            assertTrue("secondary equality fast path should find the page-backed row",
                    diagnostics.rowIdFastPathHitCountForTesting() > 0);
            assertEquals("secondary equality should not reject rows by full-scan qualifier filtering",
                    0, diagnostics.qualifierRejectCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            diagnostics.resetScanCountersForTesting();
            assertRows(reopened,
                    "select id, code, payload from row_id_fast_path_t where id = 2",
                    "2|beta|payload-2");
            assertTrue("reopened primary-key equality should still use the row-id fast path",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("reopened primary-key equality must not materialize an unused full scan",
                    0, diagnostics.pageBackedCommittedScanCountForTesting());
            assertTrue("reopened row-id fast path should find the page-backed row",
                    diagnostics.rowIdFastPathHitCountForTesting() > 0);
        }
    }

    public void testStableSnapshotsAndLocalWritersDoNotUseCurrentCommittedRowIdFastPath() throws Exception {
        String databaseName = databaseName("mvcc-row-id-fast-path-snapshot-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table row_id_fast_path_snapshot_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "insert into row_id_fast_path_snapshot_t values (1, 'alpha', 'payload-1')");
            executeUpdate(setup, "insert into row_id_fast_path_snapshot_t values (2, 'beta', 'payload-2')");
            setup.commit();
            containerId = mvccContainerId(setup, "ROW_ID_FAST_PATH_SNAPSHOT_T");
            setup.rollback();
        }

        try (Connection reader = openDatabase(databaseName, false);
                Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader,
                    "select id, payload from row_id_fast_path_snapshot_t where id = 1",
                    "1|payload-1");
            executeUpdate(writer, "update row_id_fast_path_snapshot_t set payload = 'payload-1-new' where id = 1");
            writer.commit();

            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, payload from row_id_fast_path_snapshot_t where id = 1",
                    "1|payload-1");
            assertEquals("stable snapshots must not use current-committed row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            assertEquals("stable snapshots must not use current page-backed committed row reads",
                    0, diagnostics.pageBackedCommittedReadCountForTesting());
            assertEquals("legacy snapshot read fallback must remain zero for stable snapshot lookup",
                    0, diagnostics.legacySnapshotFallbackReadCountForTesting(0, containerId));
            assertEquals("legacy snapshot scan fallback must remain zero for stable snapshot lookup",
                    0, diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            reader.commit();
        }

        try (Connection writer = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);
            executeUpdate(writer, "update row_id_fast_path_snapshot_t set payload = 'payload-2-local' where id = 2");
            diagnostics.resetScanCountersForTesting();
            assertRows(writer,
                    "select id, payload from row_id_fast_path_snapshot_t where id = 2",
                    "2|payload-2-local");
            assertEquals("writer-borrowed reads must not use current-committed row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            assertTrue("writer-borrowed reads should observe local provider write intents",
                    diagnostics.transactionLocalWriteIntentReadCountForTesting(0, containerId) > 0
                            || diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId) > 0);
            writer.rollback();
        }

        shutdownDatabase(databaseName);
    }
}
