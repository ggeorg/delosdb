/* Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import io.github.ggeorg.delosdb.benchmark.jdbc.RawStoreStabilityDiagnostics;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.derby.impl.store.raw.data.RawStoreOccupancy;

/** Qualifies actual insert ownership and disjoint nested accounting in separate off/on JVMs. */
public final class RawStoreOccupancyTest extends MvccSqlTestSupport {
    public void testConcurrentInsertCommitRollbackAndAccounting() throws Exception {
        String url = "jdbc:derby:memory:occupancy;create=true";
        try (Connection setup = DriverManager.getConnection(url)) {
            try (Statement statement = setup.createStatement()) {
                statement.executeUpdate("create table O (id int primary key, payload varchar(128))");
            }
            RawStoreOccupancy.reset();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread[] workers = new Thread[4];
            for (int client = 0; client < workers.length; client++) {
                int base = client * 10000;
                workers[client] = new Thread(() -> insert(url, base, start, failure));
                workers[client].start();
            }
            start.countDown();
            for (Thread worker : workers) {
                worker.join(30000);
                assertFalse("insert worker did not complete", worker.isAlive());
            }
            if (failure.get() != null) throw new AssertionError("concurrent insert failed", failure.get());
            try (Statement statement = setup.createStatement();
                 ResultSet rows = statement.executeQuery("select count(*) from O")) {
                assertTrue(rows.next());
                assertEquals(1600, rows.getInt(1));
            }
            String[] report = RawStoreOccupancy.snapshot();
            if (!RawStoreOccupancy.ENABLED) {
                assertEquals(0, report.length);
            } else {
                assertLedger(report);
                RawStoreOccupancy.reset();
                assertEquals("reset retained completed acquisitions", 1, RawStoreOccupancy.snapshot().length);
            }
        }
    }

    public void testCoupledOwnershipReentrancyAndMonitorWait() throws Exception {
        if (!RawStoreOccupancy.ENABLED || RawStoreOccupancy.SAMPLE_EVERY != 1) {
            if (!RawStoreOccupancy.ENABLED) assertEquals(0, RawStoreOccupancy.snapshot().length);
            return;
        }
        RawStoreOccupancy.reset();
        Object root = new Object();
        Object leaf = new Object();
        Object monitor = new Object();
        RawStoreOccupancy.Resource rootResource = RawStoreOccupancy.resource("TEST_ROOT");
        RawStoreOccupancy.Resource leafResource = RawStoreOccupancy.resource("TEST_LEAF");
        RawStoreOccupancy.Resource log = RawStoreOccupancy.resource("TEST_LOG");
        RawStoreOccupancy.Resource alloc = RawStoreOccupancy.resource("TEST_ALLOC");
        int role = RawStoreOccupancy.requestRole(RawStoreOccupancy.ROOT);
        long attempt = RawStoreOccupancy.pageWait();
        RawStoreOccupancy.pageAcquired(root, rootResource, attempt);
        attempt = RawStoreOccupancy.pageWait();
        Thread.sleep(2);
        RawStoreOccupancy.pageAcquired(leaf, leafResource, attempt);
        RawStoreOccupancy.classify(leaf, RawStoreOccupancy.LEAF);
        long allocWait = RawStoreOccupancy.allocWait();
        long allocHold = RawStoreOccupancy.allocAcquired(alloc, allocWait);
        long nestedWait = RawStoreOccupancy.allocWait();
        long nestedHold = RawStoreOccupancy.allocAcquired(alloc, nestedWait);
        Thread.sleep(2);
        RawStoreOccupancy.allocReleased(alloc, nestedHold);
        RawStoreOccupancy.allocReleased(alloc, allocHold);
        RawStoreOccupancy.logWait(log);
        synchronized (monitor) {
            RawStoreOccupancy.logAcquired();
            RawStoreOccupancy.logWait(log);
            RawStoreOccupancy.logAcquired();
            int phase = RawStoreOccupancy.phase(RawStoreOccupancy.SWITCH_BUFFER);
            int nestedPhase = RawStoreOccupancy.phase(RawStoreOccupancy.DIRTY_FLUSH);
            Thread.sleep(2);
            RawStoreOccupancy.phase(nestedPhase);
            RawStoreOccupancy.phase(phase);
            RawStoreOccupancy.logSuspend();
            try {
                monitor.wait(10);
            } finally {
                RawStoreOccupancy.logResume();
            }
            RawStoreOccupancy.logReleased();
            RawStoreOccupancy.logReleased();
            // The real wait must be charged to the page, never to log occupancy.
            RawStoreOccupancy.pageReleased(root); // coupling may release parent first
            RawStoreOccupancy.pageReleased(leaf);
            String[] report = RawStoreOccupancy.snapshot();
            Map<String, String[]> rows = new HashMap<>();
            for (int i = 1; i < report.length; i++) {
                String[] values = report[i].split(",");
                rows.put(values[0], values);
            }
            Map<String, Integer> columns = new HashMap<>();
            String[] header = report[0].split(",");
            for (int i = 0; i < header.length; i++) columns.put(header[i], i);
            assertEquals("reentrant log counted twice", "1", rows.get("TEST_LOG")[columns.get("acquires")]);
            assertEquals("reentrant allocation counted twice", "1", rows.get("TEST_ALLOC")[columns.get("acquires")]);
            for (String name : List.of("TEST_ROOT", "TEST_LEAF")) {
                String[] values = rows.get(name);
                long nested = 0;
                for (int i = columns.get("self_ns"); i <= columns.get("switch_file_ns"); i++) {
                    nested += Long.parseLong(values[i]);
                }
                assertEquals("outer elapsed time differs from partition", nested,
                        Long.parseLong(values[columns.get("hold_ns")]));
                assertEquals("Object.wait must be log wait under both outer pages",
                        rows.get("TEST_LOG")[columns.get("wait_ns")],
                        values[columns.get("log_wait_ns")]);
            }
            assertTrue("dirty flush was not separately attributed",
                    Long.parseLong(rows.get("TEST_LOG")[columns.get("dirty_flush_ns")]) > 0);
        }
        RawStoreOccupancy.requestRole(role);
        RawStoreOccupancy.reset();
    }

