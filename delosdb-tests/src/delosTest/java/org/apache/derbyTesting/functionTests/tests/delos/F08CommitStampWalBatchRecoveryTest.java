/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.F08CommitStampWalBatchRecoveryTest

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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Crash proof for the F08-D page-level commit-stamp WAL control. */
public final class F08CommitStampWalBatchRecoveryTest extends MvccSqlTestSupport {
    private static final String A1_PROPERTY =
            "delosdb.experimental.mvccGen2A1.enabled";
    private static final String PAGE_REUSE_PROPERTY =
            "delosdb.experimental.mvccGen2CommitStampBatch.enabled";
    private static final String WAL_BATCH_PROPERTY =
            "delosdb.experimental.mvccGen2CommitStampWalBatch.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final int INSERT_WIDTH = 100;

    public void testCrashAfterBatchedStampBeforeRawCommitRecoversAsRollback() throws Exception {
        runCrashProof("after-stamp-before-raw-commit", 91, 0);
    }

    public void testCrashAfterRawCommitWithBatchedStampRecoversCommittedRows() throws Exception {
        runCrashProof("after-raw-commit-before-publication", 92, INSERT_WIDTH);
    }

    private void runCrashProof(
            String failurePoint,
            int expectedStatus,
            int expectedRows) throws Exception {
        String database = Path.of("f08-commit-stamp-wal-batch-recovery-" + expectedStatus)
                .toAbsolutePath()
                .normalize()
                .toString();
        String previousA1 = System.getProperty(A1_PROPERTY);
        try {
            System.setProperty(A1_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table F08_WAL_BATCH_RECOVERY "
                                + "(id int not null, payload varchar(128) not null) "
                                + "using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database);
        } finally {
            restoreProperty(A1_PROPERTY, previousA1);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + A1_PROPERTY + "=true",
                "-D" + PAGE_REUSE_PROPERTY + "=true",
                "-D" + WAL_BATCH_PROPERTY + "=true",
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
            fail("F08-D crash worker did not terminate at " + failurePoint);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at " + failurePoint + "; output=" + output,
                expectedStatus,
                process.exitValue());

        try (Connection recovered = openDatabase(database, false)) {
            assertEquals(expectedRows, countRows(recovered));
        } finally {
            shutdownDatabase(database);
        }
    }

    private static int countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*) from F08_WAL_BATCH_RECOVERY")) {
            assertTrue(result.next());
            int count = result.getInt(1);
            assertFalse(result.next());
            return count;
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
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into F08_WAL_BATCH_RECOVERY (id, payload) values (?, ?)")) {
                    String payload = "x".repeat(96);
                    for (int id = 1; id <= INSERT_WIDTH; id++) {
                        statement.setInt(1, id);
                        statement.setString(2, payload);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            }
            System.err.println("commit returned without configured F08-D RawStore halt");
            System.exit(93);
        }
    }
}
