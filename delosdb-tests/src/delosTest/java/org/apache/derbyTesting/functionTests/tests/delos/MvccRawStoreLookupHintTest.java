/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreLookupHintTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Validated RawStore page/record lookup-hint and logical-fallback proofs. */
public final class MvccRawStoreLookupHintTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testInsertAndFetchLocationReturnsStableDirectoryLocator() throws Exception {
        String database = uniqueDatabaseName("mvcc-raw-store-insert-location-hint");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table insert_hint_t (id int) using delos_mvcc");
            connection.commit();

            MvccRawStoreMetadataInspection.RowLocationIdentity inserted =
                    MvccRawStoreMetadataInspection.insertAndFetchBaseRowLocation(
                            connection,
                            "INSERT_HINT_T",
                            1);
            List<MvccRawStoreMetadataInspection.RowLocationIdentity> scanned =
                    MvccRawStoreMetadataInspection.baseScanRowLocations(
                            connection,
                            "INSERT_HINT_T");

            assertEquals(1, scanned.size());
            assertTrue(
                    "insertAndFetchLocation must return a physical locator",
                    inserted.hasLocator());
            assertEquals(
                    "insertAndFetchLocation must return the same stable-directory locator "
                            + "that a base scan exposes",
                    scanned.get(0),
                    inserted);
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testBaseScanRowLocationsPreserveDirectoryHintsForMutation() throws Exception {
        String database = uniqueDatabaseName("mvcc-raw-store-scan-location-hints");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table scan_hint_t "
                            + "(id int primary key, payload varchar(64)) using delos_mvcc");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into scan_hint_t values (?, ?)")) {
                for (int id = 1; id <= 256; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, "payload-" + id);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();

            List<MvccRawStoreMetadataInspection.RowLocationIdentity> locations =
                    MvccRawStoreMetadataInspection.baseScanRowLocations(
                            connection,
                            "SCAN_HINT_T");
            assertEquals(256, locations.size());
            for (MvccRawStoreMetadataInspection.RowLocationIdentity location : locations) {
                assertTrue(
                        "base scans must preserve the stable-directory locator hint",
                        location.hasLocator());
            }

            executeUpdate(connection, "delete from scan_hint_t where id = 256");
            executeUpdate(connection,
                    "insert into scan_hint_t values (256, 'replacement')");
            connection.commit();
            assertRows(connection,
                    "select payload from scan_hint_t where id = 256",
                    "replacement");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testPersistedHintsMatchPhysicalRecordsAndSurviveReopen() throws Exception {
        String database = uniqueDatabaseName("mvcc-raw-store-lookup-hints");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table lookup_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into lookup_t values (1, 'one')");
                executeUpdate(connection, "insert into lookup_t values (2, 'two')");
                connection.commit();

                executeUpdate(connection, "update lookup_t set name = 'one-v2' where id = 1");
                executeUpdate(connection, "update lookup_t set name = 'one-v3' where id = 1");
                executeUpdate(connection, "delete from lookup_t where id = 2");
                connection.commit();

                assertRows(connection,
                        "select id, name from lookup_t order by id",
                        "1|one-v3");
                assertHintTopology(connection, "LOOKUP_T");
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, name from lookup_t order by id",
                        "1|one-v3");
                assertHintTopology(reopened, "LOOKUP_T");
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testStaleHintsFallBackForCurrentAndHistoricalSnapshots() throws Exception {
        String database = uniqueDatabaseName("mvcc-raw-store-stale-lookup-hints");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table hint_anchor (id int) using delos_mvcc");
                executeUpdate(setup,
                        "create table hint_data (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into hint_anchor values (1)");
                executeUpdate(setup, "insert into hint_data values (1, 'old')");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertRows(historical, "select id from hint_anchor", "1");

                executeUpdate(writer, "update hint_data set name = 'new' where id = 1");
                writer.commit();

                try (Connection corrupter = openDatabase(database, false)) {
                    corrupter.setAutoCommit(false);
                    MvccRawStoreMetadataInspection.invalidateLookupHints(
                            corrupter,
                            "HINT_DATA",
                            1L);
                    corrupter.commit();
                }

                assertRows(historical, "select id, name from hint_data", "1|old");
                historical.commit();
                try (Connection current = openDatabase(database, false)) {
                    assertRows(current, "select id, name from hint_data", "1|new");
                    current.commit();
                }

                executeUpdate(writer, "update hint_data set name = 'newest' where id = 1");
                writer.commit();
                assertRows(writer, "select id, name from hint_data", "1|newest");
                assertCurrentHeadAndNewestVersionHaveHints(writer, "HINT_DATA");
                writer.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testRowsWithoutOptionalHintsRemainReadableAndUpgradeOnMutation() throws Exception {
        String database = uniqueDatabaseName("mvcc-raw-store-legacy-lookup-hints");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table legacy_hint_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into legacy_hint_t values (1, 'one')");
                connection.commit();
                executeUpdate(connection,
                        "update legacy_hint_t set name = 'one-v2' where id = 1");
                connection.commit();

                MvccRawStoreMetadataInspection.stripLookupHints(connection, "LEGACY_HINT_T");
                connection.commit();
                assertNoHintFields(connection, "LEGACY_HINT_T");
                assertRows(connection, "select id, name from legacy_hint_t", "1|one-v2");
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertRows(reopened, "select id, name from legacy_hint_t", "1|one-v2");
                executeUpdate(reopened,
                        "update legacy_hint_t set name = 'one-v3' where id = 1");
                reopened.commit();
                assertRows(reopened, "select id, name from legacy_hint_t", "1|one-v3");
                assertCurrentHeadAndNewestVersionHaveHints(reopened, "LEGACY_HINT_T");
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testCrashRecoveryAndMemoryUseTheSameHintRules() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);

        String database = "mvcc_raw_store_lookup_hint_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_hint_t (id int, name varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into memory_hint_t values (1, 'old')");
            connection.commit();
            executeUpdate(connection,
                    "update memory_hint_t set name = 'new' where id = 1");
            connection.commit();
            assertHintTopology(connection, "MEMORY_HINT_T");