    public void testExactMultipleThreadAggregation() throws Exception {
        if (!RawStoreOccupancy.ENABLED || RawStoreOccupancy.SAMPLE_EVERY != 1) return;
        RawStoreOccupancy.reset();
        Object monitor = new Object();
        RawStoreOccupancy.Resource resource = RawStoreOccupancy.resource("TEST_SHARED");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[4];
        for (int client = 0; client < threads.length; client++) {
            threads[client] = new Thread(() -> {
                try {
                    start.await();
                    for (int acquisition = 0; acquisition < 100; acquisition++) {
                        long attempt = RawStoreOccupancy.pageWait();
                        synchronized (monitor) {
                            RawStoreOccupancy.pageAcquired(monitor, resource, attempt);
                            RawStoreOccupancy.pageReleased(monitor);
                        }
                    }
                } catch (Exception problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            threads[client].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(30000);
            assertFalse("aggregation worker did not finish", thread.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        String[] row = Arrays.stream(RawStoreOccupancy.snapshot())
                .filter(line -> line.startsWith("TEST_SHARED,"))
                .findFirst().orElseThrow().split(",");
        assertEquals("concurrent acquisitions were lost", 400L, Long.parseLong(row[2]));
        RawStoreOccupancy.reset();
    }

    public void testSampledEpisodesKeepAllNestedAcquisitionsTogether() throws Exception {
        if (!RawStoreOccupancy.ENABLED || RawStoreOccupancy.SAMPLE_EVERY == 1) return;
        RawStoreOccupancy.reset();
        Object root = new Object();
        Object leaf = new Object();
        RawStoreOccupancy.Resource parent = RawStoreOccupancy.resource("SAMPLED_PARENT");
        RawStoreOccupancy.Resource child = RawStoreOccupancy.resource("SAMPLED_CHILD");
        RawStoreOccupancy.Resource log = RawStoreOccupancy.resource("SAMPLED_LOG");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] workers = new Thread[4];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int episode = 0; episode < 10000; episode++) {
                        long attempt = RawStoreOccupancy.pageWait();
                        synchronized (root) {
                            RawStoreOccupancy.pageAcquired(root, parent, attempt);
                            attempt = RawStoreOccupancy.pageWait();
                            RawStoreOccupancy.pageAcquired(leaf, child, attempt);
                            RawStoreOccupancy.logWait(log);
                            RawStoreOccupancy.logAcquired();
                            int phase = RawStoreOccupancy.phase(RawStoreOccupancy.DIRTY_FLUSH);
                            RawStoreOccupancy.phase(phase);
                            RawStoreOccupancy.logReleased();
                            RawStoreOccupancy.pageReleased(root);
                            RawStoreOccupancy.pageReleased(leaf);
                        }
                    }
                } catch (Exception problem) {
                    failure.compareAndSet(null, problem);
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30000);
            assertFalse("sampling worker stalled", worker.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
        Map<String, String[]> rows = new HashMap<>();
        String[] report = RawStoreOccupancy.snapshot();
        for (int i = 1; i < report.length; i++) {
            String[] values = report[i].split(",");
            rows.put(values[0], values);
        }
        String[] columns = report[0].split(",");
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.length; i++) index.put(columns[i], i);
        long samples = Long.parseLong(rows.get("SAMPLED_PARENT")[index.get("acquires")]);
        assertTrue("sampler selected too few episodes", samples > 40000 / (RawStoreOccupancy.SAMPLE_EVERY * 2));
        assertTrue("sampler did not skip episodes", samples < 40000 / 2);
        for (String name : List.of("SAMPLED_PARENT", "SAMPLED_CHILD", "SAMPLED_LOG")) {
            String[] row = rows.get(name);
            assertEquals("nested acquisition sampled independently", samples,
                    Long.parseLong(row[index.get("acquires")]));
            assertEquals(RawStoreOccupancy.SAMPLE_EVERY, Integer.parseInt(row[index.get("sample_every")]));
            long partition = 0L;
            for (int i = index.get("self_ns"); i <= index.get("switch_file_ns"); i++) {
                long nanos = Long.parseLong(row[i]);
                assertTrue("negative sampled interval", nanos >= 0L);
                partition += nanos;
            }
            assertEquals("partial sampled hold", partition, Long.parseLong(row[index.get("hold_ns")]));
        }
        RawStoreOccupancy.reset();
    }

    public void testQuietHostGuardRejectsPressureAndMissingEvidence() {
        long room = 3L * 1024 * 1024 * 1024;
        assertTrue(RawStoreStabilityDiagnostics.quietEnough(0.1, 2.0, 14, room));
        assertFalse(RawStoreStabilityDiagnostics.quietEnough(0.6, 2.0, 14, room));
        assertFalse(RawStoreStabilityDiagnostics.quietEnough(0.1, 12.0, 14, room));
        assertFalse(RawStoreStabilityDiagnostics.quietEnough(0.1, 2.0, 14, 120L * 1024 * 1024));
        assertFalse(RawStoreStabilityDiagnostics.quietEnough(-1.0, 2.0, 14, room));
        assertFalse(RawStoreStabilityDiagnostics.quietEnough(0.1, 2.0, 14, -1L));
    }

    public void testJvmStabilityWindowsRespectFlagsAndReset() throws Exception {
        String property = "delosdb.benchmark.crossEngineConcurrency.rawStoreStabilityDiagnostics";
        Path directory = Files.createTempDirectory(Path.of("."), "stability-window-");
        List<String> command = new ArrayList<>();
        try (SystemPropertyScope disabled = setSystemProperty(property, "false")) {
            RawStoreStabilityDiagnostics.configureWorker(command, directory, "delos_heap", 1);
            RawStoreStabilityDiagnostics.beforeMeasurement();
            RawStoreStabilityDiagnostics.markStart(System.nanoTime());
            RawStoreStabilityDiagnostics.writeWindow(directory, "disabled", 100L, 1L);
            assertTrue("disabled diagnostics changed worker command", command.isEmpty());
            assertFalse(Files.exists(directory.resolve("disabled-jvm.csv")));
        }
        try (SystemPropertyScope enabled = setSystemProperty(property, "true")) {
            RawStoreStabilityDiagnostics.configureWorker(command, directory, "delos_heap", 1);
            assertTrue(command.contains("-Xlog:async"));
            assertTrue(command.stream().anyMatch(value -> value.contains("jit+compilation=debug")));
            for (int window = 0; window < 2; window++) {
                RawStoreStabilityDiagnostics.beforeMeasurement();
                long started = System.nanoTime();
                RawStoreStabilityDiagnostics.markStart(started);
                Thread.sleep(20);
                long elapsed = System.nanoTime() - started;
                String stem = "window-" + window;
                RawStoreStabilityDiagnostics.writeWindow(directory, stem, elapsed, 100L);
                List<String> report = Files.readAllLines(directory.resolve(stem + "-jvm.csv"));
                String[] columns = report.get(0).split(",");
                String[] values = report.get(1).split(",");
                Map<String, String> data = new HashMap<>();
                for (int i = 0; i < columns.length; i++) data.put(columns[i], values[i]);
                assertEquals(elapsed, Long.parseLong(data.get("elapsed_ns")));
                double interval = Double.parseDouble(data.get("end_uptime_ms"))
                        - Double.parseDouble(data.get("start_uptime_ms"));
                assertEquals(elapsed / 1_000_000.0, interval, 0.00001);
                assertTrue(Long.parseLong(data.get("process_cpu_ns")) >= -1L);
                assertTrue(Long.parseLong(data.get("gc_collections")) >= 0L);
                assertTrue(Long.parseLong(data.get("gc_collection_ms")) >= 0L);
                assertTrue(Long.parseLong(data.get("available_processors")) > 0L);
            }
            try {
                RawStoreStabilityDiagnostics.writeWindow(directory, "stale", 1L, 1L);
                fail("stale measurement boundaries were reused");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Missing"));
            }
        }
    }

    private static void insert(String url, int base, CountDownLatch start,
            AtomicReference<Throwable> failure) {
        try (Connection connection = DriverManager.getConnection(url);
             PreparedStatement insert = connection.prepareStatement("insert into O values (?, ?)")) {
            connection.setAutoCommit(false);
            start.await();
            for (int row = 0; row < 401; row++) {
                insert.setInt(1, base + row);
                insert.setString(2, "payload");
                insert.executeUpdate();
                if (row % 100 == 99) connection.commit();
            }
            connection.rollback();
        } catch (Exception problem) {
            failure.compareAndSet(null, problem);
        }
    }

    private void assertLedger(String[] report) {
        assertTrue("ledger did not contain resources", report.length > 1);
        String[] columns = report[0].split(",");
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.length; i++) index.put(columns[i], i);
        long heap = 0, root = 0, log = 0, alloc = 0, nested = 0;
        for (String line : Arrays.copyOfRange(report, 1, report.length)) {
            assertFalse("ownership tracking overflowed", line.startsWith("INVALID"));
            String[] values = line.split(",");
            long sum = 0;
            for (int i = index.get("self_ns"); i <= index.get("switch_file_ns"); i++) {
                long value = Long.parseLong(values[i]);
                assertTrue("negative interval: " + line, value >= 0);
                sum += value;
            }
            assertEquals("nested intervals were double counted: " + line,
                    Long.parseLong(values[index.get("hold_ns")]), sum);
            assertTrue(Long.parseLong(values[index.get("wait_ns")]) >= 0);
            long count = Long.parseLong(values[index.get("acquires")]);
            if (values[0].startsWith("PAGE:") && values[1].equals("HEAP_INSERT_PAGE")) heap += count;
            if (values[0].startsWith("PAGE:") && values[1].equals("BTREE_ROOT")) root += count;
            if (values[0].startsWith("LOG:")) log += count;
            if (values[0].startsWith("ALLOC:")) alloc += count;
            if (values[0].startsWith("PAGE:")) {
                nested += Long.parseLong(values[index.get("log_hold_ns")]);
            }
        }
        assertTrue("Heap insert latch missing", heap >= (RawStoreOccupancy.SAMPLE_EVERY == 1 ? 1600 : 1));
        assertTrue("root latch missing", root > 0);
        assertTrue("append monitor missing", log >= (RawStoreOccupancy.SAMPLE_EVERY == 1 ? 3200 : 1));
        assertTrue("allocation monitor missing", alloc > 0);
        assertTrue("log was not attributed under a page latch", nested > 0);
    }
}
