/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreVacuumTest

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Transactional RawStore MVCC history reclamation and recovery proofs. */
public final class MvccRawStoreVacuumTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String BEFORE_RAW_COMMIT =
            "after-vacuum-before-raw-commit";
    private static final String AFTER_RAW_COMMIT =
            "after-vacuum-raw-commit-before-publication";

    public void testOldestRetainedSnapshotProtectsHistoryAndVacuumRelinksTheChain()
            throws Exception {
        String database = databaseName("mvcc-raw-store-vacuum-horizon");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table vacuum_horizon_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into vacuum_horizon_t values (1, 10)");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_horizon_t set value = 20 where id = 1");
                setup.commit();
            }

            try (Connection retainedReader = openDatabase(database, false);
                 Connection writer = openDatabase(database, false);
                 Connection vacuum = openDatabase(database, false)) {
                retainedReader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                retainedReader.setAutoCommit(false);
                writer.setAutoCommit(false);
                vacuum.setAutoCommit(false);

                // The reader pins the second committed version as the oldest live snapshot.
                assertRows(retainedReader,
                        "select value from vacuum_horizon_t where id = 1",
                        "20");

                executeUpdate(writer,
                        "update vacuum_horizon_t set value = 30 where id = 1");
                writer.commit();
                executeUpdate(writer,
                        "update vacuum_horizon_t set value = 40 where id = 1");
                writer.commit();

                List<MvccRawStoreMetadataInspection.VersionIdentity> before =
                        MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HORIZON_T");
                assertEquals(4, before.size());
                long protectedVersionId = before.get(1).versionId();

                inPlaceCompressTable(vacuum, "VACUUM_HORIZON_T");
                vacuum.commit();

                assertRows(retainedReader,
                        "select value from vacuum_horizon_t where id = 1",
                        "20");
                assertChain(
                        MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HORIZON_T"),
                        protectedVersionId,
                        before.get(2).versionId(),
                        before.get(3).versionId());
                assertDirectoryHead(
                        vacuum,
                        "VACUUM_HORIZON_T",
                        before.get(3).versionId());

                retainedReader.commit();
                inPlaceCompressTable(vacuum, "VACUUM_HORIZON_T");
                vacuum.commit();

                assertChain(
                        MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HORIZON_T"),
                        before.get(3).versionId());
                assertDirectoryHead(
                        vacuum,
                        "VACUUM_HORIZON_T",
                        before.get(3).versionId());
                assertRows(vacuum,
                        "select value from vacuum_horizon_t where id = 1",
                        "40");
                vacuum.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testHeldCursorRetainsItsSnapshotLeaseAcrossCommitAndVacuum()
            throws Exception {
        String database = databaseName("mvcc-raw-store-vacuum-held-cursor");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table vacuum_held_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into vacuum_held_t values (1, 10)");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_held_t set value = 20 where id = 1");
                setup.commit();
            }

            try (Connection heldReader = openDatabase(database, false);
                 Connection writer = openDatabase(database, false);
                 Connection vacuum = openDatabase(database, false)) {
                heldReader.setAutoCommit(false);
                heldReader.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
                writer.setAutoCommit(false);
                vacuum.setAutoCommit(false);

                try (Statement heldStatement = heldReader.createStatement(
                             ResultSet.TYPE_FORWARD_ONLY,
                             ResultSet.CONCUR_READ_ONLY,
                             ResultSet.HOLD_CURSORS_OVER_COMMIT);
                     ResultSet held = heldStatement.executeQuery(
                             "select value from vacuum_held_t where id = 1")) {
                    // Commit releases the transaction lease, but the held scan keeps its duplicate.
                    heldReader.commit();

                    executeUpdate(writer,
                            "update vacuum_held_t set value = 30 where id = 1");
                    writer.commit();
                    executeUpdate(writer,
                            "update vacuum_held_t set value = 40 where id = 1");
                    writer.commit();

                    List<MvccRawStoreMetadataInspection.VersionIdentity> before =
                            MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HELD_T");
                    assertEquals(4, before.size());
                    inPlaceCompressTable(vacuum, "VACUUM_HELD_T");
                    vacuum.commit();

                    assertChain(
                            MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HELD_T"),
                            before.get(1).versionId(),
                            before.get(2).versionId(),
                            before.get(3).versionId());
                    assertTrue(held.next());
                    assertEquals(20, held.getInt(1));
                    assertFalse(held.next());
                }

                // Closing the held scan releases the last lease for version 20.
                inPlaceCompressTable(vacuum, "VACUUM_HELD_T");
                vacuum.commit();
                List<MvccRawStoreMetadataInspection.VersionIdentity> after =
                        MvccRawStoreMetadataInspection.versions(vacuum, "VACUUM_HELD_T");
                assertEquals(1, after.size());
                assertChain(after, after.get(0).versionId());
                assertRows(vacuum,
                        "select value from vacuum_held_t where id = 1",
                        "40");
                vacuum.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testVacuumRepairsStaleHintsWithoutReplacingOrderedIndex()
            throws Exception {
        String database = databaseName("mvcc-raw-store-vacuum-hint-repair");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table vacuum_hint_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(connection, "insert into vacuum_hint_t values (1, 10)");
                connection.commit();

                long publishedIndex =
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                connection,
                                "VACUUM_HINT_T");
                MvccRawStoreMetadataInspection.invalidateLookupHints(
                        connection,
                        "VACUUM_HINT_T",
                        1L);
                connection.commit();

                List<MvccRawStoreMetadataInspection.VersionIdentity> before =
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_HINT_T");
                assertEquals(1, before.size());
                long versionId = before.get(0).versionId();
                inPlaceCompressTable(connection, "VACUUM_HINT_T");
                connection.commit();

                List<MvccRawStoreMetadataInspection.VersionIdentity> after =
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_HINT_T");
                assertChain(after, versionId);
                assertDirectoryHead(connection, "VACUUM_HINT_T", versionId);
                assertEquals(publishedIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                connection,
                                "VACUUM_HINT_T"));
                assertRows(connection,
                        "select value from vacuum_hint_t where id = 1",
                        "10");
                connection.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testDeletedRowsArePurgedAndStableIdentitiesAreNeverReused()
            throws Exception {
        String database = databaseName("mvcc-raw-store-vacuum-identities");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            long maximumRowId;
            long maximumVersionId;
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table vacuum_identity_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(connection,
                        "insert into vacuum_identity_t values (1, 10), (2, 20)");
                connection.commit();
                executeUpdate(connection,
                        "update vacuum_identity_t set value = 11 where id = 1");
                connection.commit();
                executeUpdate(connection,
                        "delete from vacuum_identity_t where id = 2");
                connection.commit();

                List<MvccRawStoreMetadataInspection.DirectoryIdentity> directories =
                        MvccRawStoreMetadataInspection.directories(
                                connection,
                                "VACUUM_IDENTITY_T");
                List<MvccRawStoreMetadataInspection.VersionIdentity> versions =
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_IDENTITY_T");
                maximumRowId = directories.stream()
                        .mapToLong(MvccRawStoreMetadataInspection.DirectoryIdentity::rowId)
                        .max()
                        .orElseThrow();
                maximumVersionId = versions.stream()
                        .mapToLong(MvccRawStoreMetadataInspection.VersionIdentity::versionId)
                        .max()
                        .orElseThrow();
                assertEquals(4, versions.size());

                inPlaceCompressTable(connection, "VACUUM_IDENTITY_T");
                connection.commit();

                List<MvccRawStoreMetadataInspection.DirectoryIdentity> afterDirectories =
                        MvccRawStoreMetadataInspection.directories(
                                connection,
                                "VACUUM_IDENTITY_T");
                List<MvccRawStoreMetadataInspection.VersionIdentity> afterVersions =
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_IDENTITY_T");
                assertEquals(1, afterDirectories.size());
                assertEquals(1, afterVersions.size());
                assertEquals(afterDirectories.get(0).rowId(), afterVersions.get(0).rowId());
                assertEquals(0L, afterVersions.get(0).previousVersionId());
                assertRows(connection,
                        "select id, value from vacuum_identity_t order by id",
                        "1|11");

                executeUpdate(connection,
                        "insert into vacuum_identity_t values (3, 30)");
                connection.commit();
                List<MvccRawStoreMetadataInspection.DirectoryIdentity> withNewRow =
                        MvccRawStoreMetadataInspection.directories(
                                connection,
                                "VACUUM_IDENTITY_T");
                List<MvccRawStoreMetadataInspection.VersionIdentity> withNewVersion =
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_IDENTITY_T");
                assertTrue("vacuumed logical row identities must never be reused",
                        withNewRow.stream().mapToLong(
                                MvccRawStoreMetadataInspection.DirectoryIdentity::rowId)
                                .max().orElseThrow() > maximumRowId);
                assertTrue("vacuumed version identities must never be reused",
                        withNewVersion.stream().mapToLong(
                                MvccRawStoreMetadataInspection.VersionIdentity::versionId)
                                .max().orElseThrow() > maximumVersionId);
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, value from vacuum_identity_t order by id",
                        "1|11",
                        "3|30");
                assertEquals(2,
                        MvccRawStoreMetadataInspection.directories(
                                reopened,
                                "VACUUM_IDENTITY_T").size());
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testVacuumRollbackAndSavepointRestoreOneRawStoreOutcome()
            throws Exception {
        String database = databaseName("mvcc-raw-store-vacuum-rollback");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            long publishedIndex;
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table vacuum_rollback_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into vacuum_rollback_t values (1, 10)");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_rollback_t set value = 20 where id = 1");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_rollback_t set value = 30 where id = 1");
                setup.commit();
                publishedIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        setup,
                        "VACUUM_ROLLBACK_T");
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                setup,
                                "VACUUM_ROLLBACK_T").size());
                setup.commit();
            }

            try (Connection connection = openDatabase(database, false)) {
                connection.setAutoCommit(false);
                Savepoint beforeVacuum = connection.setSavepoint("before_vacuum");
                inPlaceCompressTable(connection, "VACUUM_ROLLBACK_T");
                assertEquals(1,
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_ROLLBACK_T").size());
                connection.rollback(beforeVacuum);
                connection.releaseSavepoint(beforeVacuum);
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_ROLLBACK_T").size());
                connection.commit();
            }
            try (Connection observer = openDatabase(database, false)) {
                assertEquals(publishedIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "VACUUM_ROLLBACK_T"));
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                observer,
                                "VACUUM_ROLLBACK_T").size());
                observer.commit();
            }

            try (Connection connection = openDatabase(database, false)) {
                connection.setAutoCommit(false);
                inPlaceCompressTable(connection, "VACUUM_ROLLBACK_T");
                assertEquals(1,
                        MvccRawStoreMetadataInspection.versions(
                                connection,
                                "VACUUM_ROLLBACK_T").size());
                connection.rollback();
            }
            try (Connection observer = openDatabase(database, false)) {
                assertRows(observer,
                        "select value from vacuum_rollback_t where id = 1",
                        "30");
                assertEquals(publishedIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "VACUUM_ROLLBACK_T"));
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                observer,
                                "VACUUM_ROLLBACK_T").size());
                observer.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testVacuumRecoversOnBothSidesOfTheRawStoreCommitRecord()
            throws Exception {
        verifyCrashBoundary(BEFORE_RAW_COMMIT, 93, false);
        verifyCrashBoundary(AFTER_RAW_COMMIT, 94, true);
    }

    public void testMemoryDatabaseUsesTheSameTransactionalVacuumPath()
            throws Exception {
        String database = "mvcc_raw_store_vacuum_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_vacuum_t ("
                            + "id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into memory_vacuum_t values (1, 10), (2, 20)");
            connection.commit();
            executeUpdate(connection,
                    "update memory_vacuum_t set value = 11 where id = 1");
            connection.commit();
            executeUpdate(connection,
                    "delete from memory_vacuum_t where id = 2");
            connection.commit();
            assertEquals(4,
                    MvccRawStoreMetadataInspection.versions(
                            connection,
                            "MEMORY_VACUUM_T").size());

            inPlaceCompressTable(connection, "MEMORY_VACUUM_T");
            connection.commit();
            assertRows(connection,
                    "select id, value from memory_vacuum_t order by id",
                    "1|11");
            assertEquals(1,
                    MvccRawStoreMetadataInspection.versions(
                            connection,
                            "MEMORY_VACUUM_T").size());
            assertEquals(1,
                    MvccRawStoreMetadataInspection.directories(
                            connection,
                            "MEMORY_VACUUM_T").size());
            connection.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void assertChain(
            List<MvccRawStoreMetadataInspection.VersionIdentity> versions,
            long... expectedVersionIds) {
        assertEquals(expectedVersionIds.length, versions.size());
        Map<Long, MvccRawStoreMetadataInspection.VersionIdentity> byId = new HashMap<>();
        for (MvccRawStoreMetadataInspection.VersionIdentity version : versions) {
            byId.put(version.versionId(), version);
        }
        for (int index = 0; index < expectedVersionIds.length; index++) {
            long expectedId = expectedVersionIds[index];
            MvccRawStoreMetadataInspection.VersionIdentity version = byId.get(expectedId);
            assertNotNull("missing retained RawStore MVCC version " + expectedId, version);
            long expectedPredecessor = index == 0 ? 0L : expectedVersionIds[index - 1];
            assertEquals("incorrect logical predecessor for version " + expectedId,
                    expectedPredecessor,
                    version.previousVersionId());
            if (version.hasPreviousHintFields()) {
                if (expectedPredecessor == 0L) {
                    assertEquals(0L, version.previousHintPage());
                    assertEquals(0, version.previousHintRecord());
                } else {
                    MvccRawStoreMetadataInspection.VersionIdentity predecessor =
                            byId.get(expectedPredecessor);
                    assertEquals(predecessor.physicalPage(), version.previousHintPage());
                    assertEquals(predecessor.physicalRecord(), version.previousHintRecord());
                }
            }
        }
    }

    private static void assertDirectoryHead(
            Connection connection,
            String tableName,
            long expectedHeadVersionId) throws Exception {
        List<MvccRawStoreMetadataInspection.DirectoryIdentity> directories =
                MvccRawStoreMetadataInspection.directories(connection, tableName);
        assertEquals(1, directories.size());
        MvccRawStoreMetadataInspection.DirectoryIdentity directory = directories.get(0);
        assertEquals(expectedHeadVersionId, directory.headVersionId());
        if (directory.hasHint()) {
            MvccRawStoreMetadataInspection.VersionIdentity head =
                    MvccRawStoreMetadataInspection.versions(connection, tableName).stream()
                            .filter(version -> version.versionId() == expectedHeadVersionId)
                            .findFirst()
                            .orElseThrow();
            assertEquals(head.physicalPage(), directory.headHintPage());
            assertEquals(head.physicalRecord(), directory.headHintRecord());
        }
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-vacuum-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        long originalIndex;
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table vacuum_crash_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into vacuum_crash_t values (1, 10)");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_crash_t set value = 20 where id = 1");
                setup.commit();
                executeUpdate(setup,
                        "update vacuum_crash_t set value = 30 where id = 1");
                setup.commit();
                originalIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        setup,
                        "VACUUM_CRASH_T");
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                setup,
                                "VACUUM_CRASH_T").size());
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
            fail("RawStore MVCC vacuum crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output,
                expectedStatus,
                process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            assertRows(recovered,
                    "select value from vacuum_crash_t where id = 1",
                    "30");
            long recoveredIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                    recovered,
                    "VACUUM_CRASH_T");
            if (expectCommitted) {
                assertEquals(1,
                        MvccRawStoreMetadataInspection.versions(
                                recovered,
                                "VACUUM_CRASH_T").size());
                assertTrue(recoveredIndex != originalIndex);
                assertFalse(MvccRawStoreMetadataInspection.containerExists(
                        recovered,
                        originalIndex));
                assertTrue(MvccRawStoreMetadataInspection.containerExists(
                        recovered,
                        recoveredIndex));
            } else {
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                recovered,
                                "VACUUM_CRASH_T").size());
                assertEquals(originalIndex, recoveredIndex);
                assertTrue(MvccRawStoreMetadataInspection.containerExists(
                        recovered,
                        originalIndex));
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

    /** Child JVM halts on either side of the inherited RawStore vacuum commit. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                inPlaceCompressTable(connection, "VACUUM_CRASH_T");
                connection.commit();
            }
            throw new AssertionError("Vacuum crash failure point did not halt the child JVM");
        }
    }
}
