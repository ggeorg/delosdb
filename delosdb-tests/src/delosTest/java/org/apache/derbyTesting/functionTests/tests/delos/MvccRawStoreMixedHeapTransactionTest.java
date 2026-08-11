/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreMixedHeapTransactionTest

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
import java.util.concurrent.TimeUnit;

/** One inherited RawStore outcome across heap and RawStore-backed MVCC mutations. */
public final class MvccRawStoreMixedHeapTransactionTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testMixedHeapMvccCommitRollbackSavepointAndReopen() throws Exception {
        String database = databaseName("mvcc-raw-store-mixed-heap-file");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                createTables(connection, "heap_t", "mvcc_t");

                executeUpdate(connection, "insert into heap_t values (1, 'heap-one')");
                executeUpdate(connection, "insert into mvcc_t values (1, 'mvcc-one')");
                connection.commit();
                assertCounters(connection, 2L, 65L, 1L);
                connection.commit();

                executeUpdate(connection, "insert into mvcc_t values (2, 'mvcc-rollback')");
                executeUpdate(connection, "insert into heap_t values (2, 'heap-rollback')");
                executeUpdate(connection, "update mvcc_t set name = 'mvcc-rollback-update' where id = 1");
                executeUpdate(connection, "update heap_t set name = 'heap-rollback-update' where id = 1");
                connection.rollback();
                assertRows(connection, "select id, name from heap_t order by id", "1|heap-one");
                assertRows(connection, "select id, name from mvcc_t order by id", "1|mvcc-one");
                assertCounters(connection, 3L, 65L, 1L);
                connection.commit();

                executeUpdate(connection, "insert into heap_t values (3, 'heap-before-savepoint')");
                executeUpdate(connection, "insert into mvcc_t values (3, 'mvcc-before-savepoint')");
                Savepoint savepoint = connection.setSavepoint("mixed_before_tail");
                executeUpdate(connection, "update heap_t set name = 'heap-after-savepoint' where id = 1");
                executeUpdate(connection, "update mvcc_t set name = 'mvcc-after-savepoint' where id = 1");
                executeUpdate(connection, "insert into heap_t values (4, 'heap-after-savepoint')");
                executeUpdate(connection, "insert into mvcc_t values (4, 'mvcc-after-savepoint')");
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                connection.commit();

                assertRows(connection,
                        "select id, name from heap_t order by id",
                        "1|heap-one",
                        "3|heap-before-savepoint");
                assertRows(connection,
                        "select id, name from mvcc_t order by id",
                        "1|mvcc-one",
                        "3|mvcc-before-savepoint");
                assertCounters(connection, 4L, 65L, 2L);
                connection.commit();

                executeUpdate(connection, "delete from mvcc_t where id = 1");
                executeUpdate(connection, "delete from heap_t where id = 1");
                connection.commit();
                assertRows(connection, "select id, name from heap_t order by id", "3|heap-before-savepoint");
                assertRows(connection, "select id, name from mvcc_t order by id", "3|mvcc-before-savepoint");
                assertCounters(connection, 5L, 65L, 3L);
                connection.commit();