            MvccRawStoreMetadataInspection.invalidateLookupHints(
                    connection,
                    "MEMORY_HINT_T",
                    1L);
            connection.commit();
            assertRows(connection, "select id, name from memory_hint_t", "1|new");
            connection.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void assertHintTopology(Connection connection, String tableName) throws Exception {
        List<MvccRawStoreMetadataInspection.VersionIdentity> versions =
                MvccRawStoreMetadataInspection.versions(connection, tableName);
        Map<Long, MvccRawStoreMetadataInspection.VersionIdentity> byId = new HashMap<>();
        for (MvccRawStoreMetadataInspection.VersionIdentity version : versions) {
            byId.put(version.versionId(), version);
            assertTrue("new version rows must carry optional hint fields",
                    version.hasPreviousHintFields());
        }
        for (MvccRawStoreMetadataInspection.VersionIdentity version : versions) {
            if (version.previousVersionId() == 0L) {
                assertEquals(0L, version.previousHintPage());
                assertEquals(0, version.previousHintRecord());
            } else {
                MvccRawStoreMetadataInspection.VersionIdentity predecessor =
                        byId.get(version.previousVersionId());
                assertNotNull("missing predecessor " + version.previousVersionId(), predecessor);
                assertEquals(predecessor.physicalPage(), version.previousHintPage());
                assertEquals(predecessor.physicalRecord(), version.previousHintRecord());
            }
        }

        for (MvccRawStoreMetadataInspection.DirectoryIdentity directory :
                MvccRawStoreMetadataInspection.directories(connection, tableName)) {
            assertTrue("new directory rows must carry a head hint", directory.hasHint());
            MvccRawStoreMetadataInspection.VersionIdentity head = byId.get(directory.headVersionId());
            assertNotNull("missing directory head " + directory.headVersionId(), head);
            assertEquals(head.physicalPage(), directory.headHintPage());
            assertEquals(head.physicalRecord(), directory.headHintRecord());
        }
    }

    private static void assertNoHintFields(Connection connection, String tableName) throws Exception {
        for (MvccRawStoreMetadataInspection.DirectoryIdentity directory :
                MvccRawStoreMetadataInspection.directories(connection, tableName)) {
            assertFalse(directory.hasHint());
        }
        for (MvccRawStoreMetadataInspection.VersionIdentity version :
                MvccRawStoreMetadataInspection.versions(connection, tableName)) {
            assertFalse(version.hasPreviousHintFields());
        }
    }

    private static void assertCurrentHeadAndNewestVersionHaveHints(
            Connection connection,
            String tableName) throws Exception {
        List<MvccRawStoreMetadataInspection.VersionIdentity> versions =
                MvccRawStoreMetadataInspection.versions(connection, tableName);
        MvccRawStoreMetadataInspection.VersionIdentity newest = versions.get(versions.size() - 1);
        assertTrue(newest.hasPreviousHintFields());
        if (newest.previousVersionId() != 0L) {
            MvccRawStoreMetadataInspection.VersionIdentity predecessor = versions.stream()
                    .filter(version -> version.versionId() == newest.previousVersionId())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "missing predecessor " + newest.previousVersionId()));
            assertEquals(predecessor.physicalPage(), newest.previousHintPage());
            assertEquals(predecessor.physicalRecord(), newest.previousHintRecord());
        }
        MvccRawStoreMetadataInspection.DirectoryIdentity directory =
                MvccRawStoreMetadataInspection.directories(connection, tableName).get(0);
        assertTrue(directory.hasHint());
        assertEquals(newest.versionId(), directory.headVersionId());
        assertEquals(newest.physicalPage(), directory.headHintPage());
        assertEquals(newest.physicalRecord(), directory.headHintRecord());
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-lookup-hint-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_hint_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into crash_hint_t values (1, 'old')");
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
            fail("RawStore MVCC lookup-hint crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            assertRows(recovered,
                    "select id, name from crash_hint_t",
                    expectCommitted ? "1|new" : "1|old");
            assertHintTopology(recovered, "CRASH_HINT_T");
            recovered.commit();
        }
        shutdownDatabase(database);
    }

    private static void shutdownMemoryDatabase(String databaseName) throws Exception {
        try {
            DriverManager.getConnection(
                    "jdbc:derby:memory:" + databaseName + ";shutdown=true");
            fail("Memory database shutdown should throw");
        } catch (java.sql.SQLException expected) {
            assertEquals("08006", expected.getSQLState());
        }
    }

    private static String uniqueDatabaseName(String prefix) {
        return databaseName(prefix + '-' + Long.toUnsignedString(System.nanoTime()));
    }

    private static String javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe"
                        : "java")
                .toString();
    }

    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            try (Connection connection = openDatabase(args[0], false)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "update crash_hint_t set name = 'new' where id = 1");
                connection.commit();
            }
            throw new AssertionError("Configured RawStore MVCC failure point did not halt the JVM");
        }
    }
}
