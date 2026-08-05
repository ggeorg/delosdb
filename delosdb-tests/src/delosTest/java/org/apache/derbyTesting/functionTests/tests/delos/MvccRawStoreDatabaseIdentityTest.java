/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreDatabaseIdentityTest

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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Durable database-wide MvccTransactionId and MvccCommitSequence proof. */
public final class MvccRawStoreDatabaseIdentityTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testRollbackAndRebootDoNotReuseDatabaseWideIdentities() throws Exception {
        String database = databaseName("mvcc-raw-store-database-identities");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table identity_mvcc_t (id int, name varchar(64)) using delos_mvcc");
                connection.commit();

                assertCounters(connection, 1L, 1L, 0L);

                executeUpdate(connection, "insert into identity_mvcc_t values (1, 'rolled-back')");
                connection.rollback();
                assertCounters(connection, 2L, 1L, 0L);
                assertRows(connection, "select id from identity_mvcc_t");
                connection.commit();

                executeUpdate(connection, "insert into identity_mvcc_t values (2, 'first-commit')");
                connection.commit();
                assertCounters(connection, 3L, 2L, 1L);
                assertVersionIdentities(
                        connection,
                        "IDENTITY_MVCC_T",
                        new long[][] {{2L, 1L}});
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                executeUpdate(reopened, "insert into identity_mvcc_t values (3, 'after-reboot')");
                reopened.commit();
                assertCounters(reopened, 4L, 3L, 2L);
                assertVersionIdentities(
                        reopened,
                        "IDENTITY_MVCC_T",
                        new long[][] {{2L, 1L}, {3L, 2L}});
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testSeparateTablesShareOneDatabaseWideIdentitySequence() throws Exception {
        String database = databaseName("mvcc-raw-store-database-identity-tables");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table identity_table_a (id int) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "create table identity_table_b (id int) using delos_mvcc");
                connection.commit();

                executeUpdate(connection, "insert into identity_table_a values 1");
                connection.commit();
                assertCounters(connection, 2L, 2L, 1L);
                assertVersionIdentities(
                        connection,
                        "IDENTITY_TABLE_A",
                        new long[][] {{1L, 1L}});
                connection.commit();

                executeUpdate(connection, "insert into identity_table_b values 2");
                connection.commit();
                assertCounters(connection, 3L, 3L, 2L);
                assertVersionIdentities(
                        connection,
                        "IDENTITY_TABLE_B",
                        new long[][] {{2L, 2L}});
                connection.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testCrashBeforeRawCommitPreservesReservedIdentityGaps() throws Exception {
        String database = Path.of("mvcc-raw-store-database-identity-crash-"
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table identity_crash_t (id int, name varchar(64)) using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + "=after-stamp-before-raw-commit",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore MVCC identity crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt before RawStore commit; output=" + output, 91, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection recovered = openDatabase(database, false)) {
                recovered.setAutoCommit(false);
                assertCounters(recovered, 2L, 2L, 0L);
                assertRows(recovered, "select id from identity_crash_t");
                recovered.commit();

                executeUpdate(recovered, "insert into identity_crash_t values (2, 'after-gap')");
                recovered.commit();
                assertCounters(recovered, 3L, 3L, 2L);
                assertVersionIdentities(
                        recovered,
                        "IDENTITY_CRASH_T",
                        new long[][] {{2L, 2L}});
                recovered.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testCountersAreDatabaseScopedAndUseTheMemoryRawStore() throws Exception {
        String first = databaseName("mvcc-raw-store-database-identity-a");
        String second = databaseName("mvcc-raw-store-database-identity-b");
        String memory = "mvcc_raw_store_database_identity_memory";

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection firstConnection = openDatabase(first, true);
                 Connection secondConnection = openDatabase(second, true)) {
                firstConnection.setAutoCommit(false);
                secondConnection.setAutoCommit(false);
                executeUpdate(firstConnection, "create table identity_a (id int) using delos_mvcc");
                executeUpdate(secondConnection, "create table identity_b (id int) using delos_mvcc");
                firstConnection.commit();
                secondConnection.commit();

                executeUpdate(firstConnection, "insert into identity_a values 1");
                firstConnection.commit();
                assertCounters(firstConnection, 2L, 2L, 1L);
                firstConnection.commit();
                assertCounters(secondConnection, 1L, 1L, 0L);
                secondConnection.commit();
            }
            shutdownDatabase(first);
            shutdownDatabase(second);

            try (Connection memoryConnection = DriverManager.getConnection(
                    "jdbc:derby:memory:" + memory + ";create=true")) {
                memoryConnection.setAutoCommit(false);
                executeUpdate(memoryConnection, "create table identity_memory (id int) using delos_mvcc");
                memoryConnection.commit();
                executeUpdate(memoryConnection, "insert into identity_memory values 1");
                memoryConnection.rollback();
                executeUpdate(memoryConnection, "insert into identity_memory values 2");
                memoryConnection.commit();
                assertCounters(memoryConnection, 3L, 2L, 1L);
                assertVersionIdentities(
                        memoryConnection,
                        "IDENTITY_MEMORY",
                        new long[][] {{2L, 1L}});
                memoryConnection.commit();
            }
            shutdownMemoryDatabase(memory);
        }
    }

    /** Child JVM used to stop after identity reservation and version stamping. */
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
                executeUpdate(
                        connection,
                        "insert into identity_crash_t values (1, 'before-crash')");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
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
        assertEquals("version count", expected.length, versions.size());
        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                    "creator transaction ID at version " + index,
                    expected[index][0],
                    versions.get(index).creatorTransactionId());
            assertEquals(
                    "begin commit sequence at version " + index,
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
}
