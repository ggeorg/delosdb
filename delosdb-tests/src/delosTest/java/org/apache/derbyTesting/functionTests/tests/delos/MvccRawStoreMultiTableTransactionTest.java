/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreMultiTableTransactionTest

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One RawStore outcome and one MVCC snapshot across multiple RawStore-backed tables. */
public final class MvccRawStoreMultiTableTransactionTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String RESERVATION_BLOCK_PROPERTY =
            "delosdb.mvcc.rawStoreIdentityReservationBlockSize";

    public void testTwoTableCommitRollbackAndSavepointUseOneRawStoreOutcome() throws Exception {
        String database = databaseName("mvcc-raw-store-multi-table-outcome");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope reservation =
                     setSystemProperty(RESERVATION_BLOCK_PROPERTY, "1")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                createTables(connection, "multi_a", "multi_b");

                executeUpdate(connection, "insert into multi_a values (1, 'a-commit')");
                executeUpdate(connection, "insert into multi_b values (2, 'b-commit')");
                assertRows(connection, "select id, name from multi_a", "1|a-commit");
                assertRows(connection, "select id, name from multi_b", "2|b-commit");
                connection.commit();

                assertCounters(connection, 2L, 2L, 1L);
                assertVersionIdentities(connection, "MULTI_A", new long[][] {{1L, 1L}});
                assertVersionIdentities(connection, "MULTI_B", new long[][] {{1L, 1L}});
                connection.commit();

                executeUpdate(connection, "insert into multi_a values (3, 'a-rollback')");
                executeUpdate(connection, "insert into multi_b values (4, 'b-rollback')");
                connection.rollback();
                assertRows(connection, "select id from multi_a where id = 3");
                assertRows(connection, "select id from multi_b where id = 4");
                assertCounters(connection, 3L, 2L, 1L);
                connection.commit();

                executeUpdate(connection, "insert into multi_a values (5, 'a-savepoint')");
                Savepoint savepoint = connection.setSavepoint("before_second_table");
                executeUpdate(connection, "insert into multi_b values (6, 'b-savepoint-rollback')");
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                connection.commit();

                assertRows(connection,
                        "select id, name from multi_a order by id",
                        "1|a-commit",
                        "5|a-savepoint");
                assertRows(connection,
                        "select id, name from multi_b order by id",
                        "2|b-commit");
                assertCounters(connection, 4L, 3L, 2L);
                assertVersionIdentities(
                        connection,
                        "MULTI_A",
                        new long[][] {{1L, 1L}, {3L, 2L}});
                assertVersionIdentities(
                        connection,
                        "MULTI_B",
                        new long[][] {{1L, 1L}});
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, name from multi_a order by id",
                        "1|a-commit",
                        "5|a-savepoint");
                assertRows(reopened,
                        "select id, name from multi_b order by id",
                        "2|b-commit");
            }
            shutdownDatabase(database);
        }
    }

    public void testTransactionSnapshotIsStableAcrossRawStoreTables() throws Exception {
        String database = databaseName("mvcc-raw-store-multi-table-snapshot");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope reservation =
                     setSystemProperty(RESERVATION_BLOCK_PROPERTY, "1")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                createTables(setup, "snapshot_a", "snapshot_b");
                executeUpdate(setup, "insert into snapshot_a values (1, 'baseline')");
                setup.commit();
            }

            try (Connection reader = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                reader.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertRows(reader, "select id from snapshot_a", "1");
                executeUpdate(writer, "insert into snapshot_b values (2, 'after-snapshot')");
                writer.commit();

                assertRows(reader, "select id from snapshot_b where id = 2");
                reader.commit();
                assertRows(reader, "select id from snapshot_b where id = 2", "2");
                reader.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testTwoTableCommitSurvivesBothRawStoreCrashBoundaries() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);
    }

    public void testMemoryDatabaseSupportsTwoTableTransactions() throws Exception {
        String database = "mvcc_raw_store_multi_table_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope reservation =
                     setSystemProperty(RESERVATION_BLOCK_PROPERTY, "1");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            createTables(connection, "memory_a", "memory_b");

            executeUpdate(connection, "insert into memory_a values (1, 'a')");
            executeUpdate(connection, "insert into memory_b values (2, 'b')");
            connection.commit();
            assertRows(connection, "select id, name from memory_a", "1|a");
            assertRows(connection, "select id, name from memory_b", "2|b");

            executeUpdate(connection, "insert into memory_a values (3, 'rollback-a')");
            executeUpdate(connection, "insert into memory_b values (4, 'rollback-b')");
            connection.rollback();
            assertRows(connection, "select id from memory_a where id = 3");
            assertRows(connection, "select id from memory_b where id = 4");
            assertCounters(connection, 3L, 2L, 1L);
            connection.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-multi-table-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope reservation =
                     setSystemProperty(RESERVATION_BLOCK_PROPERTY, "1")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                createTables(setup, "crash_a", "crash_b");
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + '=' + failurePoint,
                "-D" + RESERVATION_BLOCK_PROPERTY + "=1",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore MVCC multi-table crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             SystemPropertyScope reservation =
                     setSystemProperty(RESERVATION_BLOCK_PROPERTY, "1")) {
            try (Connection recovered = openDatabase(database, false)) {
                recovered.setAutoCommit(false);
                if (expectCommitted) {
                    assertRows(recovered, "select id, name from crash_a", "1|a");
                    assertRows(recovered, "select id, name from crash_b", "2|b");
                    assertCounters(recovered, 2L, 2L, 1L);
                    assertVersionIdentities(recovered, "CRASH_A", new long[][] {{1L, 1L}});
                    assertVersionIdentities(recovered, "CRASH_B", new long[][] {{1L, 1L}});
                } else {
                    assertRows(recovered, "select id from crash_a");
                    assertRows(recovered, "select id from crash_b");
                    assertCounters(recovered, 2L, 2L, 0L);
                    assertVersionIdentities(recovered, "CRASH_A", new long[0][0]);
                    assertVersionIdentities(recovered, "CRASH_B", new long[0][0]);
                }
                recovered.commit();
            }
            shutdownDatabase(database);
        }
    }

    private static void createTables(Connection connection, String first, String second)
            throws Exception {
        executeUpdate(connection,
                "create table " + first + " (id int, name varchar(64)) using delos_mvcc");
        connection.commit();
        executeUpdate(connection,
                "create table " + second + " (id int, name varchar(64)) using delos_mvcc");
        connection.commit();
    }

    private static void assertCounters(
            Connection connection,
            long nextTransactionId,
            long nextCommitSequence,
            long committedHighWater) throws Exception {
        MvccRawStoreMetadataInspection.Counters counters =
                MvccRawStoreMetadataInspection.counters(connection);
        assertEquals("next transaction ID", nextTransactionId, counters.nextTransactionId());
        assertEquals("next commit sequence", nextCommitSequence, counters.nextCommitSequence());
        assertEquals("committed high-water", committedHighWater, counters.committedHighWater());
    }

    private static void assertVersionIdentities(
            Connection connection,
            String table,
            long[][] expected) throws Exception {
        List<MvccRawStoreMetadataInspection.VersionIdentity> versions =
                MvccRawStoreMetadataInspection.versions(connection, table);
        assertEquals("version count for " + table, expected.length, versions.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                    "creator transaction ID at version " + index + " for " + table,
                    expected[index][0],
                    versions.get(index).creatorTransactionId());
            assertEquals(
                    "begin commit sequence at version " + index + " for " + table,
                    expected[index][1],
                    versions.get(index).beginCommitSequence());
        }
    }

    private static void shutdownMemoryDatabase(String databaseName) throws Exception {
        try {
            DriverManager.getConnection(
                    "jdbc:derby:memory:" + databaseName + ";shutdown=true");
            fail("Memory database shutdown should throw the normal Derby shutdown exception");
        } catch (java.sql.SQLException expected) {
            if (!"08006".equals(expected.getSQLState())) {
                throw expected;
            }
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Child JVM used to stop on either side of the RawStore commit record. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "insert into crash_a values (1, 'a')");
                executeUpdate(connection, "insert into crash_b values (2, 'b')");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
