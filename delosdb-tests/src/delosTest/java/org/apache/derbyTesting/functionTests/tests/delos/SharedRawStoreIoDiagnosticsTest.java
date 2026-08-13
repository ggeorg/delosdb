/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.SharedRawStoreIoDiagnosticsTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.derby.iapi.store.types.DelosRawStoreIoDiagnosticsDirectory;
import org.apache.derby.iapi.store.types.DelosRawStoreIoMetrics;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Shared heap/MVCC, file/memory RawStore page-I/O diagnostics proofs. */
public final class SharedRawStoreIoDiagnosticsTest extends MvccSqlTestSupport {
    private static final String RAWSTORE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testCounterLifecycleIsBoundedExactAndLeakFree() {
        DelosRawStoreIoMetrics metrics = new DelosRawStoreIoMetrics();
        metrics.bind("file:/stage8-counter-proof", false);

        metrics.containerHandleOpened();
        metrics.pageIoStarted();
        metrics.pageIoStarted();
        metrics.pageReadSucceeded(4096L);
        metrics.pageIoFinished();
        metrics.pageWriteFailed();
        metrics.pageIoFinished();
        metrics.pageWriteSucceeded(8192L);
        metrics.pageReadFailed();
        metrics.forceSucceeded(false);
        metrics.forceSucceeded(true);
        metrics.forceFailed();
        metrics.closedChannelDetected();
        metrics.channelRecoveryAttempted();
        metrics.channelReopenSucceeded();

        DelosRawStoreIoSnapshot active = metrics.snapshot();
        assertTrue(active.runtimeActive());
        assertFalse(active.memoryDatabase());
        assertEquals(1L, active.pageReadOperations());
        assertEquals(4096L, active.pageReadBytes());
        assertEquals(1L, active.pageWriteOperations());
        assertEquals(8192L, active.pageWriteBytes());
        assertEquals(1L, active.pageReadFailures());
        assertEquals(1L, active.pageWriteFailures());
        assertEquals(1L, active.contentOnlyForceOperations());
        assertEquals(1L, active.metadataForceOperations());
        assertEquals(1L, active.forceFailures());
        assertEquals(1L, active.closedChannelDetections());
        assertEquals(1L, active.channelRecoveryAttempts());
        assertEquals(1L, active.successfulChannelReopens());
        assertEquals(0L, active.failedChannelReopens());
        assertEquals(0L, active.currentInFlightPageIo());
        assertEquals(2L, active.peakInFlightPageIo());
        assertEquals(1L, active.currentOpenContainerHandles());
        assertEquals(1L, active.peakOpenContainerHandles());

        metrics.containerHandleClosed();
        metrics.shutdown();
        DelosRawStoreIoSnapshot stopped = metrics.snapshot();
        assertFalse(stopped.runtimeActive());
        assertEquals(0L, stopped.currentOpenContainerHandles());
        assertEquals(0L, stopped.currentInFlightPageIo());
        assertEquals(0L, stopped.unclosedContainerHandlesAtShutdown());

        String leakingIdentity = "memory:stage8-leak-proof";
        DelosRawStoreIoMetrics leakingMetrics = new DelosRawStoreIoMetrics();
        leakingMetrics.bind(leakingIdentity, true);
        DelosRawStoreIoDiagnosticsDirectory.register(
                leakingIdentity, leakingMetrics);
        leakingMetrics.containerHandleOpened();
        leakingMetrics.shutdown();
        DelosRawStoreIoDiagnosticsDirectory.unregister(
                leakingIdentity, leakingMetrics);
        DelosRawStoreIoSnapshot leaking =
                DelosRawStoreIoDiagnosticsDirectory.snapshot(leakingIdentity);
        assertFalse(leaking.runtimeActive());
        assertTrue(leaking.memoryDatabase());
        assertEquals(1L, leaking.currentOpenContainerHandles());
        assertEquals(1L, leaking.unclosedContainerHandlesAtShutdown());

        DelosRawStoreIoMetrics replacementMetrics = new DelosRawStoreIoMetrics();
        replacementMetrics.bind(leakingIdentity, true);
        DelosRawStoreIoDiagnosticsDirectory.register(
                leakingIdentity, replacementMetrics);
        assertTrue(DelosRawStoreIoDiagnosticsDirectory
                .snapshot(leakingIdentity).runtimeActive());
        replacementMetrics.shutdown();
        DelosRawStoreIoDiagnosticsDirectory.unregister(
                leakingIdentity, replacementMetrics);
    }

