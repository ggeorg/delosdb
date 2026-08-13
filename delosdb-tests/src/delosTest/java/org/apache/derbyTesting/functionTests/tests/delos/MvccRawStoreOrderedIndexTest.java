/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreOrderedIndexTest

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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** RawStore-owned MVCC index-candidate visibility, recovery, and compatibility proofs. */
public final class MvccRawStoreOrderedIndexTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testEqualityRangeCandidateLookupAndReopenUseRawStoreIndex() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table indexed_t (id int, name varchar(64), score int, "
                                + "padding varchar(600)) using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "INDEXED_T", 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "INDEXED_T", 1, 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "INDEXED_T", 2, 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "INDEXED_T", 3, 0);
                for (int id : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 10}) {
                    String padding = "pad-" + id + '-' + "x".repeat(400);
                    executeUpdate(connection, "insert into indexed_t values ("
                            + id + ", 'name-" + id + "', " + (id * 10) + ", '" + padding + "')");
                }
                connection.commit();

                assertIndexedRows(connection,
                        "select id, name from indexed_t where id = 6",
                        "6|name-6");
                try (PreparedStatement lookup = connection.prepareStatement(
                        "select name from indexed_t where id = ?")) {
                    for (int id : new int[] {6, 4, 6}) {
                        lookup.setInt(1, id);
                        try (ResultSet resultSet = lookup.executeQuery()) {
                            assertTrue(resultSet.next());
                            assertEquals("name-" + id, resultSet.getString(1));
                            assertFalse(resultSet.next());
                        }
                        connection.commit();
                    }
                }
                assertIndexedRows(connection,
                        "select id, score from indexed_t where score >= 30 and score < 60 order by score",
                        "3|30",
                        "4|40",
                        "5|50");

                long indexContainer = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        connection,
                        "INDEXED_T");
                assertTrue("ordered-index container must be persisted", indexContainer > 0L);
                assertEquals("one compatibility B-tree slot per orderable column",
                        4,
                        MvccRawStoreMetadataInspection.orderedIndexBtreeCount(
                                connection,
                                "INDEXED_T"));
                assertTrue("ordered-index proof must cross a RawStore page boundary",
                        MvccRawStoreMetadataInspection.orderedIndexPageCount(
                                connection,
                                "INDEXED_T") > 1L);
                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "INDEXED_T");
                assertEquals(36, entries.size());
                assertTrue(
                        "fresh B-tree candidates must carry direct directory locators",
                        entries.stream().allMatch(
                                MvccRawStoreMetadataInspection.OrderedIndexIdentity::
                                        hasDirectoryLocator));
                assertPhysicalTypedCoverage(entries);
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertIndexedRows(reopened,
                        "select id, name from indexed_t where name = 'name-4'",
                        "4|name-4");
                assertIndexedRows(reopened,
                        "select id, score from indexed_t where score > 60 order by score",
                        "7|70",
                        "8|80",
                        "10|100");
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testOrdinaryNonUniqueColumnsDoNotReceiveHiddenCandidates() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index-policy");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table policy_t (id int primary key, category int, payload varchar(600)) "
                            + "using delos_mvcc");
            executeUpdate(connection, "insert into policy_t values (1, 7, 'one')");
            executeUpdate(connection, "insert into policy_t values (2, 7, 'two')");
            connection.commit();

            List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries =
                    MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "POLICY_T");
            assertEquals(2, entries.size());
            assertTrue(entries.stream().allMatch(entry -> entry.columnId() == 0));

            executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
            assertRows(connection,
                    "select id from policy_t where category = 7 order by id",
                    "1",
                    "2");
            assertFalse(runtimeStatistics(connection).contains(
                    "delos_mvcc_rawstore_ordered_index"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testProjectedScansKeepQualifierColumnsAndMaterializePayloadOnDemand()
            throws Exception {
        String database = databaseName("mvcc-raw-store-projected-scan");
        String payloadOne = "payload-one-" + "x".repeat(500);
        String payloadTwo = "payload-two-" + "y".repeat(500);
        String payloadThree = "payload-three-" + "z".repeat(500);
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table projection_t (id int primary key, category int, "
                                + "quantity int, payload varchar(600)) using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "PROJECTION_T", 1, 0);
                executeUpdate(setup,
                        "insert into projection_t values (1, 7, 10, '" + payloadOne + "')");
                executeUpdate(setup,
                        "insert into projection_t values (2, 7, 20, '" + payloadTwo + "')");
                executeUpdate(setup,
                        "insert into projection_t values (3, 8, 30, '" + payloadThree + "')");
                setup.commit();

                assertRows(setup,
                        "select id from projection_t order by id",
                        "1",
                        "2",
                        "3");
                assertIndexedRows(setup,
                        "select id from projection_t "
                                + "where category = 7 and quantity >= 20 order by id",
                        "2");
                assertIndexedRows(setup,
                        "select id, quantity from projection_t where id = 2",
                        "2|20");
                assertIndexedRows(setup,
                        "select payload from projection_t where id = 2",
                        payloadTwo);
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertIndexedRows(historical,
                        "select id from projection_t where category = 7 order by id",
                        "1",
                        "2");

                executeUpdate(writer,
                        "update projection_t set quantity = 25 where id = 2");
                writer.commit();

                assertIndexedRows(historical,
                        "select id, quantity from projection_t "
                                + "where category = 7 and quantity >= 20 order by id",
                        "2|20");
                historical.commit();
                assertIndexedRows(historical,
                        "select id, quantity from projection_t "
                                + "where category = 7 and quantity >= 20 order by id",
                        "2|25");
                historical.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testCoveringCandidateUsesVisibleHeadAndFallsBackForChangedHead()
            throws Exception {
        String database = databaseName("mvcc-raw-store-covering-index");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table covering_t (id int primary key, category int, "
                            + "payload varchar(600)) using delos_mvcc");
            MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "COVERING_T", 1, 0);
            executeUpdate(connection,
                    "insert into covering_t values (1, 7, '" + "x".repeat(500) + "')");
            executeUpdate(connection,
                    "insert into covering_t values (2, 7, '" + "y".repeat(500) + "')");
            connection.commit();

            assertCoveringIndexedRows(connection,
                    "select id from covering_t where id = 2",
                    "2");
            assertCoveringIndexedRows(connection,
                    "select category from covering_t where category = 7",
                    "7",
                    "7");
            String coveringStatistics = assertCoveringIndexedRows(connection,
                    "select count(*) from covering_t where category = 7",
                    "2");
            assertScanMetric(coveringStatistics, "mvccOrderedCandidates", 2L);
            assertScanMetric(coveringStatistics, "mvccCoveringCandidates", 2L);
            assertScanMetric(coveringStatistics, "mvccCoveredCandidates", 2L);
            assertScanMetric(coveringStatistics, "mvccFallbackCandidates", 0L);
            assertScanMetric(coveringStatistics, "mvccDirectoryPageAcquisitions", 1L);
            assertScanMetric(coveringStatistics, "mvccDirectoryPageBatchCandidates", 2L);
            assertScanMetric(coveringStatistics, "mvccDirectoryPageReuseHits", 1L);
            assertScanMetric(coveringStatistics, "mvccDirectoryLogicalFallbacks", 0L);
            assertScanMetric(coveringStatistics, "mvccDirectoryHeadSummaryChecks", 2L);
            assertScanMetric(coveringStatistics, "mvccDirectoryHeadSummaryHits", 2L);
            assertScanMetric(coveringStatistics, "mvccDirectoryHeadSummaryFallbacks", 0L);
            assertScanMetric(coveringStatistics, "mvccVersionPageAcquisitions", 0L);
            assertScanMetric(coveringStatistics, "mvccVersionSlotFetches", 0L);
            assertScanMetric(coveringStatistics, "mvccVisibilityChecks", 2L);
            assertScanMetric(coveringStatistics, "mvccVersionChainSteps", 0L);
            assertScanMetric(coveringStatistics, "mvccVersionLogicalFallbacks", 0L);
            String anchoredStatistics = assertNonCoveringIndexedRows(connection,
                    "select id, category from covering_t where category = 7 order by id",
                    "1|7",
                    "2|7");
            assertScanMetric(anchoredStatistics, "mvccCurrentRowAnchorChecks", 2L);
            assertScanMetric(anchoredStatistics, "mvccCurrentRowAnchorHits", 2L);
            assertScanMetric(anchoredStatistics, "mvccCurrentRowAnchorFallbacks", 0L);
            assertScanMetric(anchoredStatistics, "mvccVersionPageAcquisitions", 0L);

            executeUpdate(connection,
                    "update covering_t set payload = '" + "z".repeat(500) + "' where id = 2");
            connection.commit();
            String unchangedKeyAnchorStatistics = assertNonCoveringIndexedRows(connection,
                    "select category, payload from covering_t where category = 7 order by id",
                    "7|" + "x".repeat(500),
                    "7|" + "z".repeat(500));
            assertScanMetric(unchangedKeyAnchorStatistics, "mvccCurrentRowAnchorChecks", 2L);
            assertScanMetric(unchangedKeyAnchorStatistics, "mvccCurrentRowAnchorHits", 2L);
            assertScanMetric(unchangedKeyAnchorStatistics, "mvccCurrentRowAnchorFallbacks", 0L);
            assertScanMetric(unchangedKeyAnchorStatistics, "mvccVersionPageAcquisitions", 0L);

            executeUpdate(connection,
                    "update covering_t set category = 9 where id = 2");
            connection.commit();
            assertCoveringIndexedRows(connection,
                    "select category from covering_t where category = 9",
                    "9");

            executeUpdate(connection, "delete from covering_t where id = 1");
            connection.commit();
            assertNonCoveringIndexedRows(connection,
                    "select category from covering_t where category = 7");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testCoveringCandidateFallsBackForHistoricalSnapshot() throws Exception {
        String database = databaseName("mvcc-raw-store-covering-snapshot");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table covering_snapshot_t (id int primary key, category int) "
                                + "using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(
                        setup, "COVERING_SNAPSHOT_T", 1, 0);
                executeUpdate(setup,
                        "insert into covering_snapshot_t values (1, 7)");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertCoveringIndexedRows(historical,
                        "select category from covering_snapshot_t where category = 7",
                        "7");

                executeUpdate(writer,
                        "update covering_snapshot_t set category = 9 where id = 1");
                writer.commit();

                String historicalStatistics = assertNonCoveringIndexedRows(historical,
                        "select id, category from covering_snapshot_t where category = 7",
                        "1|7");
                assertScanMetric(historicalStatistics, "mvccCurrentRowAnchorChecks", 1L);
                assertScanMetric(historicalStatistics, "mvccCurrentRowAnchorHits", 0L);
                assertScanMetric(historicalStatistics, "mvccCurrentRowAnchorFallbacks", 1L);
                historical.commit();
                assertCoveringIndexedRows(historical,
                        "select category from covering_snapshot_t where category = 9",
                        "9");
                historical.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testDirectoryHeadSummaryHandlesOwnWritesOtherTransactionsAndRollback()
            throws Exception {
        String database = databaseName("mvcc-raw-store-directory-head-summary");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table head_summary_t (id int primary key, category int) "
                                + "using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(
                        connection, "HEAD_SUMMARY_T", 1, 0);
                connection.commit();

                executeUpdate(connection, "insert into head_summary_t values (1, 7)");
                String ownWriteStatistics = assertCoveringIndexedRows(connection,
                        "select category from head_summary_t where category = 7",
                        "7");
                assertScanMetric(
                        ownWriteStatistics, "mvccDirectoryHeadSummaryChecks", 1L);
                assertScanMetric(
                        ownWriteStatistics, "mvccDirectoryHeadSummaryHits", 1L);
                assertScanMetric(ownWriteStatistics, "mvccVersionPageAcquisitions", 0L);
                connection.rollback();
                assertNonCoveringIndexedRows(connection,
                        "select category from head_summary_t where category = 7");

                executeUpdate(connection, "insert into head_summary_t values (1, 7)");
                connection.commit();
            }

            try (Connection reader = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                reader.setAutoCommit(false);
                writer.setAutoCommit(false);

                executeUpdate(writer,
                        "update head_summary_t set category = 9 where id = 1");

                assertNonCoveringIndexedRows(reader,
                        "select category from head_summary_t where category = 7",
                        "7");
                String otherTransactionStatistics = assertNonCoveringIndexedRows(reader,
                        "select id, category from head_summary_t where category = 9");
                assertScanMetric(otherTransactionStatistics, "mvccCurrentRowAnchorChecks", 1L);
                assertScanMetric(otherTransactionStatistics, "mvccCurrentRowAnchorHits", 0L);
                assertScanMetric(otherTransactionStatistics, "mvccCurrentRowAnchorFallbacks", 1L);
                assertScanMetric(
                        otherTransactionStatistics, "mvccDirectoryHeadSummaryChecks", 0L);
                assertScanMetric(
                        otherTransactionStatistics, "mvccDirectoryHeadSummaryHits", 0L);
                assertScanMetric(
                        otherTransactionStatistics, "mvccDirectoryHeadSummaryFallbacks", 0L);

                writer.rollback();
                reader.commit();
                String restoredStatistics = assertCoveringIndexedRows(reader,
                        "select category from head_summary_t where category = 7",
                        "7");
                assertScanMetric(
                        restoredStatistics, "mvccDirectoryHeadSummaryHits", 1L);
                assertScanMetric(restoredStatistics, "mvccVersionPageAcquisitions", 0L);
                reader.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testWideVarcharKeysUseLargePagesAndOversizeKeysFallBack() throws Exception {
        String database = databaseName("mvcc-raw-store-wide-ordered-index");
        String pageSizedPayload = "p".repeat(4096);
        String oversizePayload = "o".repeat(32672);
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table page_sized_t (id int primary key, payload varchar(4096)) "
                            + "using delos_mvcc");
            executeUpdate(connection,
                    "create table oversize_t (id int primary key, payload varchar(32672)) "
                            + "using delos_mvcc");
            MvccRawStoreMetadataInspection.addNativeUniqueConstraint(
                    connection, "PAGE_SIZED_T", 1, 0);
            MvccRawStoreMetadataInspection.addNativeUniqueConstraint(
                    connection, "OVERSIZE_T", 1, 0);

            insertWideRow(connection, "page_sized_t", 1, pageSizedPayload);
            insertWideRow(connection, "oversize_t", 1, "small");
            connection.commit();

            assertPreparedLookup(
                    connection,
                    "select id from oversize_t where payload = ?",
                    "small",
                    1,
                    true);
            connection.commit();

            insertWideRow(connection, "oversize_t", 2, oversizePayload);
            connection.commit();

            assertPreparedLookup(
                    connection,
                    "select id from page_sized_t where payload = ?",
                    pageSizedPayload,
                    1,
                    true);
            assertPreparedLookup(
                    connection,
                    "select id from oversize_t where payload = ?",
                    oversizePayload,
                    2,
                    false);
            assertPreparedLookup(
                    connection,
                    "select id from oversize_t where payload = ?",
                    "small",
                    1,
                    false);
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testUpdateDeleteSavepointAndHistoricalSnapshotsKeepIndexVisibility() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index-history");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table index_anchor (id int) using delos_mvcc");
                executeUpdate(setup,
                        "create table index_history (id int, name varchar(64), score int) using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_HISTORY", 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_HISTORY", 1, 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_HISTORY", 2, 0);
                executeUpdate(setup, "insert into index_anchor values (1)");
                executeUpdate(setup, "insert into index_history values (1, 'old', 10)");
                executeUpdate(setup, "insert into index_history values (2, 'delete-old', 20)");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertRows(historical, "select id from index_anchor", "1");

                executeUpdate(writer,
                        "update index_history set name = 'new', score = 30 where id = 1");
                executeUpdate(writer, "delete from index_history where id = 2");
                assertIndexedRows(writer,
                        "select id, name, score from index_history where score = 30",
                        "1|new|30");
                assertIndexedRows(writer,
                        "select id from index_history where score = 10");

                Savepoint savepoint = writer.setSavepoint("before_index_rollback");
                executeUpdate(writer,
                        "update index_history set name = 'rolled-back', score = 40 where id = 1");
                assertIndexedRows(writer,
                        "select id, name from index_history where score = 40",
                        "1|rolled-back");
                writer.rollback(savepoint);
                writer.releaseSavepoint(savepoint);
                assertIndexedRows(writer,
                        "select id, name from index_history where score = 30",
                        "1|new");
                assertIndexedRows(writer,
                        "select id from index_history where score = 40");
                writer.commit();

                assertIndexedRows(historical,
                        "select id, name from index_history where score = 10",
                        "1|old");
                assertIndexedRows(historical,
                        "select id, name from index_history where score = 20",
                        "2|delete-old");
                historical.commit();

                assertIndexedRows(historical,
                        "select id, name, score from index_history where score = 30",
                        "1|new|30");
                assertIndexedRows(historical,
                        "select id from index_history where score = 20");
                historical.commit();

                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(writer, "INDEX_HISTORY");
                assertTrue("historical ordered-index candidates must remain present",
                        entries.stream().anyMatch(entry -> entry.columnId() == 1
                                && entry.versionId() == 1L
                                && "old".equals(entry.key())));
                assertTrue("replacement ordered-index candidates must remain present",
                        entries.stream().anyMatch(entry -> entry.columnId() == 1
                                && entry.versionId() == 3L
                                && "new".equals(entry.key())));
                writer.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testOrderedIndexSurvivesBothRawStoreCrashBoundaries() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);
    }

    public void testPreIndexCompatibilityLazyRebuildAndMemoryUseSameRawStorePath() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index-compat");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table legacy_index_t (id int, name varchar(64)) using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "LEGACY_INDEX_T", 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "LEGACY_INDEX_T", 1, 0);
                executeUpdate(setup, "insert into legacy_index_t values (1, 'one')");
                executeUpdate(setup, "insert into legacy_index_t values (2, 'two')");
                setup.commit();

                MvccRawStoreMetadataInspection.removeOrderedIndexForCompatibility(
                        setup,
                        "LEGACY_INDEX_T");
                setup.commit();
                assertEquals(0L, MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        setup,
                        "LEGACY_INDEX_T"));
                setup.commit();
            }
            shutdownDatabase(database);

            try (Connection connection = openDatabase(database, false)) {
                connection.setAutoCommit(false);
                assertRows(connection,
                        "select id, name from legacy_index_t where id = 2",
                        "2|two");

                executeUpdate(connection,
                        "update legacy_index_t set name = 'two-v2' where id = 2");
                connection.commit();
                assertTrue(MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        connection,
                        "LEGACY_INDEX_T") > 0L);
                executeUpdate(
                        connection,
                        "call syscs_util.syscs_set_runtimestatistics(1)");
                assertRows(connection,
                        "select id, name from legacy_index_t where name = 'two-v2'",
                        "2|two-v2");
                assertFalse(
                        "pre-constraint metadata must fall back instead of rebuilding "
                                + "non-unique hidden candidates",
                        runtimeStatistics(connection).contains(
                                "delos_mvcc_rawstore_ordered_index"));
                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> rebuiltEntries =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(
                                connection,
                                "LEGACY_INDEX_T");
                assertTrue(rebuiltEntries.isEmpty());
                connection.commit();
            }
            shutdownDatabase(database);
        }

        String memoryDatabase = "mvcc_raw_store_ordered_index_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_index_t (id int, name varchar(64), score int) using delos_mvcc");
            MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "MEMORY_INDEX_T", 0);
            MvccRawStoreMetadataInspection.addNativeUniqueConstraint(connection, "MEMORY_INDEX_T", 2, 0);
            executeUpdate(connection, "insert into memory_index_t values (1, 'one', 10)");
            executeUpdate(connection, "insert into memory_index_t values (2, 'two', 20)");
            connection.commit();
            assertIndexedRows(connection,
                    "select id, name from memory_index_t where score >= 10 and score <= 20 order by score",
                    "1|one",
                    "2|two");
            executeUpdate(connection,
                    "update memory_index_t set score = 30 where id = 2");
            connection.rollback();
            assertIndexedRows(connection,
                    "select id from memory_index_t where score = 20",
                    "2");
            connection.commit();
        }
        shutdownMemoryDatabase(memoryDatabase);
    }


    public void testUnchangedIndexedValuesDoNotCreateRedundantCandidates() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index-delta");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table index_delta (id int, name varchar(64), quantity int) "
                                + "using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_DELTA", 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_DELTA", 1, 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "INDEX_DELTA", 2, 0);
                executeUpdate(setup, "insert into index_delta values (1, 'one', 10)");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertIndexedRows(historical,
                        "select id, name, quantity from index_delta where id = 1",
                        "1|one|10");

                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> initial =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(
                                writer, "INDEX_DELTA");
                assertEquals("one initial candidate per indexed column", 3, initial.size());

                executeUpdate(writer,
                        "update index_delta set quantity = quantity + 1 where id = 1");
                writer.commit();

                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> updated =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(
                                writer, "INDEX_DELTA");
                assertEquals("only the changed quantity key adds a candidate", 4, updated.size());
                assertFalse("unchanged id key must not gain a second-version candidate",
                        updated.stream().anyMatch(entry -> entry.columnId() == 0
                                && entry.versionId() == 2L));
                assertFalse("unchanged name key must not gain a second-version candidate",
                        updated.stream().anyMatch(entry -> entry.columnId() == 1
                                && entry.versionId() == 2L));
                assertTrue("changed quantity key must retain its new historical candidate",
                        updated.stream().anyMatch(entry -> entry.columnId() == 2
                                && entry.versionId() == 2L
                                && "11".equals(entry.key())));

                assertIndexedRows(writer,
                        "select id, name, quantity from index_delta where id = 1",
                        "1|one|11");
                writer.commit();
                assertIndexedRows(historical,
                        "select id, name, quantity from index_delta where id = 1",
                        "1|one|10");
                historical.commit();
            }
            shutdownDatabase(database);
        }
    }

    private static void assertPhysicalTypedCoverage(
            List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries) {
        int[] ids = {1, 2, 3, 4, 5, 6, 7, 8, 10};
        assertColumnKeys(entries, 0, Set.of("1", "2", "3", "4", "5", "6", "7", "8", "10"));
        assertColumnKeys(entries, 1, Set.of(
                "name-1", "name-2", "name-3", "name-4", "name-5",
                "name-6", "name-7", "name-8", "name-10"));
        assertColumnKeys(entries, 2, Set.of(
                "10", "20", "30", "40", "50", "60", "70", "80", "100"));
        assertColumnKeys(entries, 3, java.util.Arrays.stream(ids)
                .mapToObj(id -> "pad-" + id + '-' + "x".repeat(400))
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private static void assertColumnKeys(
            List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries,
            int columnId,
            Set<String> expectedKeys) {
        List<String> keys = entries.stream()
                .filter(entry -> entry.columnId() == columnId)
                .map(MvccRawStoreMetadataInspection.OrderedIndexIdentity::key)
                .toList();
        assertEquals(expectedKeys.size(), keys.size());
        assertEquals(expectedKeys, Set.copyOf(keys));
    }

    private static String assertCoveringIndexedRows(
            Connection connection,
            String sql,
            String... expectedRows) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        assertRows(connection, sql, expectedRows);
        String statistics = runtimeStatistics(connection);
        assertTrue("expected RawStore MVCC covering ordered-index scan; statistics=" + statistics,
                statistics.contains("delos_mvcc_rawstore_ordered_index_covering"));
        return statistics;
    }

    private static void assertScanMetric(String statistics, String name, long expected) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?m)^\\s*" + java.util.regex.Pattern.quote(name)
                        + "\\s*=\\s*" + expected + "\\s*$");
        assertTrue(
                "expected scan metric " + name + '=' + expected + "; statistics=" + statistics,
                pattern.matcher(statistics).find());
    }

    private static String assertNonCoveringIndexedRows(
            Connection connection,
            String sql,
            String... expectedRows) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        assertRows(connection, sql, expectedRows);
        String statistics = runtimeStatistics(connection);
        assertTrue("expected RawStore MVCC ordered-index scan; statistics=" + statistics,
                statistics.contains("delos_mvcc_rawstore_ordered_index"));
        assertFalse("unexpected RawStore MVCC covering scan; statistics=" + statistics,
                statistics.contains("delos_mvcc_rawstore_ordered_index_covering"));
        return statistics;
    }

    private static void assertIndexedRows(
            Connection connection,
            String sql,
            String... expectedRows) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        assertRows(connection, sql, expectedRows);
        String statistics = runtimeStatistics(connection);
        assertTrue("expected RawStore MVCC ordered-index scan; statistics=" + statistics,
                statistics.contains("delos_mvcc_rawstore_ordered_index"));
    }

    private static void insertWideRow(
            Connection connection,
            String table,
            int id,
            String payload) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?)")) {
            insert.setInt(1, id);
            insert.setString(2, payload);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void assertPreparedLookup(
            Connection connection,
            String sql,
            String payload,
            int expectedId,
            boolean expectOrderedIndex) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        try (PreparedStatement lookup = connection.prepareStatement(sql)) {
            lookup.setString(1, payload);
            try (ResultSet resultSet = lookup.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(expectedId, resultSet.getInt(1));
                assertFalse(resultSet.next());
            }
        }
        String statistics = runtimeStatistics(connection);
        assertEquals(
                "unexpected RawStore MVCC ordered-index eligibility; statistics=" + statistics,
                expectOrderedIndex,
                statistics.contains("delos_mvcc_rawstore_ordered_index"));
    }

    private static String runtimeStatistics(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-ordered-index-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_index_t (id int, name varchar(64), score int) using delos_mvcc");
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "CRASH_INDEX_T", 0);
                MvccRawStoreMetadataInspection.addNativeUniqueConstraint(setup, "CRASH_INDEX_T", 2, 0);
                executeUpdate(setup, "insert into crash_index_t values (1, 'old', 10)");
                executeUpdate(setup, "insert into crash_index_t values (2, 'delete-old', 20)");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + '=' + failurePoint,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore MVCC ordered-index crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            if (expectCommitted) {
                assertIndexedRows(recovered,
                        "select id, name from crash_index_t where score = 30",
                        "1|new");
                assertIndexedRows(recovered,
                        "select id from crash_index_t where score = 20");
            } else {
                assertIndexedRows(recovered,
                        "select id, name from crash_index_t where score = 10",
                        "1|old");
                assertIndexedRows(recovered,
                        "select id, name from crash_index_t where score = 20",
                        "2|delete-old");
            }
            recovered.commit();
        }
        shutdownDatabase(database);
    }

    private static void shutdownMemoryDatabase(String databaseName) throws Exception {
        try {
            DriverManager.getConnection(
                    "jdbc:derby:memory:" + databaseName + ";shutdown=true");
            fail("Memory database shutdown should throw the normal Derby shutdown exception");
        } catch (SQLException expected) {
            if (!"08006".equals(expected.getSQLState())) {
                throw expected;
            }
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Child JVM stopped on either side of the inherited RawStore commit record. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "update crash_index_t set name = 'new', score = 30 where id = 1");
                executeUpdate(connection, "delete from crash_index_t where id = 2");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
