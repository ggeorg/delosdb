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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;

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

            // The ordered MVCC index pages are a provider sidecar consulted by
            // the base-table current-committed equality narrowing path. Do not
            // force Derby's inherited secondary-index access path here; that
            // path is a later replacement gate.
            assertRows(connection,
                    "select id, payload from ordered_index_lookup_t where code = 'beta'",
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

    public void testUnsupportedOrderedIndexPredicateFallsBackWithReason() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-unsupported-fallback-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_unsupported_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into ordered_index_unsupported_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into ordered_index_unsupported_t values (2, 'beta', 'payload-2')");
            connection.commit();

            long containerId = mvccContainerId(connection, "ORDERED_INDEX_UNSUPPORTED_T");
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            long unsupportedBefore = diagnostics.orderedIndexFallbackReasonCountForTesting(
                    0,
                    containerId,
                    DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE);
            diagnostics.resetScanCountersForTesting();

            assertRows(connection,
                    "select id from ordered_index_unsupported_t where code <> 'missing'",
                    "1", "2");

            assertEquals("unsupported predicates should not claim an ordered-index execution shortcut",
                    lookupBefore, diagnostics.orderedIndexLookupCountForTesting(0, containerId));
            assertTrue("unsupported predicates should record a safe ordered-index fallback",
                    diagnostics.orderedIndexFallbackCountForTesting(0, containerId) > fallbackBefore);
            assertTrue("unsupported predicates should record an explicit fallback reason",
                    diagnostics.orderedIndexFallbackReasonCountForTesting(
                            0,
                            containerId,
                            DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE) > unsupportedBefore);
            assertEquals("unsupported predicates should continue through the full scan path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            connection.rollback();
        }

        shutdownDatabase(databaseName);
    }

    public void testMissingOrderedIndexSidecarFallsBackWithReason() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-missing-sidecar-fallback-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        Path orderedIndexPagesFile;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_missing_sidecar_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into ordered_index_missing_sidecar_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into ordered_index_missing_sidecar_t values (2, 'beta', 'payload-2')");
            connection.commit();
            containerId = mvccContainerId(connection, "ORDERED_INDEX_MISSING_SIDECAR_T");
            orderedIndexPagesFile = diagnostics.orderedIndexPagesFileForTesting(0, containerId);
            assertTrue("ordered index sidecar should exist before intentional removal",
                    Files.exists(orderedIndexPagesFile));
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        Files.deleteIfExists(orderedIndexPagesFile);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            long missingBefore = diagnostics.orderedIndexFallbackReasonCountForTesting(
                    0,
                    containerId,
                    DelosStorageOrderedIndexFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR);
            diagnostics.resetScanCountersForTesting();

            assertRows(reopened,
                    "select id, payload from ordered_index_missing_sidecar_t where code = 'beta'",
                    "2|payload-2");

            assertTrue("missing ordered-index sidecar should still be looked up diagnostically",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertTrue("missing ordered-index sidecar should record safe fallback",
                    diagnostics.orderedIndexFallbackCountForTesting(0, containerId) > fallbackBefore);
            assertTrue("missing ordered-index sidecar should record a stale/missing fallback reason",
                    diagnostics.orderedIndexFallbackReasonCountForTesting(
                            0,
                            containerId,
                            DelosStorageOrderedIndexFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR)
                            > missingBefore);
            assertEquals("missing sidecar fallback must not claim row-id shortcut execution",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
        }
    }

    public void testMalformedOrderedIndexSidecarFallsBackWithReason() throws Exception {
        String databaseName = databaseName("mvcc-ordered-index-malformed-sidecar-fallback-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;
        Path orderedIndexPagesFile;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table ordered_index_malformed_sidecar_t "
                    + "(id int primary key, code varchar(32), payload varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into ordered_index_malformed_sidecar_t values (1, 'alpha', 'payload-1')");
            executeUpdate(connection, "insert into ordered_index_malformed_sidecar_t values (2, 'beta', 'payload-2')");
            connection.commit();
            containerId = mvccContainerId(connection, "ORDERED_INDEX_MALFORMED_SIDECAR_T");
            orderedIndexPagesFile = diagnostics.orderedIndexPagesFileForTesting(0, containerId);
            assertTrue("ordered index sidecar should exist before intentional corruption",
                    Files.exists(orderedIndexPagesFile));
            connection.rollback();
        }

        shutdownDatabase(databaseName);
        Files.write(orderedIndexPagesFile, new byte[] {0x44, 0x45, 0x4c, 0x4f, 0x53});

        try (Connection reopened = openDatabase(databaseName, false)) {
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long fallbackBefore = diagnostics.orderedIndexFallbackCountForTesting(0, containerId);
            long malformedBefore = diagnostics.orderedIndexFallbackReasonCountForTesting(
                    0,
                    containerId,
                    DelosStorageOrderedIndexFallbackReason.MALFORMED_ORDERED_INDEX_SIDECAR);
            diagnostics.resetScanCountersForTesting();

            assertRows(reopened,
                    "select id, payload from ordered_index_malformed_sidecar_t where code = 'beta'",
                    "2|payload-2");

            assertTrue("malformed ordered-index sidecar should still be looked up diagnostically",
                    diagnostics.orderedIndexLookupCountForTesting(0, containerId) > lookupBefore);
            assertTrue("malformed ordered-index sidecar should record safe fallback",
                    diagnostics.orderedIndexFallbackCountForTesting(0, containerId) > fallbackBefore);
            assertTrue("malformed ordered-index sidecar should record a malformed-sidecar fallback reason",
                    diagnostics.orderedIndexFallbackReasonCountForTesting(
                            0,
                            containerId,
                            DelosStorageOrderedIndexFallbackReason.MALFORMED_ORDERED_INDEX_SIDECAR)
                            > malformedBefore);
            assertEquals("malformed sidecar fallback must not claim row-id shortcut execution",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
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
            long intentionalBefore = diagnostics.orderedIndexFallbackReasonCountForTesting(
                    0,
                    containerId,
                    DelosStorageOrderedIndexFallbackReason.INTENTIONAL_NON_SHORTCUT_READ);
            diagnostics.resetScanCountersForTesting();
            assertRows(reader,
                    "select id, payload from ordered_index_snapshot_t where code = 'alpha'",
                    "1|payload-1");
            assertEquals("stable snapshots must not use current-committed ordered index pages",
                    lookupBefore, diagnostics.orderedIndexLookupCountForTesting(0, containerId));
            assertTrue("stable snapshots should be classified as intentional non-shortcut reads",
                    diagnostics.orderedIndexFallbackReasonCountForTesting(
                            0,
                            containerId,
                            DelosStorageOrderedIndexFallbackReason.INTENTIONAL_NON_SHORTCUT_READ)
                            > intentionalBefore);
            assertEquals("stable snapshots must not use current-committed row-id fast path",
                    0, diagnostics.rowIdFastPathReadCountForTesting());
            reader.commit();
        }

        try (Connection writer = openDatabase(databaseName, false)) {
            writer.setAutoCommit(false);
            executeUpdate(writer, "update ordered_index_snapshot_t set code = 'local', payload = 'payload-2-local' where id = 2");
            long lookupBefore = diagnostics.orderedIndexLookupCountForTesting(0, containerId);
            long intentionalBefore = diagnostics.orderedIndexFallbackReasonCountForTesting(
                    0,
                    containerId,
                    DelosStorageOrderedIndexFallbackReason.INTENTIONAL_NON_SHORTCUT_READ);
            diagnostics.resetScanCountersForTesting();
            assertRows(writer,
                    "select id, payload from ordered_index_snapshot_t where code = 'local'",
                    "2|payload-2-local");
            assertEquals("writer-borrowed reads must not use current-committed ordered index pages",
                    lookupBefore, diagnostics.orderedIndexLookupCountForTesting(0, containerId));
            assertTrue("writer-borrowed reads should be classified as intentional non-shortcut reads",
                    diagnostics.orderedIndexFallbackReasonCountForTesting(
                            0,
                            containerId,
                            DelosStorageOrderedIndexFallbackReason.INTENTIONAL_NON_SHORTCUT_READ)
                            > intentionalBefore);
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