    public void testDuplicateActiveRegistrationIsRejectedWithoutReplacement() {
        String identity = "memory:stage8-duplicate-registration-proof";
        DelosRawStoreIoMetrics first = new DelosRawStoreIoMetrics();
        DelosRawStoreIoMetrics second = new DelosRawStoreIoMetrics();
        first.bind(identity, true);
        second.bind(identity, true);

        DelosRawStoreIoDiagnosticsDirectory.register(identity, first);
        try {
            DelosRawStoreIoDiagnosticsDirectory.register(identity, second);
            fail("Expected duplicate active diagnostics registration to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains(identity));
        }

        assertEquals(first.snapshot(),
                DelosRawStoreIoDiagnosticsDirectory.snapshot(identity));
        first.shutdown();
        DelosRawStoreIoDiagnosticsDirectory.unregister(identity, first);

        DelosRawStoreIoDiagnosticsDirectory.register(identity, second);
        assertTrue(DelosRawStoreIoDiagnosticsDirectory
                .snapshot(identity).runtimeActive());
        second.shutdown();
        DelosRawStoreIoDiagnosticsDirectory.unregister(identity, second);
    }

    public void testConcurrentAccountingRemainsExactAndSnapshotSafe()
            throws Exception {
        final int workers = 8;
        final int operationsPerWorker = 250;
        DelosRawStoreIoMetrics metrics = new DelosRawStoreIoMetrics();
        metrics.bind("file:/stage8-concurrent-proof", false);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(30L, TimeUnit.SECONDS));
                    for (int operation = 0;
                         operation < operationsPerWorker;
                         operation++) {
                        metrics.containerHandleOpened();
                        metrics.pageIoStarted();
                        metrics.pageReadSucceeded(4096L);
                        metrics.pageIoFinished();
                        metrics.containerHandleClosed();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(30L, TimeUnit.SECONDS));
            start.countDown();
            while (futures.stream().anyMatch(future -> !future.isDone())) {
                DelosRawStoreIoSnapshot observed = metrics.snapshot();
                assertTrue(observed.currentInFlightPageIo()
                        <= observed.peakInFlightPageIo());
                assertTrue(observed.currentOpenContainerHandles()
                        <= observed.peakOpenContainerHandles());
                Thread.yield();
            }
            for (Future<?> future : futures) {
                future.get(30L, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30L, TimeUnit.SECONDS));
        }

