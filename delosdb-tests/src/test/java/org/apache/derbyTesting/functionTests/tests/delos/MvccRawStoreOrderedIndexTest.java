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
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** RawStore-owned MVCC ordered-index visibility, recovery, and compatibility proofs. */
public final class MvccRawStoreOrderedIndexTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final long CURRENT_END_SEQUENCE = Long.MAX_VALUE;

    public void testEqualityRangeOrderingAndReopenUseRawStoreIndex() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table indexed_t (id int, name varchar(64), score int, padding varchar(600)) using delos_mvcc");
                for (int id : new int[] {1, 2, 3, 4, 5, 6, 7, 8, 10}) {
                    String padding = "pad-" + id + '-' + "x".repeat(400);
                    executeUpdate(connection, "insert into indexed_t values ("
                            + id + ", 'name-" + id + "', " + (id * 10) + ", '" + padding + "')");
                }
                connection.commit();

                assertIndexedRows(connection,
                        "select id, name from indexed_t where id = 6",
                        "6|name-6");
                assertIndexedRows(connection,
                        "select id, score from indexed_t where score >= 30 and score < 60 order by score",
                        "3|30",
                        "4|40",
                        "5|50");

                long indexContainer = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        connection,
                        "INDEXED_T");
                assertTrue("ordered-index container must be persisted", indexContainer > 0L);
                assertTrue("ordered-index proof must cross a RawStore page boundary",
                        MvccRawStoreMetadataInspection.orderedIndexPageCount(
                                connection,
                                "INDEXED_T") > 1L);
                List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries =
                        MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "INDEXED_T");
                assertEquals(36, entries.size());
                assertPhysicalTypedOrder(entries);
                for (MvccRawStoreMetadataInspection.OrderedIndexIdentity entry : entries) {
                    assertEquals(1L, entry.beginCommitSequence());
                    assertEquals(CURRENT_END_SEQUENCE, entry.endCommitSequence());
                }
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

    public void testUpdateDeleteSavepointAndHistoricalSnapshotsKeepIndexVisibility() throws Exception {
        String database = databaseName("mvcc-raw-store-ordered-index-history");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table index_anchor (id int) using delos_mvcc");
                executeUpdate(setup,
                        "create table index_history (id int, name varchar(64), score int) using delos_mvcc");
                executeUpdate(setup, "insert into index_anchor values (1)");
                executeUpdate(setup, "insert into index_history values (1, 'old', 10)");
                executeUpdate(setup, "insert into index_history values (2, 'delete-old', 20)");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
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
                assertTrue("historical ordered-index entries must remain present",
                        entries.stream().anyMatch(entry -> entry.versionId() == 1L
                                && entry.endCommitSequence() == 2L));
                assertTrue("replacement ordered-index entries must be current",
                        entries.stream().anyMatch(entry -> entry.versionId() == 3L
                                && entry.beginCommitSequence() == 2L
                                && entry.endCommitSequence() == CURRENT_END_SEQUENCE));
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
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table legacy_index_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into legacy_index_t values (1, 'one')");
                executeUpdate(connection, "insert into legacy_index_t values (2, 'two')");
                connection.commit();

                MvccRawStoreMetadataInspection.removeOrderedIndexForCompatibility(
                        connection,
                        "LEGACY_INDEX_T");
                connection.commit();
                assertEquals(0L, MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        connection,
                        "LEGACY_INDEX_T"));
                assertRows(connection,
                        "select id, name from legacy_index_t where id = 2",
                        "2|two");

                executeUpdate(connection,
                        "update legacy_index_t set name = 'two-v2' where id = 2");
                connection.commit();
                assertTrue(MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        connection,
                        "LEGACY_INDEX_T") > 0L);
                assertIndexedRows(connection,
                        "select id, name from legacy_index_t where name = 'two-v2'",
                        "2|two-v2");
                assertEquals(6,
                        MvccRawStoreMetadataInspection.orderedIndexEntries(
                                connection,
                                "LEGACY_INDEX_T").size());
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

    private static void assertPhysicalTypedOrder(
            List<MvccRawStoreMetadataInspection.OrderedIndexIdentity> entries) {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "10"),
                entries.subList(0, 9).stream()
                        .map(MvccRawStoreMetadataInspection.OrderedIndexIdentity::key)
                        .toList());
        assertEquals(
                List.of("name-1", "name-10", "name-2", "name-3", "name-4",
                        "name-5", "name-6", "name-7", "name-8"),
                entries.subList(9, 18).stream()
                        .map(MvccRawStoreMetadataInspection.OrderedIndexIdentity::key)
                        .toList());
        assertEquals(
                List.of("10", "20", "30", "40", "50", "60", "70", "80", "100"),
                entries.subList(18, 27).stream()
                        .map(MvccRawStoreMetadataInspection.OrderedIndexIdentity::key)
                        .toList());
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
