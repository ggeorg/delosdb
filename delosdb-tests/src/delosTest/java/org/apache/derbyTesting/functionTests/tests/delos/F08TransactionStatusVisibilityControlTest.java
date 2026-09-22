/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.F08TransactionStatusVisibilityControlTest

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
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Semantic and recovery proof for the F08-F durable transaction-status visibility control. */
public final class F08TransactionStatusVisibilityControlTest extends MvccSqlTestSupport {
    private static final String A1_PROPERTY =
            "delosdb.experimental.mvccGen2A1.enabled";
    private static final String B_PROPERTY =
            "delosdb.experimental.mvccGen2B.pk.enabled";
    private static final String STATUS_PROPERTY =
            "delosdb.experimental.mvccGen2TransactionStatusVisibility.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final int INSERT_WIDTH = 100;

    public void testBareStatusVisibilityHonorsSnapshotRollbackAndReopen() throws Exception {
        String database = databaseName("f08-tx-status-bare");
        try (SystemPropertyScope a1 = setSystemProperty(A1_PROPERTY, "true");
             SystemPropertyScope b = clearSystemProperty(B_PROPERTY);
             SystemPropertyScope status = setSystemProperty(STATUS_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table T (id int not null, payload varchar(128) not null) "
                                + "using delos_mvcc");
                setup.commit();
            }

            try (Connection writer = openDatabase(database, false);
                 Connection reader = openDatabase(database, false)) {
                writer.setAutoCommit(false);
                reader.setAutoCommit(false);
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

                insertBatch(writer, "T", 1, INSERT_WIDTH);
                assertRows(reader, "select count(*) from T", "0");

                writer.commit();
                assertRows(reader, "select count(*) from T", "0");
                reader.commit();
                assertRows(reader, "select count(*) from T", Integer.toString(INSERT_WIDTH));
                reader.commit();

                insertOne(writer, "T", INSERT_WIDTH + 1, "rolled-back");
                writer.rollback();
                assertRows(reader, "select count(*) from T", Integer.toString(INSERT_WIDTH));
                reader.commit();
            }

            shutdownDatabase(database);
            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened, "select count(*) from T", Integer.toString(INSERT_WIDTH));
                assertRows(reopened, "select payload from T where id = 42", "v42");
            }
        } finally {
            shutdownIfBooted(database);
        }
    }

    public void testPrimaryKeyStatusVisibilitySupportsLookupAndDuplicateAfterReopen()
            throws Exception {
        String database = databaseName("f08-tx-status-pk");
        try (SystemPropertyScope a1 = clearSystemProperty(A1_PROPERTY);
             SystemPropertyScope b = setSystemProperty(B_PROPERTY, "true");
             SystemPropertyScope status = setSystemProperty(STATUS_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table T (id int not null primary key, payload varchar(128) not null) "
                                + "using delos_mvcc");
                connection.commit();
                insertBatch(connection, "T", 1, INSERT_WIDTH);
                connection.commit();
                assertRows(connection, "select payload from T where id = 42", "v42");

                assertDuplicateKey(() -> insertOne(connection, "T", 42, "duplicate"));
                connection.rollback();
            }

            shutdownDatabase(database);
            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened, "select payload from T where id = 42", "v42");
                assertRows(reopened, "select count(*) from T", Integer.toString(INSERT_WIDTH));
            }
        } finally {
            shutdownIfBooted(database);
        }
    }

    public void testCrashBeforeRawCommitRollsBackStatusAndRows() throws Exception {
        runCrashProof("after-stamp-before-raw-commit", 91, 0);
    }

    public void testCrashAfterRawCommitRecoversStatusAndRows() throws Exception {
        runCrashProof("after-raw-commit-before-publication", 92, INSERT_WIDTH);
    }

    private void runCrashProof(String failurePoint, int expectedStatus, int expectedRows)
            throws Exception {
        String database = Path.of("f08-tx-status-recovery-" + expectedStatus)
                .toAbsolutePath()
                .normalize()
                .toString();
        String previousA1 = System.getProperty(A1_PROPERTY);
        String previousStatus = System.getProperty(STATUS_PROPERTY);
        try {
            System.setProperty(A1_PROPERTY, "true");
            System.setProperty(STATUS_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table F08_TX_STATUS_RECOVERY "
                                + "(id int not null, payload varchar(128) not null) "
                                + "using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database);
        } finally {
            restoreProperty(A1_PROPERTY, previousA1);
            restoreProperty(STATUS_PROPERTY, previousStatus);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + A1_PROPERTY + "=true",
                "-D" + STATUS_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + "=" + failurePoint,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("F08-F crash worker did not terminate at " + failurePoint);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at " + failurePoint + "; output=" + output,
                expectedStatus,
                process.exitValue());

        try (SystemPropertyScope a1 = setSystemProperty(A1_PROPERTY, "true");
             SystemPropertyScope status = setSystemProperty(STATUS_PROPERTY, "true")) {
            try (Connection recovered = openDatabase(database, false)) {
                assertRows(recovered,
                        "select count(*) from F08_TX_STATUS_RECOVERY",
                        Integer.toString(expectedRows));
            } finally {
                shutdownDatabase(database);
            }
        }
    }

    private static void insertBatch(Connection connection, String table, int firstId, int count)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " (id, payload) values (?, ?)")) {
            for (int offset = 0; offset < count; offset++) {
                int id = firstId + offset;
                statement.setInt(1, id);
                statement.setString(2, "v" + id);
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            assertEquals(count, counts.length);
        }
    }

    private static void insertOne(Connection connection, String table, int id, String payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + " (id, payload) values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            statement.executeUpdate();
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void restoreProperty(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }

    private static void shutdownIfBooted(String database) throws SQLException {
        try {
            shutdownDatabase(database);
        } catch (SQLException e) {
            if (!"XJ004".equals(e.getSQLState()) && !"08006".equals(e.getSQLState())) {
                throw e;
            }
        }
    }

    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                System.err.println("expected database path");
                System.exit(90);
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + args[0])) {
                connection.setAutoCommit(false);
                insertBatch(connection, "F08_TX_STATUS_RECOVERY", 1, INSERT_WIDTH);
                connection.commit();
            }
            System.err.println("commit returned without configured F08-F RawStore halt");
            System.exit(93);
        }
    }
}
