/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreUpdateDeleteTest

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
import java.sql.Savepoint;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** RawStore version-chain, UPDATE, DELETE, savepoint, recovery, and memory proofs. */
public final class MvccRawStoreUpdateDeleteTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final long CURRENT_END_SEQUENCE = Long.MAX_VALUE;

    public void testUpdateDeleteCommitRollbackSavepointAndReopen() throws Exception {
        String database = databaseName("mvcc-raw-store-update-delete-file");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table mutation_t (id int, name varchar(64), note varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into mutation_t values (1, 'one', 'base')");
                executeUpdate(connection, "insert into mutation_t values (2, 'two', 'base')");
                executeUpdate(connection, "insert into mutation_t values (3, 'three', 'base')");
                connection.commit();

                assertEquals(1, executeUpdate(connection,
                        "update mutation_t set name = 'rollback', note = 'changed' where id = 1"));
                assertRows(connection,
                        "select id, name, note from mutation_t where id = 1",
                        "1|rollback|changed");
                connection.rollback();
                assertRows(connection,
                        "select id, name, note from mutation_t where id = 1",
                        "1|one|base");
                connection.commit();

                assertEquals(1, executeUpdate(connection,
                        "update mutation_t set name = 'first' where id = 1"));
                assertEquals(1, executeUpdate(connection,
                        "update mutation_t set name = 'second', note = 'committed' where id = 1"));
                assertEquals(1, executeUpdate(connection,
                        "delete from mutation_t where id = 2"));
                assertRows(connection,
                        "select id, name, note from mutation_t order by id",
                        "1|second|committed",
                        "3|three|base");

                Savepoint savepoint = connection.setSavepoint("before_rolled_back_mutations");
                assertEquals(1, executeUpdate(connection,
                        "update mutation_t set name = 'savepoint' where id = 3"));
                assertEquals(1, executeUpdate(connection,
                        "delete from mutation_t where id = 1"));
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                assertRows(connection,
                        "select id, name, note from mutation_t order by id",
                        "1|second|committed",
                        "3|three|base");
                connection.commit();

                assertCommittedChains(connection);
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, name, note from mutation_t order by id",
                        "1|second|committed",
                        "3|three|base");
                assertCommittedChains(reopened);
            }
            shutdownDatabase(database);
        }
    }

    public void testHistoricalSnapshotTraversesReplacementAndTombstoneChains() throws Exception {
        String database = databaseName("mvcc-raw-store-update-delete-snapshot");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table snapshot_anchor (id int) using delos_mvcc");
                executeUpdate(setup,
                        "create table snapshot_data (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into snapshot_anchor values (1)");
                executeUpdate(setup, "insert into snapshot_data values (1, 'old')");
                executeUpdate(setup, "insert into snapshot_data values (2, 'delete-old')");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection staleWriter = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                staleWriter.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                historical.setAutoCommit(false);
                staleWriter.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertRows(historical, "select id from snapshot_anchor", "1");
                assertRows(staleWriter, "select id from snapshot_anchor", "1");

                executeUpdate(writer, "update snapshot_data set name = 'new' where id = 1");
                executeUpdate(writer, "delete from snapshot_data where id = 2");
                writer.commit();

                assertRows(historical,
                        "select id, name from snapshot_data order by id",
                        "1|old",
                        "2|delete-old");

                try {
                    executeUpdate(staleWriter,
                            "update snapshot_data set name = 'stale-overwrite' where id = 1");
                    fail("A stale snapshot must not overwrite a newer RawStore MVCC head");
                } catch (SQLException expected) {
                    assertEquals("40001", expected.getSQLState());
                }
                rollbackAfterExpectedConflict(staleWriter);

                historical.commit();
                assertRows(historical,
                        "select id, name from snapshot_data order by id",
                        "1|new");
                historical.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testUpdateDeleteSurviveBothRawStoreCrashBoundaries() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);
    }

    public void testMemoryAndMultiTableUpdateDeleteShareOneOutcome() throws Exception {
        String database = "mvcc_raw_store_update_delete_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_a (id int, name varchar(64)) using delos_mvcc");
            executeUpdate(connection,
                    "create table memory_b (id int, name varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into memory_a values (1, 'a-old')");
            executeUpdate(connection, "insert into memory_b values (2, 'b-old')");
            connection.commit();

            executeUpdate(connection, "update memory_a set name = 'a-rollback' where id = 1");
            executeUpdate(connection, "delete from memory_b where id = 2");
            connection.rollback();
            assertRows(connection, "select id, name from memory_a", "1|a-old");
            assertRows(connection, "select id, name from memory_b", "2|b-old");
            connection.commit();

            executeUpdate(connection, "update memory_a set name = 'a-new' where id = 1");
            executeUpdate(connection, "delete from memory_b where id = 2");
            connection.commit();
            assertRows(connection, "select id, name from memory_a", "1|a-new");
            assertRows(connection, "select id, name from memory_b");

            MvccRawStoreMetadataInspection.Counters counters =
                    MvccRawStoreMetadataInspection.counters(connection);
            assertEquals(4L, counters.nextTransactionId());
            assertEquals(65L, counters.nextCommitSequence());
            assertEquals(2L, counters.committedHighWater());
            connection.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void assertCommittedChains(Connection connection) throws Exception {
        List<MvccRawStoreMetadataInspection.VersionIdentity> versions =
                MvccRawStoreMetadataInspection.versions(connection, "MUTATION_T");
        assertEquals(6, versions.size());

        MvccRawStoreMetadataInspection.VersionIdentity rowOneBase = versions.get(0);
        MvccRawStoreMetadataInspection.VersionIdentity rowTwoBase = versions.get(1);
        MvccRawStoreMetadataInspection.VersionIdentity rowThreeBase = versions.get(2);
        MvccRawStoreMetadataInspection.VersionIdentity rowOneFirst = versions.get(3);
        MvccRawStoreMetadataInspection.VersionIdentity rowOneSecond = versions.get(4);
        MvccRawStoreMetadataInspection.VersionIdentity rowTwoDelete = versions.get(5);

        assertVersion(rowOneBase, 1L, 1L, 1L, 2L, 0L, false);
        assertVersion(rowTwoBase, 2L, 1L, 1L, 2L, 0L, false);
        assertVersion(rowThreeBase, 3L, 1L, 1L, CURRENT_END_SEQUENCE, 0L, false);
        assertVersion(rowOneFirst, 1L, 3L, 2L, 2L, rowOneBase.versionId(), false);
        assertVersion(rowOneSecond, 1L, 3L, 2L, CURRENT_END_SEQUENCE,
                rowOneFirst.versionId(), false);
        assertVersion(rowTwoDelete, 2L, 3L, 2L, CURRENT_END_SEQUENCE,
                rowTwoBase.versionId(), true);

        for (int index = 1; index < versions.size(); index++) {
            assertTrue("version IDs must remain monotonic even when rollback leaves gaps",
                    versions.get(index - 1).versionId() < versions.get(index).versionId());
        }
    }

    private static void assertVersion(
            MvccRawStoreMetadataInspection.VersionIdentity actual,
            long rowId,
            long transactionId,
            long begin,
            long end,
            long previous,
            boolean tombstone) {
        assertEquals("row ID", rowId, actual.rowId());
        assertEquals("creator transaction ID", transactionId, actual.creatorTransactionId());
        assertEquals("begin sequence", begin, actual.beginCommitSequence());
        assertEquals("end sequence", end, actual.endCommitSequence());
        assertEquals("previous version ID", previous, actual.previousVersionId());
        assertEquals("tombstone", tombstone, actual.tombstone());
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-update-delete-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_mutation_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(setup, "insert into crash_mutation_t values (1, 'old')");
                executeUpdate(setup, "insert into crash_mutation_t values (2, 'delete-old')");
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
            fail("RawStore MVCC UPDATE/DELETE crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            if (expectCommitted) {
                assertRows(recovered,
                        "select id, name from crash_mutation_t order by id",
                        "1|new");
            } else {
                assertRows(recovered,
                        "select id, name from crash_mutation_t order by id",
                        "1|old",
                        "2|delete-old");
            }
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
                        "update crash_mutation_t set name = 'new' where id = 1");
                executeUpdate(connection, "delete from crash_mutation_t where id = 2");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