        DelosRawStoreIoSnapshot completed = metrics.snapshot();
        long expectedOperations = (long) workers * operationsPerWorker;
        assertEquals(expectedOperations, completed.pageReadOperations());
        assertEquals(expectedOperations * 4096L, completed.pageReadBytes());
        assertEquals(0L, completed.currentInFlightPageIo());
        assertEquals(0L, completed.currentOpenContainerHandles());
        assertTrue(completed.peakInFlightPageIo() > 0L);
        assertTrue(completed.peakOpenContainerHandles() > 0L);
    }

    public void testFileDatabaseExposesOneSharedHeapAndMvccSnapshot()
            throws Exception {
        String database = databaseName("shared-rawstore-io-file");
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table io_heap_t (id int primary key, value int)");
                executeUpdate(connection,
                        "create table io_mvcc_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(connection, "insert into io_heap_t values (1, 10)");
                executeUpdate(connection, "insert into io_mvcc_t values (1, 20)");
                connection.commit();
                assertRows(connection, "select value from io_heap_t", "10");
                assertRows(connection, "select value from io_mvcc_t", "20");
                connection.commit();
                executeUpdate(connection,
                        "call syscs_util.syscs_checkpoint_database()");

                DelosRawStoreIoSnapshot heap =
                        DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(
                                databasePath(database));
                DelosRawStoreIoSnapshot mvcc =
                        DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(
                                databasePath(database));
                assertEquals(heap, mvcc);
                assertTrue(heap.runtimeActive());
                assertFalse(heap.memoryDatabase());
                assertTrue(heap.databaseIdentity().startsWith("file:"));
                assertTrue(heap.pageReadOperations() > 0L);
                assertTrue(heap.pageReadBytes() > 0L);
                assertTrue(heap.pageWriteOperations() > 0L);
                assertTrue(heap.pageWriteBytes() > 0L);
                assertTrue(heap.totalForceOperations() > 0L);
                assertEquals(0L, heap.currentInFlightPageIo());
                assertTrue(heap.peakInFlightPageIo() > 0L);
                assertTrue(heap.peakOpenContainerHandles() > 0L);
                assertTrue(heap.currentOpenContainerHandles()
                        <= heap.peakOpenContainerHandles());

                DelosRawStoreIoSnapshot unchanged =
                        DelosStorageDiagnosticsRegistry.mvccDatabaseRawStoreIoSnapshot(
                                databasePath(database));
                assertEquals("diagnostic reads must not mutate storage counters",
                        mvcc, unchanged);
            } finally {
                shutdownDatabase(database);
            }
            DelosRawStoreIoSnapshot stopped =
                    DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(
                            databasePath(database));
            assertFalse(stopped.runtimeActive());
            assertEquals(0L, stopped.currentInFlightPageIo());
            assertEquals(0L, stopped.currentOpenContainerHandles());
            assertEquals(0L, stopped.unclosedContainerHandlesAtShutdown());
        }
    }

    public void testFileDatabaseDiagnosticsOwnershipSurvivesImmediateRestart()
            throws Exception {
        String database = databaseName("shared-rawstore-io-restart");
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                executeUpdate(connection,
                        "create table restart_t (id int primary key, value int)");
                executeUpdate(connection, "insert into restart_t values (1, 10)");
            }
            shutdownDatabase(database);

            DelosRawStoreIoSnapshot stopped =
                    DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(
                            databasePath(database));
            assertFalse(stopped.runtimeActive());

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened, "select value from restart_t where id = 1", "10");
                DelosRawStoreIoSnapshot active =
                        DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(
                                databasePath(database));
                assertTrue(active.runtimeActive());
            } finally {
                shutdownDatabase(database);
            }
        }
    }

    public void testMemoryDatabaseUsesTheSameSnapshotShapeAndMetadataForcePath()
            throws Exception {
        String database = "shared-rawstore-io-memory-" + System.nanoTime();
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:memory:" + database + ";create=true")) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table io_memory_heap_t (id int primary key, value int)");
                executeUpdate(connection,
                        "create table io_memory_mvcc_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(connection,
                        "insert into io_memory_heap_t values (1, 30)");
                executeUpdate(connection,
                        "insert into io_memory_mvcc_t values (1, 40)");
                connection.commit();
                assertRows(connection,
                        "select value from io_memory_mvcc_t", "40");
                connection.commit();
                executeUpdate(connection,
                        "call syscs_util.syscs_checkpoint_database()");

                DelosRawStoreIoSnapshot heap =
                        DelosStorageDiagnosticsRegistry.heapMemoryDatabaseRawStoreIoSnapshot(
                                database);
                DelosRawStoreIoSnapshot mvcc =
                        DelosStorageDiagnosticsRegistry.mvccMemoryDatabaseRawStoreIoSnapshot(
                                database);
                assertEquals(heap, mvcc);
                assertTrue(heap.runtimeActive());
                assertTrue(heap.memoryDatabase());
                assertTrue(heap.databaseIdentity().startsWith("memory:"));
                // A newly created memory database may satisfy logical reads
                // entirely from the page cache. Requiring a physical read
                // would couple this diagnostics proof to cache eviction policy.
                assertTrue(heap.pageWriteOperations() > 0L);
                assertTrue(heap.pageWriteBytes() > 0L);
                assertTrue(heap.metadataForceOperations() > 0L);
                assertEquals(0L, heap.currentInFlightPageIo());
                assertTrue(heap.peakInFlightPageIo() > 0L);
            } finally {
                shutdownNamedMemoryDatabase(database);
            }
            DelosRawStoreIoSnapshot stopped =
                    DelosStorageDiagnosticsRegistry.heapMemoryDatabaseRawStoreIoSnapshot(
                            database);
            assertFalse(stopped.runtimeActive());
            assertTrue(stopped.memoryDatabase());
            assertEquals(0L, stopped.currentInFlightPageIo());
            assertEquals(0L, stopped.currentOpenContainerHandles());
            assertEquals(0L, stopped.unclosedContainerHandlesAtShutdown());
        }
    }

    public void testTwoFileDatabasesKeepIndependentCounters() throws Exception {
        String firstDatabase = databaseName("shared-rawstore-io-isolation-a");
        String secondDatabase = databaseName("shared-rawstore-io-isolation-b");
        try (SystemPropertyScope ignored =
                     setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true")) {
            try (Connection first = openDatabase(firstDatabase, true);
                 Connection second = openDatabase(secondDatabase, true)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                executeUpdate(first,
                        "create table io_isolation_a (id int primary key, value int)");
                executeUpdate(second,
                        "create table io_isolation_b (id int primary key, value int)");
                first.commit();
                second.commit();
                executeUpdate(first,
                        "call syscs_util.syscs_checkpoint_database()");
                executeUpdate(second,
                        "call syscs_util.syscs_checkpoint_database()");

                DelosRawStoreIoSnapshot firstBefore = snapshot(firstDatabase);
                DelosRawStoreIoSnapshot secondBefore = snapshot(secondDatabase);
                assertFalse(firstBefore.databaseIdentity()
                        .equals(secondBefore.databaseIdentity()));

                for (int id = 1; id <= 64; id++) {
                    executeUpdate(first,
                            "insert into io_isolation_a values ("
                                    + id + ", " + (id * 10) + ")");
                }
                first.commit();
                executeUpdate(first,
                        "call syscs_util.syscs_checkpoint_database()");

                DelosRawStoreIoSnapshot firstAfter = snapshot(firstDatabase);
                DelosRawStoreIoSnapshot secondAfter = snapshot(secondDatabase);
                assertTrue(firstAfter.pageWriteOperations()
                        > firstBefore.pageWriteOperations());
                assertEquals(secondBefore.pageWriteOperations(),
                        secondAfter.pageWriteOperations());
                assertEquals(secondBefore.pageWriteBytes(),
                        secondAfter.pageWriteBytes());
            } finally {
                shutdownDatabase(firstDatabase);
                shutdownDatabase(secondDatabase);
            }
        }
    }

    private static DelosRawStoreIoSnapshot snapshot(String database) {
        return DelosStorageDiagnosticsRegistry.heapDatabaseRawStoreIoSnapshot(
                databasePath(database));
    }
}
