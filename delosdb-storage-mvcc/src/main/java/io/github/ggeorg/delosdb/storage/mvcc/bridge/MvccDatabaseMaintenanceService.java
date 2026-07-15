/*

   DelosDB - Class io.github.ggeorg.delosdb.storage.mvcc.bridge.MvccDatabaseMaintenanceService

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
package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Database-owned scheduler for inherited MVCC maintenance.
 *
 * <p>Tables retain authority for reader-horizon checks and the proven purge
 * algorithm.  This service owns only scheduling: commit wakeups, periodic idle
 * scans, bounded workers, debt ordering, and database shutdown.</p>
 */
final class MvccDatabaseMaintenanceService implements AutoCloseable {
    static final String WORKER_COUNT_PROPERTY = "delosdb.mvcc.maintenance.workerCount";
    static final String PERIOD_MILLIS_PROPERTY = "delosdb.mvcc.maintenance.periodMillis";
    static final int DEFAULT_WORKER_COUNT = 1;
    static final long DEFAULT_PERIOD_MILLIS = 1_000L;
    private static final int MAX_WORKER_COUNT = 8;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30L);

    enum Trigger {
        COMMIT,
        PERIODIC
    }

    private enum TaskState {
        IDLE,
        QUEUED,
        RUNNING
    }

    interface Target {
        String maintenanceIdentity();

        Optional<Priority> periodicMaintenancePriority();

        void runMaintenance(Trigger trigger);
    }

    record Priority(long visibilityDebtScore,
                    long obsoleteVersions,
                    long pendingPurgeEntries) implements Comparable<Priority> {
        Priority {
            visibilityDebtScore = Math.max(0L, visibilityDebtScore);
            obsoleteVersions = Math.max(0L, obsoleteVersions);
            pendingPurgeEntries = Math.max(0L, pendingPurgeEntries);
        }

        static Priority from(MvccVisibilityDebtPolicy.Snapshot debt) {
            Objects.requireNonNull(debt, "debt");
            return new Priority(debt.score(), debt.obsoleteVersions(), debt.pendingPurgeEntries());
        }

        @Override
        public int compareTo(Priority other) {
            int debtOrder = Long.compare(other.visibilityDebtScore, visibilityDebtScore);
            if (debtOrder != 0) {
                return debtOrder;
            }
            int growthOrder = Long.compare(other.obsoleteVersions, obsoleteVersions);
            if (growthOrder != 0) {
                return growthOrder;
            }
            return Long.compare(other.pendingPurgeEntries, pendingPurgeEntries);
        }
    }

    record Metrics(int workerCount,
                   int registeredTableCount,
                   int queuedTaskCount,
                   long commitWakeupCount,
                   long periodicScanCount,
                   long scheduledTaskCount,
                   long runCount,
                   long failureCount,
                   int activeWorkerCount,
                   int maximumActiveWorkerCount,
                   boolean accepting) {
    }

    final class Registration implements AutoCloseable {
        private final Target target;
        private boolean active = true;
        private TaskState state = TaskState.IDLE;
        private boolean rerunRequested;
        private Priority latestPriority = new Priority(0L, 0L, 0L);
        private Trigger latestTrigger = Trigger.PERIODIC;

        private Registration(Target target) {
            this.target = target;
        }

        boolean request(Priority priority, Trigger trigger) {
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(trigger, "trigger");
            if (trigger == Trigger.COMMIT) {
                commitWakeupCount.incrementAndGet();
            }
            synchronized (this) {
                if (!active || !accepting.get()) {
                    return false;
                }
                if (state != TaskState.IDLE) {
                    if (!rerunRequested || priority.compareTo(latestPriority) < 0) {
                        latestPriority = priority;
                    }
                    latestTrigger = trigger;
                    rerunRequested = true;
                    return false;
                }
                latestPriority = priority;
                latestTrigger = trigger;
                state = TaskState.QUEUED;
                if (enqueue(this, latestPriority, latestTrigger)) {
                    return true;
                }
                state = TaskState.IDLE;
                notifyAll();
                return false;
            }
        }

        private void run(Trigger trigger) {
            synchronized (this) {
                if (!active) {
                    state = TaskState.IDLE;
                    rerunRequested = false;
                    notifyAll();
                    return;
                }
                if (state != TaskState.QUEUED) {
                    return;
                }
                state = TaskState.RUNNING;
            }
            int activeNow = activeWorkers.incrementAndGet();
            maximumActiveWorkers.accumulateAndGet(activeNow, Math::max);
            try {
                target.runMaintenance(trigger);
                runCount.incrementAndGet();
            } catch (RuntimeException failure) {
                failureCount.incrementAndGet();
            } finally {
                activeWorkers.decrementAndGet();
                finishRun();
            }
        }

        private void finishRun() {
            synchronized (this) {
                if (active && accepting.get() && rerunRequested) {
                    rerunRequested = false;
                    state = TaskState.QUEUED;
                    if (enqueue(this, latestPriority, latestTrigger)) {
                        return;
                    }
                }
                finishWithoutRerun();
            }
        }

        private void finishWithoutRerun() {
            state = TaskState.IDLE;
            rerunRequested = false;
            notifyAll();
        }

        private void cancelQueuedTask() {
            synchronized (this) {
                if (state == TaskState.QUEUED) {
                    finishWithoutRerun();
                }
            }
        }

        @Override
        public void close() {
            boolean interrupted = false;
            synchronized (this) {
                active = false;
                rerunRequested = false;
                while (state != TaskState.IDLE) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            registrations.remove(this);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final String databaseIdentity;
    private final int workerCount;
    private final Set<Registration> registrations = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService periodicScanner;
    private final Duration shutdownTimeout;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong commitWakeupCount = new AtomicLong();
    private final AtomicLong periodicScanCount = new AtomicLong();
    private final AtomicLong scheduledTaskCount = new AtomicLong();
    private final AtomicLong runCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger maximumActiveWorkers = new AtomicInteger();

    MvccDatabaseMaintenanceService(Path databaseDirectory) {
        this(databaseDirectory, configuredWorkerCount(), configuredPeriodMillis(), SHUTDOWN_TIMEOUT);
    }

    MvccDatabaseMaintenanceService(Path databaseDirectory, int workerCount, long periodMillis) {
        this(databaseDirectory, workerCount, periodMillis, SHUTDOWN_TIMEOUT);
    }

    MvccDatabaseMaintenanceService(
            Path databaseDirectory,
            int workerCount,
            long periodMillis,
            Duration shutdownTimeout) {
        if (workerCount < 1 || workerCount > MAX_WORKER_COUNT) {
            throw new IllegalArgumentException("workerCount must be in [1, " + MAX_WORKER_COUNT + ']');
        }
        if (periodMillis < 1L) {
            throw new IllegalArgumentException("periodMillis must be positive");
        }
        if (Objects.requireNonNull(shutdownTimeout, "shutdownTimeout").isZero()
                || shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownTimeout must be positive");
        }
        this.databaseIdentity = databaseDirectory == null
                ? "memory-" + Integer.toHexString(System.identityHashCode(this))
                : databaseDirectory.toAbsolutePath().normalize().toString();
        this.workerCount = workerCount;
        this.shutdownTimeout = shutdownTimeout;
        String threadIdentity = Integer.toHexString(databaseIdentity.hashCode());
        ThreadFactory workerFactory = Thread.ofVirtual()
                .name("delosdb-mvcc-maintenance-worker-" + threadIdentity + '-', 0L)
                .factory();
        this.workers = new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                workerFactory);
        this.periodicScanner = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual()
                        .name("delosdb-mvcc-maintenance-scan-" + threadIdentity)
                        .factory());
        periodicScanner.scheduleWithFixedDelay(
                this::scanIdleTables,
                periodMillis,
                periodMillis,
                TimeUnit.MILLISECONDS);
    }

    Registration register(Target target) {
        Objects.requireNonNull(target, "target");
        if (!accepting.get()) {
            throw new IllegalStateException("MVCC database maintenance service is closed: " + databaseIdentity);
        }
        Registration registration = new Registration(target);
        registrations.add(registration);
        if (!accepting.get()) {
            registration.close();
            throw new IllegalStateException("MVCC database maintenance service is closed: " + databaseIdentity);
        }
        return registration;
    }

    Metrics metrics() {
        return new Metrics(
                workerCount,
                registrations.size(),
                workers.getQueue().size(),
                commitWakeupCount.get(),
                periodicScanCount.get(),
                scheduledTaskCount.get(),
                runCount.get(),
                failureCount.get(),
                activeWorkers.get(),
                maximumActiveWorkers.get(),
                accepting.get());
    }

    private boolean enqueue(Registration registration, Priority priority, Trigger trigger) {
        try {
            workers.execute(new MaintenanceTask(
                    registration,
                    priority,
                    trigger,
                    sequence.incrementAndGet()));
            scheduledTaskCount.incrementAndGet();
            return true;
        } catch (RuntimeException rejected) {
            registration.finishWithoutRerun();
            return false;
        }
    }

    private void scanIdleTables() {
        if (!accepting.get()) {
            return;
        }
        periodicScanCount.incrementAndGet();
        for (Registration registration : registrations) {
            try {
                registration.target.periodicMaintenancePriority()
                        .ifPresent(priority -> registration.request(priority, Trigger.PERIODIC));
            } catch (RuntimeException failure) {
                failureCount.incrementAndGet();
            }
        }
    }

    @Override
    public void close() {
        if (accepting.compareAndSet(true, false)) {
            periodicScanner.shutdownNow();
            workers.shutdown();
        }
        boolean interrupted = false;
        try {
            if (!periodicScanner.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                periodicScanner.shutdownNow();
                if (!periodicScanner.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("MVCC maintenance scanner did not terminate for "
                            + databaseIdentity);
                }
            }
            if (!workers.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                List<Runnable> cancelled = workers.shutdownNow();
                for (Runnable runnable : cancelled) {
                    if (runnable instanceof MaintenanceTask task) {
                        task.cancel();
                    }
                }
                if (!workers.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("MVCC maintenance workers did not terminate for "
                            + databaseIdentity + "; table resources remain open");
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            List<Runnable> cancelled = workers.shutdownNow();
            for (Runnable runnable : cancelled) {
                if (runnable instanceof MaintenanceTask task) {
                    task.cancel();
                }
            }
            throw new IllegalStateException("Interrupted while closing MVCC maintenance for "
                    + databaseIdentity, e);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        for (Registration registration : new ArrayList<>(registrations)) {
            registration.close();
        }
    }

    private static int configuredWorkerCount() {
        String configured = System.getProperty(WORKER_COUNT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_WORKER_COUNT;
        }
        try {
            return Math.max(1, Math.min(MAX_WORKER_COUNT, Integer.parseInt(configured.trim())));
        } catch (NumberFormatException ignored) {
            return DEFAULT_WORKER_COUNT;
        }
    }

    private static long configuredPeriodMillis() {
        String configured = System.getProperty(PERIOD_MILLIS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PERIOD_MILLIS;
        }
        try {
            return Math.max(1L, Long.parseLong(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_PERIOD_MILLIS;
        }
    }

    private final class MaintenanceTask implements Runnable, Comparable<MaintenanceTask> {
        private final Registration registration;
        private final Priority priority;
        private final Trigger trigger;
        private final long sequenceNumber;

        private MaintenanceTask(
                Registration registration,
                Priority priority,
                Trigger trigger,
                long sequenceNumber) {
            this.registration = registration;
            this.priority = priority;
            this.trigger = trigger;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public void run() {
            registration.run(trigger);
        }

        @Override
        public int compareTo(MaintenanceTask other) {
            int priorityOrder = priority.compareTo(other.priority);
            if (priorityOrder != 0) {
                return priorityOrder;
            }
            return Long.compare(sequenceNumber, other.sequenceNumber);
        }

        private void cancel() {
            registration.cancelQueuedTask();
        }
    }
}
