/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreMaintenanceDiagnosticsTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceTableSnapshot;

/** Autonomous RawStore MVCC maintenance and immutable diagnostics proofs. */
public final class MvccRawStoreMaintenanceDiagnosticsTest extends MvccSqlTestSupport {
    private static final String RAWSTORE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String MAINTENANCE_ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.enabled";
    private static final String PERIOD_MILLIS_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.periodMillis";
    private static final String CHANGED_ROWS_THRESHOLD_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20L);

    public void testMaintenanceIsExplicitDuringConvergenceAndDiagnosticsRemainDatabaseScoped()
            throws Exception {
        String database = databaseName("mvcc-raw-store-maintenance-disabled");
        try (SystemPropertyScope rawStore = setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true");
             SystemPropertyScope maintenance = setSystemProperty(
                     MAINTENANCE_ENABLED_PROPERTY, "false")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table maintenance_disabled_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(connection,
                        "insert into maintenance_disabled_t values (1, 10)");
                connection.commit();
                executeUpdate(connection,
                        "update maintenance_disabled_t set value = 20 where id = 1");
                connection.commit();
                executeUpdate(connection,
                        "update maintenance_disabled_t set value = 30 where id = 1");
                connection.commit();

                long tableId = mvccContainerId(connection, "MAINTENANCE_DISABLED_T");
                DelosStorageMaintenanceSnapshot snapshot = snapshot(database);
                assertTrue(snapshot.runtimeActive());
                assertFalse(snapshot.maintenanceEnabled());
                assertFalse(snapshot.accepting());
                assertEquals(0, snapshot.workerCount());
                assertEquals(1, snapshot.registeredTableCount());
                assertEquals(0L, snapshot.scheduledRunCount());
                assertEquals(0L, snapshot.completedRunCount());
                DelosStorageMaintenanceTableSnapshot table = tableSnapshot(snapshot, tableId);
                assertTrue(table.active());
                assertFalse(table.queued());
                assertFalse(table.running());
                assertEquals(0L, table.runCount());
                assertEquals(3,
                        MvccRawStoreMetadataInspection.versions(
                                connection, "MAINTENANCE_DISABLED_T").size());
                connection.commit();
            }
            shutdownDatabase(database);
            assertNoActiveRuntime(database);
        }
    }

    public void testCommitWakeupsAutonomouslyReclaimHistoryWithOneBoundedWorker()
            throws Exception {
        String database = databaseName("mvcc-raw-store-maintenance-autonomous");
        try (MaintenanceProperties ignored = enableMaintenance()) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table maintenance_auto_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(connection, "insert into maintenance_auto_t values (1, 10)");
                connection.commit();
                executeUpdate(connection,
                        "update maintenance_auto_t set value = 20 where id = 1");
                connection.commit();
                executeUpdate(connection,
                        "update maintenance_auto_t set value = 30 where id = 1");
                connection.commit();
                executeUpdate(connection,
                        "update maintenance_auto_t set value = 40 where id = 1");
                connection.commit();

                long tableId = mvccContainerId(connection, "MAINTENANCE_AUTO_T");
                waitUntil("autonomous maintenance did not reclaim committed history", () ->
                        MvccRawStoreMetadataInspection.versions(
                                connection, "MAINTENANCE_AUTO_T").size() == 1
                                && snapshot(database).mutatedRunCount() > 0L);

                DelosStorageMaintenanceSnapshot first = snapshot(database);
                DelosStorageMaintenanceSnapshot second = snapshot(database);
                assertTrue(first.runtimeActive());
                assertTrue(first.maintenanceEnabled());
                assertTrue(first.accepting());
                assertEquals(1, first.workerCount());
                assertEquals(1, first.maximumActiveWorkerCount());
                assertTrue(first.commitWakeupCount() >= 1L);
                assertTrue(first.completedRunCount() >= 1L);
                assertEquals(0L, first.notificationFailureCount());
                assertEquals(0L, first.failedRunCount());
                assertTrue(first.removedVersionCount() >= 1L);
                assertTrue(second.captureSequence() > first.captureSequence());
                assertEquals(first.databaseIdentity(), second.databaseIdentity());
                assertEquals(first.storageMode(), second.storageMode());
                assertEquals(first.collectionSemantics(), second.collectionSemantics());

                DelosStorageMaintenanceTableSnapshot table = tableSnapshot(first, tableId);
                assertEquals(0L, table.failureCount());
                assertEquals(1, table.remainingVersions());
                assertEquals(1, table.remainingLogicalRows());
                assertFalse(table.retryRequired());
                assertTrue(table.lastDecision().startsWith("vacuumed")
                        || "no-reclaimable-history".equals(table.lastDecision()));
                assertRows(connection,
                        "select value from maintenance_auto_t where id = 1",
                        "40");
                connection.commit();

                try {
                    first.tableSnapshots().clear();
                    fail("maintenance table observations must be immutable");
                } catch (UnsupportedOperationException expected) {
                    // Expected immutable diagnostic evidence.
                }
            }
            shutdownDatabase(database);
            assertNoActiveRuntime(database);
        }
    }

    public void testRetainedReaderConstrainsAutonomousVacuumUntilLeaseRelease()
            throws Exception {
        String database = databaseName("mvcc-raw-store-maintenance-horizon");
        try (MaintenanceProperties ignored = enableMaintenance()) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table maintenance_horizon_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into maintenance_horizon_t values (1, 10)");
                setup.commit();
            }

            try (Connection reader = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                reader.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertRows(reader,
                        "select value from maintenance_horizon_t where id = 1",
                        "10");

                executeUpdate(writer,
                        "update maintenance_horizon_t set value = 20 where id = 1");
                writer.commit();
                executeUpdate(writer,
                        "update maintenance_horizon_t set value = 30 where id = 1");
                writer.commit();

                long tableId = mvccContainerId(writer, "MAINTENANCE_HORIZON_T");
                waitUntil("retained snapshot was not reported as a vacuum constraint", () -> {
                    DelosStorageMaintenanceSnapshot current = snapshot(database);
                    DelosStorageMaintenanceTableSnapshot table = tableSnapshot(current, tableId);
                    return current.retainedSnapshotCount() >= 1
                            && current.vacuumHorizon() < current.publishedCommitHighWater()
                            && table.retryRequired()
                            && table.remainingVersions() > table.remainingLogicalRows();
                });

                assertRows(reader,
                        "select value from maintenance_horizon_t where id = 1",
                        "10");
                DelosStorageMaintenanceSnapshot constrained = snapshot(database);
                DelosStorageMaintenanceTableSnapshot constrainedTable =
                        tableSnapshot(constrained, tableId);
                assertEquals(constrained.vacuumHorizon(), constrained.oldestRetainedSnapshot());
                assertEquals("retained-snapshot-protects-history",
                        constrainedTable.lastDecision());

                reader.commit();
                waitUntil("periodic retry did not reclaim history after lease release", () ->
                        MvccRawStoreMetadataInspection.versions(
                                writer, "MAINTENANCE_HORIZON_T").size() == 1
                                && !tableSnapshot(snapshot(database), tableId).retryRequired());

                DelosStorageMaintenanceSnapshot released = snapshot(database);
                assertEquals(0, released.retainedSnapshotCount());
                assertEquals(released.publishedCommitHighWater(), released.vacuumHorizon());
                assertEquals(released.publishedCommitHighWater(), released.oldestRetainedSnapshot());
                assertRows(writer,
                        "select value from maintenance_horizon_t where id = 1",
                        "30");
                writer.commit();
            }
            shutdownDatabase(database);
        }
    }

    public void testDatabaseWorkersAreIsolatedAndMemoryUsesTheSameMaintenancePath()
            throws Exception {
        String firstDatabase = databaseName("mvcc-raw-store-maintenance-db-a");
        String secondDatabase = databaseName("mvcc-raw-store-maintenance-db-b");
        try (MaintenanceProperties ignored = enableMaintenance()) {
            try (Connection first = openDatabase(firstDatabase, true);
                 Connection second = openDatabase(secondDatabase, true)) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                executeUpdate(first,
                        "create table maintenance_a_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(second,
                        "create table maintenance_b_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(first, "insert into maintenance_a_t values (1, 10)");
                executeUpdate(second, "insert into maintenance_b_t values (1, 100)");
                first.commit();
                second.commit();
                executeUpdate(first,
                        "update maintenance_a_t set value = 20 where id = 1");
                first.commit();

                waitUntil("first database maintenance did not run", () ->
                        snapshot(firstDatabase).completedRunCount() > 0L);
                DelosStorageMaintenanceSnapshot firstSnapshot = snapshot(firstDatabase);
                DelosStorageMaintenanceSnapshot secondSnapshot = snapshot(secondDatabase);
                assertFalse(firstSnapshot.databaseIdentity().equals(
                        secondSnapshot.databaseIdentity()));
                assertEquals(1, firstSnapshot.workerCount());
                assertEquals(1, secondSnapshot.workerCount());
                assertEquals(1, firstSnapshot.maximumActiveWorkerCount());
                assertTrue(secondSnapshot.maximumActiveWorkerCount() <= 1);
                assertEquals(0L, firstSnapshot.notificationFailureCount());
                assertEquals(0L, secondSnapshot.notificationFailureCount());
                assertEquals(0L, firstSnapshot.failedRunCount());
                assertEquals(0L, secondSnapshot.failedRunCount());
            }
            shutdownDatabase(firstDatabase);
            assertNoActiveRuntime(firstDatabase);
            assertTrue(snapshot(secondDatabase).runtimeActive());
            shutdownDatabase(secondDatabase);
            assertNoActiveRuntime(secondDatabase);

            String memoryDatabase = "mvcc_raw_store_maintenance_memory";
            try (Connection memory = DriverManager.getConnection(
                    "jdbc:derby:memory:" + memoryDatabase + ";create=true")) {
                memory.setAutoCommit(false);
                executeUpdate(memory,
                        "create table maintenance_memory_t ("
                                + "id int primary key, value int) using delos_mvcc");
                executeUpdate(memory,
                        "insert into maintenance_memory_t values (1, 10)");
                memory.commit();
                executeUpdate(memory,
                        "update maintenance_memory_t set value = 20 where id = 1");
                memory.commit();
                waitUntil("memory maintenance did not use the database-owned worker", () ->
                        DelosStorageDiagnosticsRegistry
                                .mvccMemoryDatabaseMaintenanceSnapshot(memoryDatabase)
                                .completedRunCount() > 0L);
                DelosStorageMaintenanceSnapshot memorySnapshot =
                        DelosStorageDiagnosticsRegistry
                                .mvccMemoryDatabaseMaintenanceSnapshot(memoryDatabase);
                assertTrue(memorySnapshot.databaseIdentity().startsWith("memory:"));
                assertEquals(1, memorySnapshot.workerCount());
                assertEquals(0L, memorySnapshot.failedRunCount());
                assertRows(memory,
                        "select value from maintenance_memory_t where id = 1",
                        "20");
                memory.commit();
            }
            shutdownNamedMemoryDatabase(memoryDatabase);
        }
    }

    private static DelosStorageMaintenanceSnapshot snapshot(String database) {
        return DelosStorageDiagnosticsRegistry.mvccDatabaseMaintenanceSnapshot(
                databasePath(database));
    }

    private static DelosStorageMaintenanceTableSnapshot tableSnapshot(
            DelosStorageMaintenanceSnapshot snapshot,
            long metadataContainerId) {
        return snapshot.tableSnapshots().stream()
                .filter(table -> table.metadataContainerId() == metadataContainerId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No maintenance observation for container " + metadataContainerId
                                + " in " + snapshot.tableSnapshots()));
    }

    private static void assertNoActiveRuntime(String database) {
        try {
            snapshot(database);
            fail("shutdown database must not retain a maintenance runtime");
        } catch (IllegalStateException expected) {
            // Diagnostics are weak and non-owning; shutdown removes the runtime.
        }
    }

    private static void waitUntil(String failureMessage, Condition condition) throws Exception {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (Throwable transientFailure) {
                lastFailure = transientFailure;
            }
            Thread.sleep(20L);
        }
        AssertionError failure = new AssertionError(failureMessage);
        if (lastFailure != null) {
            failure.initCause(lastFailure);
        }
        throw failure;
    }

    private static MaintenanceProperties enableMaintenance() {
        return new MaintenanceProperties();
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate() throws Exception;
    }

    private static final class MaintenanceProperties implements AutoCloseable {
        private final List<SystemPropertyScope> scopes = List.of(
                setSystemProperty(RAWSTORE_ENABLED_PROPERTY, "true"),
                setSystemProperty(MAINTENANCE_ENABLED_PROPERTY, "true"),
                setSystemProperty(PERIOD_MILLIS_PROPERTY, "25"),
                setSystemProperty(CHANGED_ROWS_THRESHOLD_PROPERTY, "1"));

        @Override
        public void close() {
            for (int index = scopes.size() - 1; index >= 0; index--) {
                scopes.get(index).close();
            }
        }
    }
}
