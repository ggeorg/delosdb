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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.derby.impl.store.access.btree.InsertRootRoutingTestSupport;

/**
 * B-tree inserts route through the root routing snapshot instead of latching
 * the root. Concurrent splits must still place every key in the leaf that
 * covers it, and a BTree instance left behind by a conglomerate cache
 * eviction must not keep routing on separators the root no longer has.
 */
public final class InsertRootRoutingSnapshotTest extends MvccSqlTestSupport {

    private static final int WRITERS = 8;
    private static final int ROWS_PER_WRITER = 1500;

    /** Wide keys give small fanout, so the root changes often. */
    private static final String WIDE_TABLE = "create table rr_t (id int not null primary key,"
            + " w char(200) not null)";

    public void testConcurrentInsertsKeepIndexOrderAcrossSplits() throws Exception {
        String databaseName = databaseName("insert-root-routing-db");
        try (Connection setup = openDatabase(databaseName, true)) {
            executeUpdate(setup, WIDE_TABLE);
            executeUpdate(setup, "create index rr_wide on rr_t(w)");

            long hitsBefore = InsertRootRoutingTestSupport.hits();
            long fallbacksBefore = InsertRootRoutingTestSupport.fallbacks();
            CyclicBarrier start = new CyclicBarrier(WRITERS);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int lane = writer;
                Thread thread = new Thread(() -> {
                    try (Connection connection = openDatabase(databaseName, false)) {
                        connection.setAutoCommit(false);
                        List<Integer> keys = new ArrayList<>();
                        for (int i = 0; i < ROWS_PER_WRITER; i++) {
                            keys.add(i * WRITERS + lane);
                        }
                        Collections.shuffle(keys, new Random(lane));
                        start.await(30, TimeUnit.SECONDS);
                        try (PreparedStatement insert = connection.prepareStatement(
                                "insert into rr_t values (?, ?)")) {
                            int inBatch = 0;
                            for (int key : keys) {
                                insert.setInt(1, key);
                                insert.setString(2, wide(key));
                                insert.executeUpdate();
                                if (++inBatch == 50) {
                                    connection.commit();
                                    inBatch = 0;
                                }
                            }
                        }
                        connection.commit();
                        // A rolled-back batch interleaved with the other writers.
                        // Logical undo descends through its own BTree instance
                        // once the root page has been reloaded.
                        try (PreparedStatement insert = connection.prepareStatement(
                                "insert into rr_t values (?, ?)")) {
                            for (int i = 0; i < 100; i++) {
                                int key = 1_000_000 + i * WRITERS + lane;
                                insert.setInt(1, key);
                                insert.setString(2, wide(key));
                                insert.executeUpdate();
                            }
                        }
                        connection.rollback();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                }, "rr-writer-" + writer);
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                thread.join(TimeUnit.MINUTES.toMillis(2));
                if (thread.isAlive()) {
                    fail(thread.getName() + " did not finish\n" + threadDump());
                }
            }
            if (failure.get() != null) {
                throw new AssertionError("writer failed", failure.get());
            }

            assertIndexHoldsExactly(setup, WRITERS * ROWS_PER_WRITER);
            assertTrue("inserts must route through the root snapshot",
                    InsertRootRoutingTestSupport.hits() > hitsBefore);
            assertTrue("root changes must send inserts to the latched descent",
                    InsertRootRoutingTestSupport.fallbacks() > fallbacksBefore);
        } finally {
            shutdownDatabase(databaseName);
        }
    }

    private static final int SPACING = 1000;
    private static final int SETUP_ROWS = 60;
    private static final int CACHE_PRESSURE_TABLES = 400;

