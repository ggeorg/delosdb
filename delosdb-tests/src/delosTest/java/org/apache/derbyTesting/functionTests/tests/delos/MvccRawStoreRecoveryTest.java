/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreRecoveryTest

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
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Crash proofs on both sides of the inherited RawStore commit boundary. */
public final class MvccRawStoreRecoveryTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testCrashAfterStampBeforeRawCommitRecoversAsRollback() throws Exception {
        runCrashProof("after-stamp-before-raw-commit", 91, false);
    }

    public void testCrashAfterRawCommitBeforePublicationRecoversCommittedRow() throws Exception {
        runCrashProof("after-raw-commit-before-publication", 92, true);
    }

    private void runCrashProof(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        String database = Path.of("mvcc-raw-store-recovery-" + expectedStatus)
                .toAbsolutePath()
                .normalize()
                .toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table recovery_raw_mvcc (id int, name varchar(64)) using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
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
            fail("RawStore MVCC crash worker did not terminate at " + failurePoint);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at " + failurePoint + "; output=" + output,
                expectedStatus,
                process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection recovered = openDatabase(database, false)) {
                assertRows(recovered,
                        "select id, name from recovery_raw_mvcc order by id",
                        expectCommitted ? new String[] {"1|survivor"} : new String[0]);
            }
            shutdownDatabase(database);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
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
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "insert into recovery_raw_mvcc values (1, 'survivor')");
                }
                connection.commit();
            }
            System.err.println("commit returned without the configured RawStore MVCC halt");
            System.exit(93);
        }
    }
}
