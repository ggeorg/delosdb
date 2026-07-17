/*

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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Proves that an unforced raw-store decision cannot leave committed MVCC evidence. */
public final class MvccRawStoreDecisionWalCrashTest extends MvccSqlTestSupport {
    private static final int HALT_STATUS = 84;

    public void testPowerLossAfterDecisionStageBeforeRawCommitDoesNotFalseCommit()
            throws Exception {
        Path database = Path.of("mvcc-raw-store-wal-crash").toAbsolutePath().normalize();
        Path logSnapshot = Path.of("mvcc-raw-store-wal-crash-log-snapshot")
                .toAbsolutePath().normalize();
        deleteRecursively(database);
        deleteRecursively(logSnapshot);

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database.toString(),
                logSnapshot.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("raw-store WAL crash worker did not terminate");
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at the configured pre-commit boundary; output=" + output,
                HALT_STATUS, process.exitValue());

        restoreDirectory(logSnapshot, database.resolve("log"));

        try (Connection connection = openDatabase(database.toString(), false)) {
            assertCount(connection, "select count(*) from heap_wal_t", 0);
            assertCount(connection, "select count(*) from mvcc_wal_t", 0);
        }

        Path decisions = database.resolve(
                "delos_mvcc/inherited-store/database-decisions");
        if (Files.isDirectory(decisions)) {
            try (var files = Files.list(decisions)) {
                assertEquals("an uncommitted raw-store operation must leave no decision marker",
                        0L,
                        files.filter(path -> path.getFileName().toString().endsWith(".decision"))
                                .count());
            }
        }
        shutdownDatabase(database.toString());
        deleteRecursively(database);
        deleteRecursively(logSnapshot);
    }

    private static void assertCount(Connection connection, String sql, int expected)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        deleteRecursively(target);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void restoreDirectory(Path snapshot, Path target) throws IOException {
        if (!Files.isDirectory(snapshot)) {
            throw new IOException("missing raw-store log snapshot " + snapshot);
        }
        copyDirectory(snapshot, target);
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

    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 2) {
                System.err.println("expected database and log-snapshot paths");
                System.exit(90);
            }
            Path database = Path.of(args[0]).toAbsolutePath().normalize();
            Path logSnapshot = Path.of(args[1]).toAbsolutePath().normalize();
            installHalt(database);

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + database + ";create=true")) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "create table heap_wal_t (id int primary key, value int)");
                    statement.executeUpdate(
                            "create table mvcc_wal_t (id int primary key, value int) using delos_mvcc");
                }
                copyDirectory(database.resolve("log"), logSnapshot);

                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("insert into heap_wal_t values (1, 10)");
                    statement.executeUpdate("insert into mvcc_wal_t values (1, 10)");
                }
                connection.commit();
            }
            System.err.println("mixed commit returned without the configured process halt");
            System.exit(91);
        }

        private static void installHalt(Path database) throws Exception {
            Class<?> registry = Class.forName(
                    "io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailurePointRegistry");
            Method install = registry.getDeclaredMethod(
                    "installHaltForTesting",
                    Path.class,
                    String.class,
                    long.class,
                    int.class);
            install.setAccessible(true);
            install.invoke(
                    null,
                    database,
                    "BEFORE_DERBY_RAW_STORE_COMMIT",
                    1L,
                    HALT_STATUS);
        }
    }
}
