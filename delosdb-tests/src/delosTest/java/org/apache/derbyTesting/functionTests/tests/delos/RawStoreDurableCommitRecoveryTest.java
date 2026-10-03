/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import junit.framework.TestCase;

/** Process-crash proof: acknowledged mixed transactions survive with the coordinator disabled on recovery. */
public final class RawStoreDurableCommitRecoveryTest extends TestCase {
    private static final String COORDINATOR = "delosdb.experimental.rawStoreDurableCommit.enabled";
    private static final int WRITERS = 4;
    private static final int TRANSACTIONS = 32;
    private static final int ROWS_PER_TRANSACTION = 8;
    private static final int ROWS = WRITERS * TRANSACTIONS * ROWS_PER_TRANSACTION;
    private static final String[] TABLES = {"COMMIT_HEAP", "COMMIT_MVCC"};

    public void testAcknowledgedCommitsSurviveImmediateHalt() throws Exception {
        verifyCrash(false);
    }

    public void testCheckpointedUncommittedRowsAreUndoneAfterHalt() throws Exception {
        verifyCrash(true);
    }

    private static void verifyCrash(boolean checkpointUncommitted) throws Exception {
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "durable-commit-recovery-");
        Path database = root.resolve("database");
        Path writerLog = root.resolve("writer.log");
        runWorker("write", database, checkpointUncommitted, true, 93, writerLog);
        String output = Files.readString(writerLog, StandardCharsets.UTF_8);
        assertTrue("writer must prove it used the coordinator: " + output,
                Pattern.compile("DELOS_RAWSTORE_DURABLE_COMMIT\\|enabled=true[^\\r\\n]*\\|cohorts=[1-9][0-9]*")
                        .matcher(output).find());
        assertTrue("halt occurs only after every JDBC commit returned: " + output,
                output.contains("ACKNOWLEDGED_ROWS=" + ROWS));
        runWorker("recover", database, checkpointUncommitted, false, 0, root.resolve("recovery.log"));
        // Keep the database and both subprocess logs under the test working
        // directory for inspection. The existing Gradle fixture owns cleanup.
    }

    private static void runWorker(String mode, Path database, boolean checkpoint,
                                  boolean enabled, int exit, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(
                java, "-Xmx512m", "-D" + COORDINATOR + "=" + enabled,
                "-Ddelosdb.diagnostic.rawStoreDurableCommit=true",
                "-Ddelosdb.mvcc.rawStoreVerticalSlice.enabled=true",
                "-Dderby.storage.logSwitchInterval=1048576",
                "-Dderby.storage.checkpointInterval=1048576",
                "-cp", System.getProperty("java.class.path"),
                Worker.class.getName(), mode, database.toString(), Boolean.toString(checkpoint)));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue("subprocess timed out; see " + output, process.waitFor(120, TimeUnit.SECONDS));
            assertEquals("subprocess failed; output=" + Files.readString(output), exit, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    public static final class Worker {
        private Worker() { }

        public static void main(String[] args) throws Exception {
            Path database = Path.of(args[1]);
            if ("write".equals(args[0])) {
                writeAndHalt(database, Boolean.parseBoolean(args[2]));
            } else if ("recover".equals(args[0])) {
                recover(database);
            } else {
                throw new IllegalArgumentException("Unknown worker mode: " + args[0]);
            }
        }

        private static void writeAndHalt(Path database, boolean checkpointUncommitted) throws Exception {
            try (Connection setup = open(database, true); Statement sql = setup.createStatement()) {
                sql.executeUpdate("create table COMMIT_HEAP (id int primary key, payload varchar(512))");
                sql.executeUpdate("create table COMMIT_MVCC (id int primary key, payload varchar(512)) using delos_mvcc");
                sql.execute("call SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");
            }
            CountDownLatch start = new CountDownLatch(1);
            List<FutureTask<Void>> writers = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int id = writer;
                FutureTask<Void> task = new FutureTask<>(() -> {
                    writeTransactions(database, id, start);
                    return null;
                });
                writers.add(task);
                Thread thread = new Thread(task, "durable-commit-writer-" + writer);
                thread.setDaemon(true);
                thread.start();
            }
            start.countDown();
            for (FutureTask<Void> writer : writers) {
                writer.get(90, TimeUnit.SECONDS);
            }
            System.out.println("ACKNOWLEDGED_ROWS=" + ROWS);
            // No clean database shutdown and, in the immediate-halt case,
            // no post-workload checkpoint that could mask an early commit ACK.
            Connection unfinished = open(database, false);
            unfinished.setAutoCommit(false);
            insert(unfinished, -1);
            if (checkpointUncommitted) {
                try (Connection checkpoint = open(database, false);
                     Statement sql = checkpoint.createStatement()) {
                    sql.execute("call SYSCS_UTIL.SYSCS_CHECKPOINT_DATABASE()");
                }
            }
            System.out.flush();
            Runtime.getRuntime().halt(93);
        }

        private static void writeTransactions(Path database, int writer, CountDownLatch start) throws Exception {
            try (Connection connection = open(database, false)) {
                connection.setAutoCommit(false);
                start.await();
                for (int transaction = 0; transaction < TRANSACTIONS; transaction++) {
                    for (int row = 0; row < ROWS_PER_TRANSACTION; row++) {
                        int id = (writer * TRANSACTIONS + transaction) * ROWS_PER_TRANSACTION + row + 1;
                        insert(connection, id);
                    }
                    connection.commit();
                }
            }
        }

        private static void insert(Connection connection, int id) throws SQLException {
            for (String table : TABLES) {
                try (PreparedStatement insert = connection.prepareStatement("insert into " + table + " values (?,?)")) {
                    insert.setInt(1, id);
                    insert.setString(2, "v".repeat(512));
                    assertEquals(1, insert.executeUpdate());
                }
            }
        }

        private static void recover(Path database) throws Exception {
            assertFalse("recovery must not depend on the prototype", Boolean.getBoolean(COORDINATOR));
            try (Connection recovered = open(database, false)) {
                recovered.setAutoCommit(false);
                for (String table : TABLES) {
                    try (Statement sql = recovered.createStatement();
                         ResultSet rows = sql.executeQuery("select id, payload from " + table + " order by id")) {
                        for (int expected = 1; expected <= ROWS; expected++) {
                            assertTrue("missing committed row " + table + ":" + expected, rows.next());
                            assertEquals(expected, rows.getInt(1));
                            assertEquals("v".repeat(512), rows.getString(2));
                        }
                        assertFalse("uncommitted or duplicate rows survived: " + table, rows.next());
                    }
                }
                recovered.commit();
                try {
                    insert(recovered, 1);
                    fail("recovered PK must still reject duplicates");
                } catch (SQLException expected) {
                    assertEquals("23505", expected.getSQLState());
                    recovered.rollback();
                }
                insert(recovered, ROWS + 1);
                recovered.commit();
            }
            shutdown(database);
            try (Connection reopened = open(database, false); Statement sql = reopened.createStatement()) {
                for (String table : TABLES) {
                    try (ResultSet rows = sql.executeQuery("select count(*) from " + table)) {
                        assertTrue(rows.next());
                        assertEquals(ROWS + 1, rows.getInt(1));
                    }
                }
            }
            shutdown(database);
            System.out.println("DURABLE_COMMIT_RECOVERY_OK rows=" + ROWS);
        }

        private static Connection open(Path database, boolean create) throws SQLException {
            return DriverManager.getConnection("jdbc:derby:" + database + (create ? ";create=true" : ""));
        }

        private static void shutdown(Path database) throws SQLException {
            try {
                DriverManager.getConnection("jdbc:derby:" + database + ";shutdown=true");
                fail("expected Derby shutdown acknowledgement");
            } catch (SQLException shutdown) {
                assertEquals("08006", shutdown.getSQLState());
            }
        }
    }
}
