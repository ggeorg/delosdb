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
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.impl.store.raw.data.CachedPageValidityTestSupport;

/**
 * Cached page validity: a page fetch that finds the page in the page cache
 * skips the allocation-cache check and relies on the page's own status after
 * latching. Concurrent page allocation, post-commit page deallocation, page
 * reuse and an in-place compress that moves and truncates pages race with
 * uncommitted-read scans and point lookups; readers must only ever see
 * well-formed rows, and committed contents, consistency checks and restart
 * must be unchanged.
 */
public final class CachedPageValidityTest extends MvccSqlTestSupport {
    private static final int WRITERS = 4;
    private static final int READERS = 4;
    private static final int ROUNDS = 60;
    private static final int BATCH = 40;
    private static final int ID_RANGE = ROUNDS * BATCH;

    public void testDeallocatedCachedPageIsNotReturned() throws Exception {
        String databaseName = databaseName("cached-page-validity-dealloc-db");
        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            try {
                String failure = CachedPageValidityTestSupport.verifyDeallocatedCachedPage(connection);
                assertNull(failure, failure);
            } finally {
                connection.rollback();
            }
        } finally {
            shutdownDatabase(databaseName);
        }
    }

    public void testConcurrentAllocationDeallocationAndCompress() throws Exception {
        String databaseName = databaseName("cached-page-validity-db");
        try (Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, "create table pv_t (id int not null primary key,"
                    + " writer int not null, payload varchar(1000) not null)");
        }

        CyclicBarrier start = new CyclicBarrier(WRITERS + READERS);
        AtomicBoolean writersDone = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<TreeSet<Integer>> live = new ArrayList<>();
        List<Thread> writers = new ArrayList<>();
        List<Thread> readers = new ArrayList<>();

        for (int writer = 0; writer < WRITERS; writer++) {
            int writerId = writer;
            TreeSet<Integer> ids = new TreeSet<>();
            live.add(ids);
            writers.add(start(() -> {
                try (Connection connection = openDatabase(databaseName, false);
                     PreparedStatement insert = connection.prepareStatement(
                             "insert into pv_t values (?, ?, ?)");
                     PreparedStatement delete = connection.prepareStatement(
                             "delete from pv_t where id = ?")) {
                    connection.setAutoCommit(false);
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        for (int row = 0; row < BATCH; row++) {
                            int id = writerId * ID_RANGE + round * BATCH + row + 1;
                            insert.setInt(1, id);
                            insert.setInt(2, writerId);
                            insert.setString(3, payload(id));
                            insert.executeUpdate();
                            ids.add(id);
                        }
                        connection.commit();
                        // Empty most of an older batch so post-commit work
                        // deallocates its pages and later batches reuse them.
                        if (round >= 2) {
                            int first = writerId * ID_RANGE + (round - 2) * BATCH + 1;
                            for (int id = first; id < first + BATCH; id++) {
                                if (id % 4 != 0) {
                                    delete.setInt(1, id);
                                    assertEquals(1, delete.executeUpdate());
                                    ids.remove(id);
                                }
                            }
                            connection.commit();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "cached-page-validity-writer-" + writer));
        }

        for (int reader = 0; reader < READERS; reader++) {
            readers.add(start(() -> {
                try (Connection connection = openDatabase(databaseName, false);
                     PreparedStatement lookup = connection.prepareStatement(
                             "select id, writer, payload from pv_t where id = ?");
                     Statement scan = connection.createStatement()) {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    while (!writersDone.get() && failure.get() == null) {
                        lookup.setInt(1, 1 + random.nextInt(WRITERS * ID_RANGE));
                        try (ResultSet rows = lookup.executeQuery()) {
                            assertWellFormed(rows);
                        }
                        if (random.nextInt(8) == 0) {
                            try (ResultSet rows = scan.executeQuery(
                                    "select id, writer, payload from pv_t")) {
                                assertWellFormed(rows);
                            }
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "cached-page-validity-reader-" + reader));
        }

        for (Thread thread : writers) {
            thread.join();
        }
        if (failure.get() == null) {
            // Defragment and truncate while readers still fetch pages.
            try (Connection connection = openDatabase(databaseName, false)) {
                executeStatement(connection,
                        "call syscs_util.syscs_inplace_compress_table('APP', 'PV_T', 1, 1, 1)");
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }
        writersDone.set(true);
        for (Thread thread : readers) {
            thread.join();
        }
        if (failure.get() != null) {
            throw new AssertionError("concurrent page validity worker failed", failure.get());
        }

        TreeSet<Integer> expected = new TreeSet<>();
        live.forEach(expected::addAll);
        assertContents(databaseName, expected);
        shutdownDatabase(databaseName);
        assertContents(databaseName, expected);
        shutdownDatabase(databaseName);
    }

    private static Thread start(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.start();
        return thread;
    }

    private static void assertWellFormed(ResultSet rows) throws SQLException {
        while (rows.next()) {
            int id = rows.getInt(1);
            assertEquals((id - 1) / ID_RANGE, rows.getInt(2));
            assertEquals(payload(id), rows.getString(3));
        }
    }

    private static void assertContents(String databaseName, TreeSet<Integer> expected)
            throws SQLException {
        try (Connection connection = openDatabase(databaseName, false);
             Statement statement = connection.createStatement()) {
            TreeSet<Integer> actual = new TreeSet<>();
            try (ResultSet rows = statement.executeQuery("select id, writer, payload from pv_t")) {
                while (rows.next()) {
                    int id = rows.getInt(1);
                    assertEquals((id - 1) / ID_RANGE, rows.getInt(2));
                    assertEquals(payload(id), rows.getString(3));
                    assertTrue("duplicate row " + id, actual.add(id));
                }
            }
            assertEquals(expected, actual);
            try (ResultSet check = statement.executeQuery(
                    "values syscs_util.syscs_check_table('APP', 'PV_T')")) {
                assertTrue(check.next());
                assertEquals(1, check.getInt(1));
            }
        }
    }

    private static String payload(int id) {
        String base = "row-" + id + "-";
        return base + "p".repeat(900 - base.length());
    }
}
