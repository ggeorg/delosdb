/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-writer heap insert lanes: concurrent inserters contending on the shared
 * insert page switch to their own target pages. Lanes change only in-memory
 * page choice, so committed contents, rollback, restart and consistency
 * checks must be unchanged, and a single writer keeps Derby's row placement.
 */
public final class HeapInsertLaneTest extends MvccSqlTestSupport {
    private static final int WRITERS = 8;
    private static final int ROWS_PER_WRITER = 4000;
    private static final int BATCH = 50;

    public void testSingleWriterKeepsInsertOrder() throws Exception {
        String databaseName = databaseName("heap-insert-lane-single-db");
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

        assertCommittedRows(databaseName, table);
        shutdownDatabase(databaseName);
        // Reboot runs recovery over the interleaved lane pages.
        assertCommittedRows(databaseName, table);
        shutdownDatabase(databaseName);
    }

    private static void assertCommittedRows(String databaseName, String table) throws SQLException {
        int batches = ROWS_PER_WRITER / BATCH;
        int committedBatches = 0;
        for (int batch = 0; batch < batches; batch++) {
            if (!rolledBack(batch)) {
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
                    assertFalse("rolled-back row " + id + " is visible", rolledBack(offset / BATCH));
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

    private static boolean rolledBack(int batch) {
        return batch % 3 == 2;
    }

    private static String payload(int id) {
        String base = "row-" + id + "-";
        return base + "x".repeat(128 - base.length());
    }
}
