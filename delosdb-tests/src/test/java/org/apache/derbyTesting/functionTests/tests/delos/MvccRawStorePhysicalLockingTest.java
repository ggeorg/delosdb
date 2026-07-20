/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStorePhysicalLockingTest

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
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Row-level RawStore physical locking and private ordered-index publication proofs. */
public final class MvccRawStorePhysicalLockingTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String BEFORE_RAW_COMMIT =
            "after-stamp-before-raw-commit";
    private static final String AFTER_RAW_COMMIT =
            "after-raw-commit-before-publication";

    public void testReaderAndDifferentRowWritersDoNotSerializeOnTableContainers()
            throws Exception {
        String database = databaseName("mvcc-raw-store-physical-row-locking");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table physical_lock_t ("
                                + "id int primary key, value int, email varchar(64) unique) "
                                + "using delos_mvcc");
                executeUpdate(setup,
                        "insert into physical_lock_t values "
                                + "(1, 10, 'one@example'), (2, 20, 'two@example')");
                setup.commit();
            }

            try (Connection first = openDatabase(database, false);
                 Connection second = openDatabase(database, false);
                 Connection reader = openDatabase(database, false)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                reader.setAutoCommit(false);

                // Capture a transaction-wide snapshot before either writer.
                assertRows(reader,
                        "select id, value from physical_lock_t order by id",
                        "1|10",
                        "2|20");

                executeUpdate(first,
                        "update physical_lock_t set value = 11 where id = 1");
                assertRows(first,
                        "select value from physical_lock_t where id = 1",
                        "11");

                ExecutorService executor = Executors.newFixedThreadPool(2);
                try {
                    Future<String> differentRowWriter = executor.submit(() -> {
                        try {
                            executeUpdate(second,
                                    "update physical_lock_t set value = 22 where id = 2");
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState() + ':' + failure.getMessage();
                        }
                    });
                    assertEquals(
                            "a different stable row must not wait for a table-wide physical lock",
                            "SUCCESS",
                            differentRowWriter.get(10, TimeUnit.SECONDS));
                    assertRows(second,
                            "select value from physical_lock_t where id = 2",
                            "22");

                    Future<String> nonBlockingReader = executor.submit(() -> {
                        try {
                            assertRows(reader,
                                    "select id, value from physical_lock_t order by id",
                                    "1|10",
                                    "2|20");
                            return "SUCCESS";
                        } catch (Throwable failure) {
                            return failure.getClass().getName() + ':' + failure.getMessage();
                        }
                    });
                    assertEquals(
                            "a snapshot reader must not wait behind uncommitted physical writes",
                            "SUCCESS",
                            nonBlockingReader.get(10, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }

                first.commit();
                second.commit();

                // The existing reader keeps its transaction snapshot.
                assertRows(reader,
                        "select id, value from physical_lock_t order by id",
                        "1|10",
                        "2|20");
                reader.commit();
                assertRows(reader,
                        "select id, value from physical_lock_t order by id",
                        "1|11",
                        "2|22");
                reader.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testPrivateOrderedIndexGenerationPublishesOnlyAtCommit()
            throws Exception {
        String database = databaseName("mvcc-raw-store-private-index-publication");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table private_index_t (id int primary key, score int) using delos_mvcc");
                executeUpdate(setup, "insert into private_index_t values (1, 10)");
                setup.commit();
            }

            long originalIndex;
            try (Connection observer = openDatabase(database, false)) {
                originalIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        observer,
                        "PRIVATE_INDEX_T");
                assertTrue(originalIndex > 0L);
                observer.commit();
            }

            // A private generation created after the savepoint is fully undone.
            try (Connection writer = openDatabase(database, false);
                 Connection observer = openDatabase(database, false)) {
                writer.setAutoCommit(false);
                Savepoint beforeGeneration = writer.setSavepoint("before_generation");
                executeUpdate(writer,
                        "update private_index_t set score = 20 where id = 1");
                assertRows(writer,
                        "select id from private_index_t where score = 20",
                        "1");
                assertRows(observer,
                        "select id from private_index_t where score = 10",
                        "1");
                assertRows(observer,
                        "select id from private_index_t where score = 20");
                assertEquals(originalIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "PRIVATE_INDEX_T"));

                writer.rollback(beforeGeneration);
                writer.releaseSavepoint(beforeGeneration);
                assertRows(writer,
                        "select id from private_index_t where score = 10",
                        "1");
                writer.commit();
                observer.commit();
            }
            try (Connection observer = openDatabase(database, false)) {
                assertEquals(originalIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "PRIVATE_INDEX_T"));
                assertTrue(MvccRawStoreMetadataInspection.containerExists(
                        observer,
                        originalIndex));
                observer.commit();
            }

            // A committed mutation atomically switches generations and drops the old one.
            long publishedIndex;
            try (Connection writer = openDatabase(database, false);
                 Connection observer = openDatabase(database, false)) {
                writer.setAutoCommit(false);
                executeUpdate(writer,
                        "update private_index_t set score = 30 where id = 1");
                Savepoint afterFirstUpdate = writer.setSavepoint("after_first_update");
                executeUpdate(writer,
                        "update private_index_t set score = 35 where id = 1");
                writer.rollback(afterFirstUpdate);
                writer.releaseSavepoint(afterFirstUpdate);
                assertRows(writer,
                        "select id from private_index_t where score = 30",
                        "1");
                assertRows(observer,
                        "select id from private_index_t where score = 10",
                        "1");
                assertEquals(originalIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "PRIVATE_INDEX_T"));
                writer.commit();
                observer.commit();
            }
            try (Connection observer = openDatabase(database, false)) {
                assertRows(observer,
                        "select id from private_index_t where score = 30",
                        "1");
                publishedIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        observer,
                        "PRIVATE_INDEX_T");
                assertTrue("commit must publish a new ordered-index generation",
                        publishedIndex != originalIndex);
                assertFalse("the replaced generation must be dropped transactionally",
                        MvccRawStoreMetadataInspection.containerExists(observer, originalIndex));
                assertTrue(MvccRawStoreMetadataInspection.containerExists(observer, publishedIndex));
                observer.commit();
            }

            // Transaction rollback preserves the currently published generation.
            try (Connection writer = openDatabase(database, false)) {
                writer.setAutoCommit(false);
                executeUpdate(writer,
                        "update private_index_t set score = 40 where id = 1");
                writer.rollback();
            }
            try (Connection observer = openDatabase(database, false)) {
                assertRows(observer,
                        "select id from private_index_t where score = 30",
                        "1");
                assertEquals(publishedIndex,
                        MvccRawStoreMetadataInspection.orderedIndexContainerId(
                                observer,
                                "PRIVATE_INDEX_T"));
                observer.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testPrivateGenerationRecoveryOnBothRawStoreCommitBoundaries()
            throws Exception {
        verifyCrashBoundary(BEFORE_RAW_COMMIT, 91, false);
        verifyCrashBoundary(AFTER_RAW_COMMIT, 92, true);
    }

    public void testMemoryDatabaseUsesTheSameRowLevelPhysicalLocking()
            throws Exception {
        String database = "mvcc_raw_store_physical_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection setup = DriverManager.getConnection(
                     "jdbc:derby:memory:" + database + ";create=true")) {
            setup.setAutoCommit(false);
            executeUpdate(setup,
                    "create table memory_physical_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(setup,
                    "insert into memory_physical_t values (1, 10), (2, 20)");
            setup.commit();
        }

        try (Connection first = DriverManager.getConnection("jdbc:derby:memory:" + database);
             Connection second = DriverManager.getConnection("jdbc:derby:memory:" + database);
             Connection reader = DriverManager.getConnection("jdbc:derby:memory:" + database)) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            reader.setAutoCommit(false);
            assertRows(reader,
                    "select id, value from memory_physical_t order by id",
                    "1|10",
                    "2|20");

            long initialIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                    reader,
                    "MEMORY_PHYSICAL_T");
            executeUpdate(first,
                    "update memory_physical_t set value = 11 where id = 1");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> secondWriter = executor.submit(() -> {
                    try {
                        executeUpdate(second,
                                "update memory_physical_t set value = 22 where id = 2");
                        return "SUCCESS";
                    } catch (SQLException failure) {
                        return failure.getSQLState() + ':' + failure.getMessage();
                    }
                });
                assertEquals(
                        "memory writers on different rows must not serialize physically",
                        "SUCCESS",
                        secondWriter.get(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }

            assertRows(reader,
                    "select id, value from memory_physical_t order by id",
                    "1|10",
                    "2|20");
            first.commit();
            long firstPublished = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                    first,
                    "MEMORY_PHYSICAL_T");
            assertTrue(firstPublished != initialIndex);
            first.commit();
            second.commit();
            long secondPublished = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                    second,
                    "MEMORY_PHYSICAL_T");
            assertTrue(secondPublished != firstPublished);
            second.commit();

            reader.commit();
            assertRows(reader,
                    "select id, value from memory_physical_t order by id",
                    "1|11",
                    "2|22");
            assertFalse(MvccRawStoreMetadataInspection.containerExists(reader, initialIndex));
            assertFalse(MvccRawStoreMetadataInspection.containerExists(reader, firstPublished));
            assertTrue(MvccRawStoreMetadataInspection.containerExists(reader, secondPublished));
            reader.commit();
        }
        shutdownMemoryDatabase(database);
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-physical-crash-"
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
                        "create table physical_crash_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(setup,
                        "insert into physical_crash_t values (1, 10)");
                setup.commit();
                originalIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                        setup,
                        "PHYSICAL_CRASH_T");
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
            fail("RawStore MVCC physical-locking crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output,
                expectedStatus,
                process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            long recoveredIndex = MvccRawStoreMetadataInspection.orderedIndexContainerId(
                    recovered,
                    "PHYSICAL_CRASH_T");
            if (expectCommitted) {
                assertRows(recovered,
                        "select id from physical_crash_t where value = 20",
                        "1");
                assertTrue(recoveredIndex != originalIndex);
                assertFalse(MvccRawStoreMetadataInspection.containerExists(
                        recovered,
                        originalIndex));
                assertTrue(MvccRawStoreMetadataInspection.containerExists(
                        recovered,
                        recoveredIndex));
            } else {
                assertRows(recovered,
                        "select id from physical_crash_t where value = 10",
                        "1");
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

    /** Child JVM halts on either side of the inherited RawStore commit record. */
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
                        "update physical_crash_t set value = 20 where id = 1");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
