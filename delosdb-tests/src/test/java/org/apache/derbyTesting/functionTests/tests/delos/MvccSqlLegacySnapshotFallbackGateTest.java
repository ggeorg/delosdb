/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlLegacySnapshotFallbackGateTest

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

/** SQL gate proving old snapshots now use page-backed historical reads instead of the legacy fallback. */
public final class MvccSqlLegacySnapshotFallbackGateTest extends MvccSqlTestSupport {
    public void testLegacySnapshotFallbackIsReplacedByPageBackedHistoricalSnapshots() throws Exception {
        String databaseName = databaseName("mvcc-legacy-snapshot-fallback-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table legacy_fallback_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "insert into legacy_fallback_t values (1, 'one', 'payload-1')");
            executeUpdate(setup, "insert into legacy_fallback_t values (2, 'two', 'payload-2')");
            setup.commit();
            containerId = mvccContainerId(setup, "LEGACY_FALLBACK_T");

            int beforeCurrentScanFallback = diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId);
            assertRows(setup,
                    "select id, code, payload from legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2");
            assertEquals("current committed scans must use the provider page-backed scan, not the legacy fallback",
                    beforeCurrentScanFallback,
                    diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            setup.commit();
        }

        try (Connection writer = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);
            int beforeWriterScanFallback = diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId);
            int beforeWriteIntentScan = diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId);
            executeUpdate(writer, "update legacy_fallback_t set payload = 'payload-1-uncommitted' where id = 1");
            assertRows(writer,
                    "select id, code, payload from legacy_fallback_t order by id",
                    "1|one|payload-1-uncommitted",
                    "2|two|payload-2");
            assertTrue("current writer scans should use the provider write-intent/page-backed composition",
                    diagnostics.transactionLocalWriteIntentScanCountForTesting(0, containerId) > beforeWriteIntentScan);
            assertEquals("current writer scans must not use the old in-memory MVCC scan fallback",
                    beforeWriterScanFallback,
                    diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            writer.rollback();
        }

        try (Connection oldSnapshot = openDatabase(databaseName, false);
             Connection writer = openDatabase(databaseName, false)) {
            oldSnapshot.setAutoCommit(false);
            oldSnapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            writer.setAutoCommit(false);

            assertRows(oldSnapshot,
                    "select id, code, payload from legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2");

            executeUpdate(writer, "update legacy_fallback_t set payload = 'payload-1-committed' where id = 1");
            executeUpdate(writer, "delete from legacy_fallback_t where id = 2");
            executeUpdate(writer, "insert into legacy_fallback_t values (3, 'three', 'payload-3')");
            writer.commit();

            int beforeOldSnapshotFallback = diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId);
            int beforeHistoricalPageBackedScan = diagnostics.pageBackedHistoricalSnapshotScanCountForTesting(
                    0, containerId);
            assertRows(oldSnapshot,
                    "select id, code, payload from legacy_fallback_t order by id",
                    "1|one|payload-1",
                    "2|two|payload-2");
            assertEquals("older transaction-scoped snapshots should no longer use the legacy in-memory fallback",
                    beforeOldSnapshotFallback,
                    diagnostics.legacySnapshotFallbackScanCountForTesting(0, containerId));
            assertTrue("older transaction-scoped snapshots should read historical rows from page-backed storage",
                    diagnostics.pageBackedHistoricalSnapshotScanCountForTesting(0, containerId)
                            > beforeHistoricalPageBackedScan);
            oldSnapshot.commit();
        }

        try (Connection verifier = openDatabase(databaseName, false)) {
            verifier.setAutoCommit(false);
            assertRows(verifier,
                    "select id, code, payload from legacy_fallback_t order by id",
                    "1|one|payload-1-committed",
                    "3|three|payload-3");
            diagnostics.assertConsistentForTesting(0, containerId);
            verifier.commit();
        }
    }
}
