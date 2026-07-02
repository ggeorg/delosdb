/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlOrderedIndexEqualityLookupTest

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

/** SQL gate for routing current-committed equality narrowing through ordered MVCC index pages. */
public final class MvccSqlOrderedIndexEqualityLookupTest extends MvccSqlTestSupport {
    public void testCurrentCommittedEqualityLookupUsesOrderedIndexPages() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-equality-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_lookup_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "create index ordered_index_lookup_code_idx on ordered_index_lookup_t(code)");
            executeUpdate(connection, "insert into ordered_index_lookup_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into ordered_index_lookup_t values (2, 'beta', 'payload-2')");
            executeUpdate(connection, "insert into ordered_index_lookup_t values (3, 'gamma', 'payload-3')");
            connection.commit();

            containerId = mvccContainerId(connection, "ORDERED_INDEX_LOOKUP_T");
            assertTrue("ordered index sidecar should have committed entries before routing lookups",
                    diagnostics.orderedIndexEntryCountForTesting(0, containerId) > 0L);

            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            long rowIdsBefore = diagnostics.orderedIndexRowIdCountForTesting(0, containerId);
            diagnostics.resetCandidateIndexCountersForTesting();
            diagnostics.resetScanCountersForTesting();

            assertRows(connection,
                    "select id, payload from ordered_index_lookup_t "
                            + "--DERBY-PROPERTIES index=ordered_index_lookup_code_idx\n "
                            + "where code = 'beta'",
                    "2|payload-2");

            assertTrue("current-committed equality lookup should consult ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertTrue("ordered equality lookup should hit the beta key",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) > hitBefore);
            assertEquals("ordered equality lookup should not fall back when sidecar answers",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertTrue("ordered equality lookup should return candidate row ids",
                    diagnostics.orderedIndexRowIdCountForTesting(0, containerId) > rowIdsBefore);
            assertTrue("ordered equality lookup still feeds the row-id fast path",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            assertEquals("ordered equality lookup should avoid full-scan qualifier rejection",
                    0, diagnostics.qualifierRejectCountForTesting());
            diagnostics.assertConsistentForTesting(0, containerId);

            lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id from ordered_index_lookup_t where code = 'missing'");
            assertTrue("ordered pages should answer negative equality lookups too",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertEquals("negative ordered lookup should not count as a hit",
                    hitBefore, diagnostics.orderedIndexHitCountForTesting(0, containerId));
            assertEquals("negative ordered lookup should not fall back to candidate index",
                    fallbackBefore, diagnostics.orderedIndexFallbackCountForTesting(0, containerId));
            assertEquals("negative ordered lookup should not read rows through the row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());

            executeUpdate(connection, "update ordered_index_lookup_t set code = 'omega' where id = 2");
            executeUpdate(connection, "delete from ordered_index_lookup_t where id = 3");
            connection.commit();

            lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(connection,
                    "select id, payload from ordered_index_lookup_t where code = 'omega'",
                    "2|payload-2");
            assertTrue("ordered lookup should see updated committed keys",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertTrue("ordered lookup should hit the updated key",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) > hitBefore);
            assertRows(connection,
                    "select id from ordered_index_lookup_t where code = 'gamma'");
            diagnostics.assertConsistentForTesting(0, containerId);
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long hitBefore = diagnostics.orderedIndexHitCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(reopened,
                    "select id, payload from ordered_index_lookup_t where code = 'omega'",
                    "2|payload-2");
            assertTrue("reopened equality lookup should still consult ordered index pages",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertTrue("reopened ordered equality lookup should hit the durable sidecar",
                    diagnostics.orderedIndexHitCountForTesting(0, containerId) > hitBefore);
            assertTrue("reopened ordered equality lookup should still feed row-id point reads",
                    diagnostics.rowIdFastPathReadCountForTesting() > 0);
            diagnostics.assertConsistentForTesting(0, containerId);
        }
    }

    public void testStableSnapshotsAndLocalWritersDoNotUseOrderedCurrentCommittedLookup() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-equality-snapshot-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection setup = openDatabase(databaseName, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup, "create table ordered_index_snapshot_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(setup, "insert into ordered_index_snapshot_t values (1, 'alpha', 'payload-1')");
            executeUpdate(setup, "insert into ordered_index_snapshot_t values (2, 'beta', 'payload-2')");
            setup.commit();
            containerId = mvccContainerId(setup, "ORDERED_INDEX_SNAPSHOT_T");
            setup.rollback();
        }

        try (Connection reader = openDatabase(databaseName, false);
                Connection writer = openDatabase(databaseName, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader,
                    "select id, payload from ordered_index_snapshot_t where code = 'alpha'",
                    "1|payload-1");
            executeUpdate(writer, "update ordered_index_snapshot_t set code = 'omega', payload = 'payload-1-new' where id = 1");
            writer.commit();

            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, payload from ordered_index_snapshot_t where code = 'alpha'",
                    "1|payload-1");
            assertEquals("stable snapshots must not use current-committed ordered index pages",
                    lookupBefore, diagnostics.orderedIndexLookupCountForTesting(0, containerId));
            assertEquals("stable snapshots must not use current-committed row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            reader.commit();
        }

        try (Connection writer = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);
            executeUpdate(writer, "update ordered_index_snapshot_t set code = 'local', payload = 'payload-2-local' where id = 2");
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            diagnostics.resetScanCountersForTesting();
            assertRows(writer,
                    "select id, payload from ordered_index_snapshot_t where code = 'local'",
                    "2|payload-2-local");
            assertEquals("writer-borrowed reads must not use current-committed ordered index pages",
                    lookupBefore, diagnostics.orderedIndexLookupCountForTesting(0, containerId));
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