                executeUpdate(connection, "insert into heap_t values (5, 'heap-only')");
                connection.commit();
                assertCounters(connection, 5L, 65L, 3L);
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertRows(reopened,
                        "select id, name from heap_t order by id",
                        "3|heap-before-savepoint",
                        "5|heap-only");
                assertRows(reopened,
                        "select id, name from mvcc_t order by id",
                        "3|mvcc-before-savepoint");
                assertCounters(reopened, 5L, 65L, 3L);
                reopened.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testMixedHeapMvccCommitSurvivesBothRawStoreCrashBoundaries() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);
    }

    public void testMemoryDatabaseSupportsMixedHeapMvccTransactions() throws Exception {
        String database = "mvcc_raw_store_mixed_heap_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            connection.setAutoCommit(false);
            createTables(connection, "memory_heap", "memory_mvcc");

            executeUpdate(connection, "insert into memory_heap values (1, 'heap')");
            executeUpdate(connection, "insert into memory_mvcc values (1, 'mvcc')");
            connection.commit();
            assertRows(connection, "select id, name from memory_heap", "1|heap");
            assertRows(connection, "select id, name from memory_mvcc", "1|mvcc");

            executeUpdate(connection, "update memory_mvcc set name = 'mvcc-rollback' where id = 1");
            executeUpdate(connection, "delete from memory_heap where id = 1");
            connection.rollback();
            assertRows(connection, "select id, name from memory_heap", "1|heap");
            assertRows(connection, "select id, name from memory_mvcc", "1|mvcc");

            executeUpdate(connection, "update memory_heap set name = 'heap-new' where id = 1");
            executeUpdate(connection, "update memory_mvcc set name = 'mvcc-new' where id = 1");
            connection.commit();
            assertRows(connection, "select id, name from memory_heap", "1|heap-new");
            assertRows(connection, "select id, name from memory_mvcc", "1|mvcc-new");
            assertCounters(connection, 4L, 65L, 2L);
            connection.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-mixed-heap-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                createTables(setup, "crash_heap", "crash_mvcc");
                executeUpdate(setup, "insert into crash_heap values (1, 'heap-old')");
                executeUpdate(setup, "insert into crash_mvcc values (1, 'mvcc-old')");
                executeUpdate(setup, "insert into crash_heap values (2, 'heap-delete')");
                executeUpdate(setup, "insert into crash_mvcc values (2, 'mvcc-delete')");
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
            fail("Mixed heap/MVCC crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection recovered = openDatabase(database, false)) {
                recovered.setAutoCommit(false);
                if (expectCommitted) {
                    assertRows(recovered,
                            "select id, name from crash_heap order by id",
                            "1|heap-new",
                            "3|heap-insert");
                    assertRows(recovered,
                            "select id, name from crash_mvcc order by id",
                            "1|mvcc-new",
                            "3|mvcc-insert");
                    assertCounters(recovered, 3L, 129L, 65L);
                } else {
                    assertRows(recovered,
                            "select id, name from crash_heap order by id",
                            "1|heap-old",
                            "2|heap-delete");
                    assertRows(recovered,
                            "select id, name from crash_mvcc order by id",
                            "1|mvcc-old",
                            "2|mvcc-delete");
                    assertCounters(recovered, 3L, 129L, 1L);
                }
                recovered.commit();
            }
            shutdownDatabase(database);
        }
    }

    private static void createTables(Connection connection, String heapTable, String mvccTable)
            throws Exception {
        executeUpdate(connection,
                "create table " + heapTable + " (id int primary key, name varchar(64))");
        executeUpdate(connection,
                "create table " + mvccTable + " (id int, name varchar(64)) using delos_mvcc");
        connection.commit();
    }

    private static void assertCounters(
            Connection connection,
            long nextTransactionId,
            long durableNextCommitSequence,
            long committedHighWater) throws Exception {
        MvccRawStoreMetadataInspection.Counters counters =
                MvccRawStoreMetadataInspection.counters(connection);
        assertEquals("next transaction ID", nextTransactionId, counters.nextTransactionId());
        assertEquals(
                "durable next commit sequence",
                durableNextCommitSequence,
                counters.nextCommitSequence());
        assertEquals("committed high-water", committedHighWater, counters.committedHighWater());
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
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

    /** Child JVM which halts at one of the two RawStore commit boundaries. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            System.setProperty(ENABLED_PROPERTY, "true");
            try (Connection connection = openDatabase(args[0], false)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "update crash_heap set name = 'heap-new' where id = 1");
                executeUpdate(connection, "update crash_mvcc set name = 'mvcc-new' where id = 1");
                executeUpdate(connection, "delete from crash_heap where id = 2");
                executeUpdate(connection, "delete from crash_mvcc where id = 2");
                executeUpdate(connection, "insert into crash_heap values (3, 'heap-insert')");
                executeUpdate(connection, "insert into crash_mvcc values (3, 'mvcc-insert')");
                connection.commit();
                throw new AssertionError("Configured failure point did not halt the child JVM");
            }
        }
    }
}
