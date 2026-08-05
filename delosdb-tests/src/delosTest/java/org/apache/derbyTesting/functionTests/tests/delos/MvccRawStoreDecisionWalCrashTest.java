/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreDecisionWalCrashTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Stage 5 proof that one inherited RawStore recovery decision owns mixed heap/MVCC work.
 */
public final class MvccRawStoreDecisionWalCrashTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    private static final String BEFORE_RAW_COMMIT =
            "after-stamp-before-raw-commit";
    private static final String AFTER_RAW_COMMIT =
            "after-raw-commit-before-publication";

    public void testMixedDecisionRecoversOnlyThroughInheritedRawStore() throws Exception {
        verifyBoundary(BEFORE_RAW_COMMIT, 91, false);
        verifyBoundary(AFTER_RAW_COMMIT, 92, true);
    }

    private static void verifyBoundary(
            String failurePoint,
            int expectedStatus,
            boolean expectCommitted) throws Exception {
        Path database = Path.of(
                "mvcc-raw-store-decision-recovery-"
                        + expectedStatus + '-'
                        + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath()
                .normalize();
        deleteRecursively(database);

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database.toString(), true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table decision_heap_t (id int primary key, value int)");
                executeUpdate(setup,
                        "create table decision_mvcc_t (id int, value int) using delos_mvcc");
                setup.commit();
            }
            shutdownDatabase(database.toString());
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + '=' + failurePoint,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("RawStore decision-recovery worker did not terminate at " + failurePoint);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(
                "worker must halt at " + failurePoint + "; output=" + output,
                expectedStatus,
                process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection recovered = openDatabase(database.toString(), false)) {
                recovered.setAutoCommit(false);
                String[] expectedRows = expectCommitted ? new String[] {"1|10"} : new String[0];
                assertRows(recovered,
                        "select id, value from decision_heap_t order by id",
                        expectedRows);
                assertRows(recovered,
                        "select id, value from decision_mvcc_t order by id",
                        expectedRows);
                recovered.commit();
            }
            shutdownDatabase(database.toString());
        }

        assertNoRetainedDecisionAuthority(database);
        deleteRecursively(database);
    }

    private static void assertNoRetainedDecisionAuthority(Path database) throws IOException {
        Path retained = database.resolve("delos_mvcc");
        if (!Files.exists(retained)) {
            return;
        }
        try (var paths = Files.walk(retained)) {
            assertEquals(
                    "RawStore recovery must create no retained MVCC WAL, decision, or sidecar file",
                    0L,
                    paths.filter(Files::isRegularFile).count());
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    /** Child JVM halted on one side of the inherited RawStore commit record. */
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
                    statement.executeUpdate("insert into decision_heap_t values (1, 10)");
                    statement.executeUpdate("insert into decision_mvcc_t values (1, 10)");
                }
                connection.commit();
            }
            System.err.println("commit returned without the configured RawStore MVCC halt");
            System.exit(93);
        }
    }
}
