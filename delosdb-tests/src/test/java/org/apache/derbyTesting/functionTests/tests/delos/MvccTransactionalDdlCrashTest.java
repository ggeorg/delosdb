/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccTransactionalDdlCrashTest

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Process-halt recovery proofs around the raw-store MVCC DDL decision. */
public final class MvccTransactionalDdlCrashTest extends MvccSqlTestSupport {
    private static final int HALT_STATUS = 85;

    public void testCreateBeforeRawCommitIsUndoneOnReopen() throws Exception {
        CrashResult result = runScenario("create-before", "BEFORE_DERBY_RAW_STORE_COMMIT", true);
        try (Connection connection = openDatabase(result.database().toString(), false)) {
            assertTableMissing(connection, "crash_created_t");
            assertCount(connection, "select count(*) from crash_witness_t", 0);
        }
        assertNoLifecycleMarkers(result.database());
        assertNoProviderState(result.database(), result.lifecycleContainerId());
        cleanup(result);
    }

    public void testCreateAfterRawCommitIsRecoveredOnReopen() throws Exception {
        CrashResult result = runScenario("create-after", "AFTER_DERBY_RAW_STORE_COMMIT", false);
        try (Connection connection = openDatabase(result.database().toString(), false)) {
            assertRows(connection, "select id from crash_created_t", new String[0]);
            assertCount(connection, "select count(*) from crash_witness_t", 1);
        }
        assertNoLifecycleMarkers(result.database());
        assertProviderStateExists(result.database(), result.lifecycleContainerId());
        cleanup(result);
    }

    public void testDropBeforeRawCommitPreservesTableOnReopen() throws Exception {
        CrashResult result = runScenario("drop-before", "BEFORE_DERBY_RAW_STORE_COMMIT", true);
        try (Connection connection = openDatabase(result.database().toString(), false)) {
            assertRows(connection,
                    "select id, value from crash_drop_t order by id",
                    "1|10", "2|20");
            assertCount(connection, "select count(*) from crash_witness_t", 0);
        }
        assertProviderStateExists(result.database(), result.lifecycleContainerId());
        cleanup(result);
    }

    public void testDropAfterRawCommitRetiresTableOnReopen() throws Exception {
        CrashResult result = runScenario("drop-after", "AFTER_DERBY_RAW_STORE_COMMIT", false);
        try (Connection connection = openDatabase(result.database().toString(), false)) {
            assertTableMissing(connection, "crash_drop_t");
            assertCount(connection, "select count(*) from crash_witness_t", 1);
        }
        assertNoProviderState(result.database(), result.lifecycleContainerId());
        cleanup(result);
    }

