/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.impl.store.raw.data.HeapInsertLaneTestSupport;

/**
 * Per-writer heap insert lanes: concurrent inserters contending on the shared
 * insert page switch to their own target pages. Lanes change only in-memory
 * page choice, so committed contents, rollback, clean restart, crash recovery
 * and consistency checks must be unchanged, and a single writer keeps Derby's
 * row placement without ever switching to lanes.
 */
public final class HeapInsertLaneTest extends MvccSqlTestSupport {
    private static final int WRITERS = 8;
    private static final int ROWS_PER_WRITER = 4000;
    private static final int BATCH = 50;

    public void testSingleWriterKeepsInsertOrder() throws Exception {
        String databaseName = databaseName("heap-insert-lane-single-db");
        long switchesBefore = HeapInsertLaneTestSupport.laneSwitches();
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table lane_single_t (id int not null, payload varchar(200))");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into lane_single_t values (?, ?)")) {
                for (int id = 1; id <= 3000; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, payload(id));
                    insert.executeUpdate();
                }
            }
            connection.commit();

            // An unordered heap scan returns a lone writer's rows in insert order.
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select id from lane_single_t")) {
                int expected = 1;
                while (rows.next()) {
                    assertEquals(expected++, rows.getInt(1));
                }
                assertEquals(3001, expected);
            }
            connection.commit();
            assertEquals("a lone writer must not switch to insert lanes",
                    switchesBefore, HeapInsertLaneTestSupport.laneSwitches());
        } finally {
            shutdownDatabase(databaseName);
        }
    }

    public void testConcurrentBareInsertsCommitRollbackAndRestart() throws Exception {
        verifyConcurrentInserts("heap-insert-lane-bare-db", "lane_bare_t", "id int not null");
    }

    public void testConcurrentPrimaryKeyInsertsCommitRollbackAndRestart() throws Exception {
        verifyConcurrentInserts("heap-insert-lane-pk-db", "lane_pk_t", "id int not null primary key");
    }

    private static void verifyConcurrentInserts(String name, String table, String idColumn)
            throws Exception {
        String databaseName = databaseName(name);
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table " + table + " (" + idColumn
                    + ", writer int not null, payload varchar(200) not null)");
        }

        // Every writer rolls back every third batch; only committed batches survive.
        long switchesBefore = HeapInsertLaneTestSupport.laneSwitches();
        CyclicBarrier start = new CyclicBarrier(WRITERS);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int writer = 0; writer < WRITERS; writer++) {
            int writerId = writer;
            Thread thread = new Thread(() -> {
                try (Connection connection = openDatabase(databaseName, false);
                     PreparedStatement insert = connection.prepareStatement(
                             "insert into " + table + " values (?, ?, ?)")) {
                    connection.setAutoCommit(false);
                    start.await();
                    for (int row = 0; row < ROWS_PER_WRITER; row++) {
                        int id = writerId * ROWS_PER_WRITER + row + 1;
                        insert.setInt(1, id);
                        insert.setInt(2, writerId);
                        insert.setString(3, payload(id));
                        insert.executeUpdate();
                        if ((row + 1) % BATCH == 0) {
                            if (rolledBack(row / BATCH)) {
                                connection.rollback();
                            } else {
                                connection.commit();
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "heap-insert-lane-writer-" + writer);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent writer failed", failure.get());
        }
        assertTrue("concurrent writers must switch the table to insert lanes",
                HeapInsertLaneTestSupport.laneSwitches() > switchesBefore);

        assertCommittedRows(databaseName, table, false);
        shutdownDatabase(databaseName);
        // Reboot runs recovery over the interleaved lane pages.
        assertCommittedRows(databaseName, table, false);
        shutdownDatabase(databaseName);
    }

    public void testCrashRecoveryAfterConcurrentLaneInserts() throws Exception {
        String databaseName = Path.of(databaseName("heap-insert-lane-crash-db"))
                .toAbsolutePath().normalize().toString();
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table lane_crash_t (id int not null primary key,"
                    + " writer int not null, payload varchar(200) not null)");
        }
        shutdownDatabase(databaseName);

        // The worker commits and rolls back across lanes, leaves one batch per
        // writer in flight, and halts without a clean shutdown or checkpoint.
        Process worker = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                CrashWorker.class.getName(), databaseName)
                .redirectErrorStream(true)
                .start();
        if (!worker.waitFor(90, TimeUnit.SECONDS)) {
            worker.destroyForcibly();
            fail("heap insert lane crash worker did not terminate");
        }
        String output = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt with transactions in flight; output=" + output,
                77, worker.exitValue());

        // Reboot replays the interleaved lane pages and undoes the in-flight batches.
        assertCommittedRows(databaseName, "lane_crash_t", true);
        shutdownDatabase(databaseName);
    }

    /** Separate JVM: concurrent lane inserts, then halt mid-transaction. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            String url = "jdbc:derby:" + args[0];
            CyclicBarrier inFlight = new CyclicBarrier(WRITERS + 1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int writerId = writer;
                Thread thread = new Thread(() -> {
                    try {
                        Connection connection = DriverManager.getConnection(url);
                        PreparedStatement insert = connection.prepareStatement(
                                "insert into lane_crash_t values (?, ?, ?)");
                        connection.setAutoCommit(false);
                        for (int row = 0; row < ROWS_PER_WRITER; row++) {
                            int id = writerId * ROWS_PER_WRITER + row + 1;
                            insert.setInt(1, id);
                            insert.setInt(2, writerId);
                            insert.setString(3, payload(id));
                            insert.executeUpdate();
                            boolean batchEnd = (row + 1) % BATCH == 0;
                            if (batchEnd && row + 1 == ROWS_PER_WRITER) {
                                // last batch stays uncommitted: rolled back by recovery
                                break;
                            }
                            if (batchEnd) {
                                if (rolledBack(row / BATCH)) {
                                    connection.rollback();
                                } else {
                                    connection.commit();
                                }
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    } finally {
                        try {
                            inFlight.await();
                        } catch (Exception ignored) {
                        }
                    }
                }, "heap-insert-lane-crash-writer-" + writer);
                thread.setDaemon(true);
                thread.start();
            }
            inFlight.await();
            if (failure.get() != null) {
                failure.get().printStackTrace();
                System.exit(78);
            }
            Runtime.getRuntime().halt(77);
        }
    }

    private static void assertCommittedRows(String databaseName, String table, boolean lastBatchInFlight)
            throws SQLException {
        int batches = ROWS_PER_WRITER / BATCH;
        int committedBatches = 0;
        for (int batch = 0; batch < batches; batch++) {
            if (committed(batch, lastBatchInFlight)) {
                committedBatches++;
            }
        }
        long expectedRows = (long) WRITERS * committedBatches * BATCH;

        try (Connection connection = openDatabase(databaseName, false);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "select count(*), count(distinct id) from " + table)) {
                assertTrue(rows.next());
                assertEquals(expectedRows, rows.getLong(1));
                assertEquals(expectedRows, rows.getLong(2));
            }
            try (ResultSet rows = statement.executeQuery("select id, writer, payload from " + table)) {
                while (rows.next()) {
                    int id = rows.getInt(1);
                    int offset = (id - 1) % ROWS_PER_WRITER;
                    assertEquals((id - 1) / ROWS_PER_WRITER, rows.getInt(2));
                    assertTrue("uncommitted row " + id + " is visible",
                            committed(offset / BATCH, lastBatchInFlight));
                    assertEquals(payload(id), rows.getString(3));
                }
            }
            try (ResultSet check = statement.executeQuery(
                    "values syscs_util.syscs_check_table('APP', '" + table.toUpperCase() + "')")) {
                assertTrue(check.next());
                assertEquals(1, check.getInt(1));
            }
        }
    }

    private static boolean committed(int batch, boolean lastBatchInFlight) {
        boolean inFlight = lastBatchInFlight && batch == ROWS_PER_WRITER / BATCH - 1;
        return !rolledBack(batch) && !inFlight;
    }

    private static boolean rolledBack(int batch) {
        return batch % 3 == 2;
    }

    private static String payload(int id) {
        String base = "row-" + id + "-";
        return base + "x".repeat(128 - base.length());
    }
}
