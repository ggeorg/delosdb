/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MvccDatabaseMaintenanceServiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10L);

    @TempDir
    Path databaseDirectory;

    @AfterEach
    void clearProperties() {
        System.clearProperty(MvccPurgeDaemon.ENABLED_PROPERTY);
        System.clearProperty(MvccPurgeDaemon.ASYNC_ENABLED_PROPERTY);
        System.clearProperty(MvccPurgeDaemon.CHANGED_ROWS_THRESHOLD_PROPERTY);
        System.clearProperty(MvccVisibilityDebtPolicy.THRESHOLD_PROPERTY);
    }

    @Test
    void boundedWorkersPrioritizeDebtAndScanIdleTables() throws Exception {
        MvccDatabaseMaintenanceService service =
                new MvccDatabaseMaintenanceService(databaseDirectory, 1, 20L);
        List<String> runOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch queuedRuns = new CountDownLatch(2);

        MvccDatabaseMaintenanceService.Registration blocker = service.register(target(
                "blocker",
                Optional::empty,
                trigger -> {
                    runOrder.add("blocker");
                    blockerStarted.countDown();
                    await(releaseBlocker);
                }));
        MvccDatabaseMaintenanceService.Registration low = service.register(target(
                "low",
                Optional::empty,
                trigger -> {
                    runOrder.add("low");
                    queuedRuns.countDown();
                }));
        MvccDatabaseMaintenanceService.Registration high = service.register(target(
                "high",
                Optional::empty,
                trigger -> {
                    runOrder.add("high");
                    queuedRuns.countDown();
                }));

        blocker.request(new MvccDatabaseMaintenanceService.Priority(100L, 100L, 0L),
                MvccDatabaseMaintenanceService.Trigger.COMMIT);
        assertTrue(blockerStarted.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        low.request(new MvccDatabaseMaintenanceService.Priority(1L, 1L, 0L),
                MvccDatabaseMaintenanceService.Trigger.COMMIT);
        high.request(new MvccDatabaseMaintenanceService.Priority(10L, 10L, 0L),
                MvccDatabaseMaintenanceService.Trigger.COMMIT);
        releaseBlocker.countDown();

        assertTrue(queuedRuns.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(List.of("blocker", "high", "low"), runOrder);
        assertEquals(1, service.metrics().workerCount());
        assertEquals(1, service.metrics().maximumActiveWorkerCount());

        AtomicBoolean periodicEligible = new AtomicBoolean(true);
        CountDownLatch periodicRun = new CountDownLatch(1);
        MvccDatabaseMaintenanceService.Registration idle = service.register(target(
                "idle",
                () -> periodicEligible.get()
                        ? Optional.of(new MvccDatabaseMaintenanceService.Priority(5L, 5L, 0L))
                        : Optional.empty(),
                trigger -> {
                    if (trigger == MvccDatabaseMaintenanceService.Trigger.PERIODIC) {
                        periodicEligible.set(false);
                        periodicRun.countDown();
                    }
                }));

        assertTrue(periodicRun.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertTrue(service.metrics().periodicScanCount() > 0L);
        assertTrue(service.metrics().scheduledTaskCount() >= 4L);
        assertEquals(0L, service.metrics().failureCount());

        idle.close();
        high.close();
        low.close();
        blocker.close();
        service.close();
        assertFalse(service.metrics().accepting());
    }

    @Test
    void oneStoreOwnsMaintenanceForMultipleTablesAndPreservesReaderHorizon() throws Exception {
        System.setProperty(MvccPurgeDaemon.ENABLED_PROPERTY, "true");
        System.setProperty(MvccPurgeDaemon.ASYNC_ENABLED_PROPERTY, "true");
        System.setProperty(MvccPurgeDaemon.CHANGED_ROWS_THRESHOLD_PROPERTY, "1");
        System.setProperty(MvccVisibilityDebtPolicy.THRESHOLD_PROPERTY, "1");

        MvccDatabaseMaintenanceService service =
                new MvccDatabaseMaintenanceService(databaseDirectory, 1, 25L);
        MvccInheritedStore store = new MvccInheritedStore(databaseDirectory, service);
        MvccInheritedTable first = (MvccInheritedTable) store.openTable(new DelosStorageTableKey(0L, 1001L));
        MvccInheritedTable second = (MvccInheritedTable) store.openTable(new DelosStorageTableKey(0L, 1002L));

        assertSame(service, first.maintenanceServiceForTesting());
        assertSame(service, second.maintenanceServiceForTesting());
        assertEquals(2, first.databaseMaintenanceRegisteredTableCountForTesting());
        assertEquals(1, first.databaseMaintenanceWorkerCountForTesting());

        insert(first, 1L);
        insert(second, 1L);

        DelosStorageTransaction retainedReader = first.beginReadOnlyTransaction();
        first.snapshot(retainedReader);
        delete(first, 1L);
        waitUntil(() -> first.purgeDaemonSkipCountForTesting() > 0L);
        assertEquals(0L, first.purgeDaemonRunCountForTesting());

        delete(second, 1L);
        waitUntil(() -> second.purgeDaemonRunCountForTesting() > 0L);

        first.abort(retainedReader);
        waitUntil(() -> first.purgeDaemonRunCountForTesting() > 0L);

        assertTrue(first.databaseMaintenanceCommitWakeupCountForTesting() >= 2L);
        assertTrue(first.databaseMaintenancePeriodicScanCountForTesting() > 0L);
        assertTrue(first.databaseMaintenanceRunCountForTesting() >= 3L);
        assertEquals(1, first.databaseMaintenanceMaximumActiveWorkerCountForTesting());
        assertEquals(0L, first.databaseMaintenanceFailureCountForTesting());
        first.assertConsistentForTesting();
        second.assertConsistentForTesting();

        store.close();
        assertFalse(service.metrics().accepting());
        first.close();
        second.close();
    }

    @Test
    void closeFailsWithoutClosingTableResourcesWhileRunningWorkerIgnoresInterrupt() throws Exception {
        MvccDatabaseMaintenanceService service = new MvccDatabaseMaintenanceService(
                databaseDirectory.resolve("stubborn-worker"),
                1,
                TimeUnit.DAYS.toMillis(1L),
                Duration.ofMillis(50L));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MvccDatabaseMaintenanceService.Registration registration = service.register(target(
                "stubborn",
                Optional::empty,
                trigger -> {
                    started.countDown();
                    await(release);
                }));

        registration.request(
                new MvccDatabaseMaintenanceService.Priority(1L, 1L, 0L),
                MvccDatabaseMaintenanceService.Trigger.COMMIT);
        assertTrue(started.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

        IllegalStateException failure = assertThrows(IllegalStateException.class, service::close);
        assertTrue(failure.getMessage().contains("table resources remain open"));
        assertEquals(1, service.metrics().activeWorkerCount());

        release.countDown();
        waitUntil(() -> service.metrics().activeWorkerCount() == 0);
        service.close();
        assertFalse(service.metrics().accepting());
    }

    private static MvccDatabaseMaintenanceService.Target target(
            String identity,
            java.util.function.Supplier<Optional<MvccDatabaseMaintenanceService.Priority>> periodic,
            java.util.function.Consumer<MvccDatabaseMaintenanceService.Trigger> run) {
        return new MvccDatabaseMaintenanceService.Target() {
            @Override
            public String maintenanceIdentity() {
                return identity;
            }

            @Override
            public Optional<MvccDatabaseMaintenanceService.Priority> periodicMaintenancePriority() {
                return periodic.get();
            }

            @Override
            public void runMaintenance(MvccDatabaseMaintenanceService.Trigger trigger) {
                run.accept(trigger);
            }
        };
    }

    private static void insert(MvccInheritedTable table, long rowId) {
        DelosStorageTransaction transaction = table.beginTransaction();
        table.insert(rowId, new StoreDataValue[0], transaction);
        table.commit(transaction);
    }

    private static void delete(MvccInheritedTable table, long rowId) {
        DelosStorageTransaction transaction = table.beginTransaction();
        DelosStorageSnapshot snapshot = table.snapshot(transaction);
        table.delete(rowId, transaction, snapshot);
        table.commit(transaction);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not satisfied before timeout");
            }
            Thread.sleep(10L);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