    private static CrashResult runScenario(
            String scenario,
            String failurePoint,
            boolean restorePreCommitLog) throws Exception {
        Path database = Path.of("mvcc-transactional-ddl-crash-" + scenario)
                .toAbsolutePath().normalize();
        Path logSnapshot = Path.of("mvcc-transactional-ddl-crash-" + scenario + "-log")
                .toAbsolutePath().normalize();
        Path metadata = Path.of("mvcc-transactional-ddl-crash-" + scenario + ".container")
                .toAbsolutePath().normalize();
        deleteRecursively(database);
        deleteRecursively(logSnapshot);
        Files.deleteIfExists(metadata);

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database.toString(),
                logSnapshot.toString(),
                metadata.toString(),
                scenario.startsWith("create") ? "CREATE" : "DROP",
                failurePoint)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("transactional DDL crash worker did not terminate for " + scenario);
        }
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt at " + failurePoint + "; output=" + output,
                HALT_STATUS, process.exitValue());
        assertTrue("missing lifecycle container metadata", Files.isRegularFile(metadata));
        long lifecycleContainerId = Long.parseLong(Files.readString(metadata).trim());

        if (restorePreCommitLog) {
            restoreDirectory(logSnapshot, database.resolve("log"));
        }
        return new CrashResult(database, logSnapshot, metadata, lifecycleContainerId);
    }

    private static void cleanup(CrashResult result) throws Exception {
        shutdownDatabase(result.database().toString());
        deleteRecursively(result.database());
        deleteRecursively(result.logSnapshot());
        Files.deleteIfExists(result.metadata());
    }

    private static void assertCount(Connection connection, String sql, int expected)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
        }
    }

    private static void assertTableMissing(Connection connection, String tableName)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("select * from " + tableName);
            fail("Expected table to be absent: " + tableName);
        } catch (SQLException expected) {
            assertTrue("expected missing-table SQLState, got " + expected,
                    containsSqlState(expected, "42X05")
                            || containsSqlState(expected, "42Y55"));
        }
    }

    private static boolean containsSqlState(SQLException failure, String sqlState) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (sqlState.equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoLifecycleMarkers(Path database) throws IOException {
        Path directory = database.resolve("delos_mvcc/ddl-lifecycle");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            assertEquals(0L, files.count());
        }
    }

    private static void assertProviderStateExists(Path database, long containerId)
            throws IOException {
        Path directory = database.resolve("delos_mvcc/inherited-store");
        String prefix = "conglomerate-0-" + containerId + ".";
        try (var files = Files.list(directory)) {
            assertTrue("expected provider state for " + prefix,
                    files.anyMatch(path -> path.getFileName().toString().startsWith(prefix)));
        }
    }

    private static void assertNoProviderState(Path database, long containerId)
            throws IOException {
        Path directory = database.resolve("delos_mvcc/inherited-store");
        if (!Files.isDirectory(directory)) {
            return;
        }
        String prefix = "conglomerate-0-" + containerId + ".";
        try (var files = Files.list(directory)) {
            assertFalse("unexpected provider state for " + prefix,
                    files.anyMatch(path -> path.getFileName().toString().startsWith(prefix)));
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
            if (args.length != 5) {
                System.err.println("expected database, log, metadata, operation, and failure point");
                System.exit(90);
            }
            Path database = Path.of(args[0]).toAbsolutePath().normalize();
            Path logSnapshot = Path.of(args[1]).toAbsolutePath().normalize();
            Path metadata = Path.of(args[2]).toAbsolutePath().normalize();
            String operation = args[3];
            String failurePoint = args[4];

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + database + ";create=true")) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "create table crash_witness_t (id int primary key, value int) using delos_mvcc");
                    if ("DROP".equals(operation)) {
                        statement.executeUpdate(
                                "create table crash_drop_t (id int primary key, value int) using delos_mvcc");
                        statement.executeUpdate("insert into crash_drop_t values (1, 10), (2, 20)");
                    }
                }
                long lifecycleContainerId;
                if ("DROP".equals(operation)) {
                    lifecycleContainerId = mvccContainerId(connection, "CRASH_DROP_T");
                } else {
                    lifecycleContainerId = -1L;
                }
                copyDirectory(database.resolve("log"), logSnapshot);
                installHalt(database, failurePoint);

                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    if ("CREATE".equals(operation)) {
                        statement.executeUpdate(
                                "create table crash_created_t (id int primary key, value int) using delos_mvcc");
                        lifecycleContainerId = mvccContainerId(connection, "CRASH_CREATED_T");
                    } else {
                        statement.executeUpdate("drop table crash_drop_t");
                    }
                    Files.writeString(metadata, Long.toString(lifecycleContainerId));
                    statement.executeUpdate("insert into crash_witness_t values (1, 10)");
                }
                connection.commit();
            }
            System.err.println("transactional DDL commit returned without the configured process halt");
            System.exit(91);
        }

        private static void installHalt(Path database, String failurePoint) throws Exception {
            Class<?> registry = Class.forName(
                    "io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccFailurePointRegistry");
            Method install = registry.getDeclaredMethod(
                    "installHaltForTesting",
                    Path.class,
                    String.class,
                    long.class,
                    int.class);
            install.setAccessible(true);
            install.invoke(null, database, failurePoint, 1L, HALT_STATUS);
        }
    }

    private record CrashResult(
            Path database,
            Path logSnapshot,
            Path metadata,
            long lifecycleContainerId) {
    }
}
