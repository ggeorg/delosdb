/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreLogicalLockingTest

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
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Transaction-duration logical row, unique-key, and schema locking proofs. */
public final class MvccRawStoreLogicalLockingTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testStableRowLocksHoldThroughCommitAndRollback() throws Exception {
        String database = databaseName("mvcc-raw-store-logical-row-lock");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table row_lock_anchor (id int) using delos_mvcc");
                executeUpdate(setup,
                        "create table row_lock_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into row_lock_anchor values (1)");
                executeUpdate(setup, "insert into row_lock_t values (1, 10)");
                setup.commit();
            }

            try (Connection first = openDatabase(database, false);
                 Connection second = openDatabase(database, false);
                 Connection observer = openDatabase(database, false)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);

                // Capture a stale transaction-wide snapshot before the first
                // writer moves the stable row head.
                assertRows(second, "select id from row_lock_anchor", "1");
                executeUpdate(first, "update row_lock_t set value = 11 where id = 1");
                assertLogicalLock(observer, "DELOS_MVCC_SCHEMA[", "S", "GRANT");
                assertLogicalLock(observer, "DELOS_MVCC_ROW[", "X", "GRANT");

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    CountDownLatch started = new CountDownLatch(1);
                    Future<String> staleWriter = executor.submit(() -> {
                        started.countDown();
                        try {
                            executeUpdate(second,
                                    "update row_lock_t set value = 12 where id = 1");
                            return "SUCCESS";
                        } catch (SQLException expected) {
                            return expected.getSQLState();
                        }
                    });
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertStillWaiting(staleWriter, "same-row writer must wait for the stable-row lock");
                    first.commit();
                    assertEquals("40001", staleWriter.get(15, TimeUnit.SECONDS));
                    second.rollback();

                    executeUpdate(first, "update row_lock_t set value = 21 where id = 1");
                    Future<String> afterRollback = executor.submit(() -> {
                        try {
                            executeUpdate(second,
                                    "update row_lock_t set value = 22 where id = 1");
                            second.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    assertStillWaiting(afterRollback,
                            "same-row writer must remain blocked until transaction rollback");
                    first.rollback();
                    assertEquals("SUCCESS", afterRollback.get(15, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }

                assertRows(observer, "select value from row_lock_t where id = 1", "22");
            }
            shutdownDatabase(database);
        }
    }

    public void testUniqueKeyLocksSurviveSavepointRollbackAndCommit() throws Exception {
        String database = databaseName("mvcc-raw-store-logical-key-lock");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table key_lock_t ("
                                + "id int primary key, "
                                + "email varchar(64) unique, "
                                + "code int unique) using delos_mvcc");
                setup.commit();
            }

            try (Connection first = openDatabase(database, false);
                 Connection second = openDatabase(database, false);
                 Connection observer = openDatabase(database, false)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executeUpdate(first,
                            "insert into key_lock_t values (1, 'committed@example', 100)");
                    assertLogicalLock(observer, "DELOS_MVCC_KEY[", "X", "GRANT");
                    Future<String> duplicateAfterCommit = executor.submit(() -> {
                        try {
                            executeUpdate(second,
                                    "insert into key_lock_t values (2, 'committed@example', 200)");
                            return "SUCCESS";
                        } catch (SQLException expected) {
                            return expected.getSQLState();
                        }
                    });
                    assertStillWaiting(duplicateAfterCommit,
                            "duplicate writer must wait for the transaction-duration key lock");
                    first.commit();
                    assertEquals("23505", duplicateAfterCommit.get(15, TimeUnit.SECONDS));
                    second.rollback();

                    Savepoint savepoint = first.setSavepoint("before_key");
                    executeUpdate(first,
                            "insert into key_lock_t values (3, 'savepoint@example', 300)");
                    first.rollback(savepoint);
                    first.releaseSavepoint(savepoint);
                    assertRows(first,
                            "select id from key_lock_t where email = 'savepoint@example'");
                    assertLogicalLock(observer, "DELOS_MVCC_KEY[", "X", "GRANT");

                    Future<String> afterSavepoint = executor.submit(() -> {
                        try {
                            executeUpdate(second,
                                    "insert into key_lock_t values (4, 'savepoint@example', 400)");
                            second.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    assertStillWaiting(afterSavepoint,
                            "savepoint rollback must not release transaction-duration key locks");
                    first.commit();
                    assertEquals("SUCCESS", afterSavepoint.get(15, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }
            }
            shutdownDatabase(database);
        }
    }

    public void testSchemaLocksSerializeUniqueDdlAndDml() throws Exception {
        String database = databaseName("mvcc-raw-store-logical-schema-lock");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table schema_lock_t (id int primary key, email varchar(64)) using delos_mvcc");
                setup.commit();
            }

            try (Connection dml = openDatabase(database, false);
                 Connection ddl = openDatabase(database, false);
                 Connection observer = openDatabase(database, false)) {
                dml.setAutoCommit(false);
                ddl.setAutoCommit(false);
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executeUpdate(dml,
                            "insert into schema_lock_t values (1, 'one@example')");
                    assertLogicalLock(observer, "DELOS_MVCC_SCHEMA[", "S", "GRANT");
                    Future<String> addConstraint = executor.submit(() -> {
                        try {
                            executeUpdate(ddl,
                                    "alter table schema_lock_t add constraint uq_schema_email unique (email)");
                            ddl.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    assertStillWaiting(addConstraint,
                            "unique DDL must wait for active shared table-schema locks");
                    dml.commit();
                    assertEquals("SUCCESS", addConstraint.get(20, TimeUnit.SECONDS));

                    executeUpdate(ddl,
                            "alter table schema_lock_t drop constraint uq_schema_email");
                    assertLogicalLock(observer, "DELOS_MVCC_SCHEMA[", "X", "GRANT");
                    Future<String> blockedDml = executor.submit(() -> {
                        try {
                            executeUpdate(dml,
                                    "insert into schema_lock_t values (2, 'two@example')");
                            dml.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    assertStillWaiting(blockedDml,
                            "DML must wait for active exclusive table-schema locks");
                    ddl.rollback();
                    assertEquals("SUCCESS", blockedDml.get(20, TimeUnit.SECONDS));

                    assertDuplicateKey(() -> executeUpdate(
                            dml,
                            "insert into schema_lock_t values (3, 'one@example')"));
                    dml.rollback();
                } finally {
                    executor.shutdownNow();
                }
            }
            shutdownDatabase(database);
        }
    }

    public void testAbortCrashReopenAndMemoryReleaseLogicalLocks() throws Exception {
        String database = databaseName("mvcc-raw-store-logical-lock-release");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table release_lock_t (id int primary key, email varchar(64) unique) using delos_mvcc");
                setup.commit();
            }

            Connection holder = openDatabase(database, false);
            try (Connection waiter = openDatabase(database, false)) {
                holder.setAutoCommit(false);
                waiter.setAutoCommit(false);
                executeUpdate(holder,
                        "insert into release_lock_t values (1, 'released@example')");

                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<String> afterClose = executor.submit(() -> {
                        try {
                            executeUpdate(waiter,
                                    "insert into release_lock_t values (2, 'released@example')");
                            waiter.commit();
                            return "SUCCESS";
                        } catch (SQLException failure) {
                            return failure.getSQLState();
                        }
                    });
                    assertStillWaiting(afterClose,
                            "transaction abort must be the logical-lock release boundary");
                    holder.rollback();
                    holder.close();
                    holder = null;
                    assertEquals("SUCCESS", afterClose.get(15, TimeUnit.SECONDS));
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                if (holder != null) {
                    holder.close();
                }
            }
            shutdownDatabase(database);
        }

        verifyAbruptProcessExitReleasesLogicalLocks();
        verifyMemoryDatabaseLogicalLocks();
    }

    private static void verifyAbruptProcessExitReleasesLogicalLocks() throws Exception {
        String database = Path.of("mvcc-raw-store-logical-lock-crash-"
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table crash_lock_t (id int primary key, email varchar(64) unique) using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "--enable-preview",
                "--add-exports",
                "java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                "--add-exports",
                "java.base/sun.security.action=ALL-UNNAMED",
                "--add-opens",
                "java.base/java.nio=ALL-UNNAMED",
                "-D" + ENABLED_PROPERTY + "=true",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore MVCC logical-lock crash worker did not terminate");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected logical-lock crash status; output=" + output,
                93,
                process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            recovered.setAutoCommit(false);
            executeUpdate(recovered,
                    "insert into crash_lock_t values (2, 'crash@example')");
            recovered.commit();
            assertRows(recovered,
                    "select id, email from crash_lock_t order by id",
                    "2|crash@example");
            recovered.commit();
        }
        shutdownDatabase(database);
    }

    private static void verifyMemoryDatabaseLogicalLocks() throws Exception {
        String memoryDatabase = "mvcc_raw_store_logical_lock_memory_"
                + Long.toUnsignedString(System.nanoTime());
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection first = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase + ";create=true");
             Connection second = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase);
             Connection observer = DriverManager.getConnection(
                     "jdbc:derby:memory:" + memoryDatabase)) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            executeUpdate(first,
                    "create table memory_lock_t (id int primary key, email varchar(64) unique) using delos_mvcc");
            first.commit();
            executeUpdate(first,
                    "insert into memory_lock_t values (1, 'memory@example')");
            assertLogicalLock(observer, "DELOS_MVCC_KEY[", "X", "GRANT");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> waiter = executor.submit(() -> {
                    try {
                        executeUpdate(second,
                                "insert into memory_lock_t values (2, 'memory@example')");
                        second.commit();
                        return "SUCCESS";
                    } catch (SQLException expected) {
                        return expected.getSQLState();
                    }
                });
                assertStillWaiting(waiter,
                        "memory database writers must use the same logical lock manager");
                first.rollback();
                assertEquals("SUCCESS", waiter.get(15, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }
        shutdownMemoryDatabase(memoryDatabase);
    }

    private static void assertLogicalLock(
            Connection connection,
            String lockNamePrefix,
            String mode,
            String state) throws SQLException {
        List<String> locks = logicalLocks(connection);
        for (String lock : locks) {
            if (lock.startsWith(lockNamePrefix)
                    && lock.endsWith("|" + mode + "|" + state)) {
                return;
            }
        }
        fail("Expected logical lock " + lockNamePrefix + " mode=" + mode
                + " state=" + state + "; observed=" + locks);
    }

    private static List<String> logicalLocks(Connection connection) throws SQLException {
        List<String> locks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select lockname, mode, state from syscs_diag.lock_table")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && name.startsWith("DELOS_MVCC_")) {
                    locks.add(name + '|' + rs.getString(2) + '|' + rs.getString(3));
                }
            }
        }
        return locks;
    }

    private static void assertStillWaiting(Future<?> future, String message) throws Exception {
        Thread.sleep(300L);
        assertFalse(message, future.isDone());
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

    /** Child JVM exits while holding uncommitted logical and RawStore locks. */
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
                        "insert into crash_lock_t values (1, 'crash@example')");
                Runtime.getRuntime().halt(93);
            }
        }
    }
}