    /**
     * A compiled plan keeps the BTree instance it was compiled against, and
     * the instance is cached on the root page's control row. Once the root
     * page and the conglomerate cache entry are both evicted (here by cache
     * pressure from other tables), a plan compiled afterwards gets a second
     * instance for the same index. Each instance's latched descent
     * takes over the root page's snapshot owner, so a page change must also
     * invalidate the instance that lost ownership. Otherwise that instance
     * keeps routing on separators the root no longer has: an insert lands on a
     * full leaf that no longer covers its key, the split fixes a different
     * leaf, and the retry returns to the same full leaf forever.
     */
    public void testStaleConglomerateInstanceDoesNotRouteInserts() throws Exception {
        // A stale route can make an insert loop forever, so fail on a timeout.
        String databaseName = databaseName("insert-root-routing-stale-db");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread body = new Thread(() -> {
            try {
                staleInstanceScenario(databaseName);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "rr-stale-instance");
        body.setDaemon(true);
        body.start();
        body.join(TimeUnit.MINUTES.toMillis(3));
        if (body.isAlive()) {
            fail("insert through the second BTree instance did not finish\n" + threadDump());
        }
        try {
            if (failure.get() instanceof Exception e) {
                throw e;
            }
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        } finally {
            shutdownDatabase(databaseName);
        }
    }

    private static void staleInstanceScenario(String databaseName) throws Exception {
        try (SystemPropertyScope smallCache = setSystemProperty("derby.storage.pageCacheSize", "40");
             Connection connection = openDatabase(databaseName, true)) {
            executeUpdate(connection, WIDE_TABLE);
            executeUpdate(connection, "create index rr_wide on rr_t(w)");
            executeUpdate(connection, "create table rr_filler (id int, filler varchar(1000))");
            try (PreparedStatement filler = connection.prepareStatement(
                    "insert into rr_filler values (?, ?)")) {
                String pad = String.format("%1000s", "x");
                for (int i = 0; i < 1000; i++) {
                    filler.setInt(1, i);
                    filler.setString(2, pad);
                    filler.executeUpdate();
                }
            }

            List<Integer> expected = new ArrayList<>();
            try (PreparedStatement oldPlan = connection.prepareStatement(
                    "insert into rr_t values (?, ?)")) {
                for (int i = 0; i < SETUP_ROWS; i++) {
                    insertWide(oldPlan, i * SPACING, expected);
                }

                // Push the index out of the conglomerate cache (at most 300
                // entries) by opening more tables, then out of the page cache.
                connection.setAutoCommit(false);
                try (Statement ddl = connection.createStatement()) {
                    for (int i = 0; i < CACHE_PRESSURE_TABLES; i++) {
                        ddl.executeUpdate("create table rr_pressure_" + i + " (id int)");
                    }
                }
                connection.commit();
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    for (int i = 0; i < CACHE_PRESSURE_TABLES; i++) {
                        statement.executeUpdate("insert into rr_pressure_" + i + " values (" + i + ")");
                    }
                }
                executeStatement(connection, "call syscs_util.syscs_checkpoint_database()");
                try (Statement scan = connection.createStatement();
                     ResultSet rows = scan.executeQuery("select count(*) from rr_filler")) {
                    assertTrue(rows.next());
                }

                // Different SQL text, so this plan compiles against a new instance.
                try (PreparedStatement newPlan = connection.prepareStatement(
                        "insert into rr_t (id, w) values (?, ?)")) {
                    insertWide(newPlan, 45 * SPACING + 1, expected);
                    // The old plan's latched descent takes the owner back.
                    insertWide(oldPlan, 15 * SPACING + 1, expected);
                    // Split the leaf covering 30000-31000 through the new plan.
                    for (int key = 30 * SPACING + 5; key < 31 * SPACING; key += 5) {
                        insertWide(newPlan, key, expected);
                    }
                }
            }

            assertIndexHoldsExactly(connection, expected.size());
        }
    }

    private static void insertWide(PreparedStatement insert, int key, List<Integer> inserted)
            throws SQLException {
        insert.setInt(1, key);
        insert.setString(2, wide(key));
        insert.executeUpdate();
        inserted.add(key);
    }

    private static String threadDump() {
        StringBuilder dump = new StringBuilder();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            dump.append('"').append(entry.getKey().getName()).append("\" ")
                    .append(entry.getKey().getState()).append('\n');
            for (StackTraceElement frame : entry.getValue()) {
                dump.append("    at ").append(frame).append('\n');
            }
        }
        return dump.toString();
    }

    private static String wide(int key) {
        return String.format("%08d", key);
    }

    /**
     * Every key once, in strictly increasing order along the leaf chain, with
     * each key found by a point lookup from the root. A key placed in a leaf
     * that does not cover it breaks the order or the lookup.
     */
    private static void assertIndexHoldsExactly(Connection connection, int expectedRows)
            throws SQLException {
        List<String> scanned = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select w from rr_t --DERBY-PROPERTIES index = RR_WIDE\n")) {
            while (rows.next()) {
                scanned.add(rows.getString(1));
            }
        }
        assertEquals(expectedRows, scanned.size());
        for (int i = 1; i < scanned.size(); i++) {
            assertTrue("index order broken at " + scanned.get(i - 1) + " / " + scanned.get(i),
                    scanned.get(i - 1).compareTo(scanned.get(i)) < 0);
        }
        try (PreparedStatement lookup = connection.prepareStatement(
                "select count(*) from rr_t --DERBY-PROPERTIES index = RR_WIDE\n where w = ?")) {
            for (String key : scanned) {
                lookup.setString(1, key);
                try (ResultSet rows = lookup.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("index lookup of " + key.trim(), 1, rows.getInt(1));
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet check = statement.executeQuery(
                     "values syscs_util.syscs_check_table('APP', 'RR_T')")) {
            assertTrue(check.next());
            assertEquals(1, check.getInt(1));
        }
    }
}
