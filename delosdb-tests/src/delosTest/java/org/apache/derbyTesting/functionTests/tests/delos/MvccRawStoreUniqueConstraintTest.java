/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreUniqueConstraintTest

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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.shared.common.error.StandardException;

/** RawStore-native MVCC primary-key and unique-constraint proofs. */
public final class MvccRawStoreUniqueConstraintTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testDeclaredUniqueMetadataAndAccessMethodEnforcement() throws Exception {
        String database = databaseName("mvcc-raw-store-unique");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection,
                        "create table deferred_unique ("
                                + "id int primary key, "
                                + "email varchar(64), "
                                + "constraint uq_deferred_email unique (email) "
                                + "deferrable initially deferred) using delos_mvcc");
                fail("Expected deferrable RawStore MVCC uniqueness to fail closed");
            } catch (SQLException expected) {
                assertEquals("0A000", expected.getSQLState());
            }
            connection.rollback();
            try {
                assertRows(connection, "select id from deferred_unique");
                fail("Unsupported deferrable CREATE TABLE must not leave catalog state");
            } catch (SQLException expected) {
                assertEquals("42X05", expected.getSQLState());
            }
            connection.rollback();

            executeUpdate(connection,
                    "create table unique_t ("
                            + "id int primary key, "
                            + "email varchar(64) unique, "
                            + "tenant int not null, "
                            + "code int not null, "
                            + "constraint uq_tenant_code unique (tenant, code)) using delos_mvcc");

            List<MvccRawStoreMetadataInspection.UniqueConstraintIdentity> constraints =
                    MvccRawStoreMetadataInspection.uniqueConstraints(connection, "UNIQUE_T");
            assertEquals(3, constraints.size());
            assertEquals(List.of(0), ints(constraints.get(0).columns()));
            assertFalse(constraints.get(0).duplicateNullsAllowed());
            assertEquals(List.of(1), ints(constraints.get(1).columns()));
            assertTrue(constraints.get(1).duplicateNullsAllowed());
            assertEquals(List.of(2, 3), ints(constraints.get(2).columns()));
            assertFalse(constraints.get(2).duplicateNullsAllowed());

            executeUpdate(connection, "insert into unique_t values (1, 'a@example', 10, 100)");
            executeUpdate(connection, "insert into unique_t values (2, null, 10, 200)");
            executeUpdate(connection, "insert into unique_t values (3, null, 10, 300)");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into unique_t values (1, 'pk@example', 20, 100)"));
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into unique_t values (4, 'a@example', 20, 200)"));
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into unique_t values (4, 'four@example', 10, 100)"));
            connection.rollback();

            try {
                MvccRawStoreMetadataInspection.insertBaseRowDirect(
                        connection,
                        "UNIQUE_T",
                        row(1, "bypass@example", 30, 300));
                fail("Expected RawStore-native duplicate primary-key rejection");
            } catch (StandardException expected) {
                assertEquals("23505", expected.getSQLState());
            }
            connection.rollback();

            assertRows(connection,
                    "select id, email, tenant, code from unique_t order by id",
                    "1|a@example|10|100",
                    "2|null|10|200",
                    "3|null|10|300");
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testUpdateDeleteSavepointAndReopenPreserveUniqueness() throws Exception {
        String database = databaseName("mvcc-raw-store-unique-history");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table unique_history (id int primary key, email varchar(64) unique) using delos_mvcc");
            executeUpdate(connection, "insert into unique_history values (1, 'one@example')");
            executeUpdate(connection, "insert into unique_history values (2, 'two@example')");
            connection.commit();

            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "update unique_history set email = 'one@example' where id = 2"));
            assertRows(connection,
                    "select id, email from unique_history order by id",
                    "1|one@example",
                    "2|two@example");

            executeUpdate(connection, "delete from unique_history where id = 1");
            executeUpdate(connection, "insert into unique_history values (3, 'one@example')");
            connection.commit();

            Savepoint savepoint = connection.setSavepoint("before_unique_key");
            executeUpdate(connection, "insert into unique_history values (4, 'savepoint@example')");
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            executeUpdate(connection, "insert into unique_history values (5, 'savepoint@example')");
            connection.commit();
        }
        shutdownDatabase(database);

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection reopened = openDatabase(database, false)) {
            reopened.setAutoCommit(false);
            assertDuplicateKey(() -> executeUpdate(
                    reopened,
                    "insert into unique_history values (6, 'one@example')"));
            reopened.rollback();
            assertRows(reopened,
                    "select id, email from unique_history order by id",
                    "2|two@example",
                    "3|one@example",
                    "5|savepoint@example");
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    public void testConcurrentUniqueWritersSerializeAtTheRawStoreUniqueKey() throws Exception {
        String database = databaseName("mvcc-raw-store-unique-concurrent");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table unique_concurrent (id int primary key, email varchar(64) unique) using delos_mvcc");
                setup.commit();
            }

            try (Connection first = openDatabase(database, false);
                 Connection second = openDatabase(database, false)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                executeUpdate(first,
                        "insert into unique_concurrent values (1, 'shared@example')");

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    CountDownLatch started = new CountDownLatch(1);
                    Future<String> duplicate = executor.submit(() -> {
                        started.countDown();
                        try {
                            executeUpdate(second,
                                    "insert into unique_concurrent values (2, 'shared@example')");
                            return "SUCCESS";
                        } catch (SQLException expected) {
                            return expected.getSQLState();
                        }
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    Thread.sleep(250L);
                    assertFalse("second writer should wait for the transaction-duration unique-key lock", duplicate.isDone());
                    first.commit();
                    assertEquals("23505", duplicate.get(15, TimeUnit.SECONDS));
                    second.rollback();

                    executeUpdate(first,
                            "insert into unique_concurrent values (3, 'rollback@example')");
                    Future<String> afterRollback = executor.submit(() -> {
                        try {
                            executeUpdate(second,
                                    "insert into unique_concurrent values (4, 'rollback@example')");
                            second.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    Thread.sleep(250L);
                    assertFalse(afterRollback.isDone());
                    first.rollback();
                    assertEquals("SUCCESS", afterRollback.get(15, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }
            }
            shutdownDatabase(database);
        }
    }

    public void testUniqueStateSurvivesBothCrashBoundariesAndMemory() throws Exception {
        verifyCrashBoundary("after-stamp-before-raw-commit", 91, false);
        verifyCrashBoundary("after-raw-commit-before-publication", 92, true);

        String memoryDatabase = "mvcc_raw_store_unique_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection connection = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_unique (id int primary key, email varchar(64) unique) using delos_mvcc");
            executeUpdate(connection, "insert into memory_unique values (1, 'memory@example')");
            connection.commit();
            assertDuplicateKey(() -> executeUpdate(
                    connection,
                    "insert into memory_unique values (2, 'memory@example')"));
            connection.rollback();
        }
        shutdownMemoryDatabase(memoryDatabase);
    }

    private static void verifyCrashBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-unique-crash-"
                + failurePoint + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_unique (id int primary key, email varchar(64) unique) using delos_mvcc");
                executeUpdate(setup, "insert into crash_unique values (1, 'old@example')");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "--add-exports",
                "java.base/sun.security.action=ALL-UNNAMED",
                "--add-opens",
                "java.base/java.nio=ALL-UNNAMED",
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
            fail("RawStore MVCC unique crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            recovered.setAutoCommit(false);
            String occupied = expectCommitted ? "new@example" : "old@example";
            String available = expectCommitted ? "old@example" : "new@example";
            assertDuplicateKey(() -> executeUpdate(
                    recovered,
                    "insert into crash_unique values (2, '" + occupied + "')"));
            recovered.rollback();
            executeUpdate(recovered,
                    "insert into crash_unique values (3, '" + available + "')");
            recovered.rollback();
        }
        shutdownDatabase(database);
    }

    private static StoreDataValue[] row(int id, String email, int tenant, int code) {
        return new StoreDataValue[] {
                new SQLInteger(id),
                email == null ? new SQLVarchar() : new SQLVarchar(email),
                new SQLInteger(tenant),
                new SQLInteger(code)
        };
    }

    private static List<Integer> ints(int[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
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
                        "update crash_unique set email = 'new@example' where id = 1");
                connection.commit();
            }
            throw new AssertionError("Crash failure point did not halt the child JVM");
        }
    }
}
