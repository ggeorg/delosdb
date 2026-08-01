/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreMaintenanceService

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.store.access.SpaceInfo;
import org.apache.derby.iapi.store.raw.ContainerHandle;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Page;
import org.apache.derby.iapi.store.raw.RawStoreFactory;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceTableSnapshot;
import org.apache.derby.shared.common.error.ExceptionSeverity;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/**
 * Bounded database-owned scheduler for RawStore-backed MVCC reclamation.
 *
 * <p>The service owns only scheduling, autonomous RawStore transactions, and
 * immutable evidence. The vacuum algorithm, reader horizon, logical locks,
 * physical maintenance boundary, logging, undo, commit, and recovery remain
 * owned by the already proven RawStore MVCC components.</p>
 */
final class MvccRawStoreMaintenanceService implements AutoCloseable {
    static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.enabled";
    static final String PERIOD_MILLIS_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.periodMillis";
    static final String CHANGED_ROWS_THRESHOLD_PROPERTY =
            "delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold";
    static final long DEFAULT_PERIOD_MILLIS = 1_000L;
    static final int DEFAULT_CHANGED_ROWS_THRESHOLD = 8;
    static final int TABLE_SNAPSHOT_CAPACITY = 128;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30L);
    private static final String TRANSACTION_NAME = "DelosDB RawStore MVCC maintenance";
    private static final String DIAGNOSTICS_TRANSACTION_NAME =
            "DelosDB RawStore MVCC diagnostics";

    enum Trigger {
        REGISTER,
        COMMIT,
        PERIODIC
    }

    private enum State {
        IDLE,
        QUEUED,
        RUNNING
    }

    private final MvccRawStoreRuntime runtime;
    private final RawStoreFactory rawStoreFactory;
    private final String databaseIdentity;
    private final boolean readOnly;
    private final boolean enabled;
    private final int changedRowsThreshold;
    private final long periodMillis;
    private final Map<ContainerKey, TableTarget> targets = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<MaintenanceTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicLong taskSequence = new AtomicLong();
    private final AtomicLong commitWakeupCount = new AtomicLong();
    private final AtomicLong notificationFailureCount = new AtomicLong();
    private final AtomicLong periodicScanCount = new AtomicLong();
    private final AtomicLong scheduledRunCount = new AtomicLong();
    private final AtomicLong completedRunCount = new AtomicLong();
    private final AtomicLong skippedRunCount = new AtomicLong();
    private final AtomicLong failedRunCount = new AtomicLong();
    private final AtomicLong mutatedRunCount = new AtomicLong();
    private final AtomicLong removedVersionCount = new AtomicLong();
    private final AtomicLong removedLogicalRowCount = new AtomicLong();
    private final AtomicInteger activeWorkerCount = new AtomicInteger();
    private final AtomicInteger maximumActiveWorkerCount = new AtomicInteger();
    private final Thread worker;
    private final ScheduledExecutorService periodicScanner;

    MvccRawStoreMaintenanceService(
            String databaseIdentity,
            RawStoreFactory rawStoreFactory,
            MvccRawStoreRuntime runtime,
            boolean readOnly,
            Properties serviceProperties) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.rawStoreFactory = Objects.requireNonNull(rawStoreFactory, "rawStoreFactory");
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        this.readOnly = readOnly;
        this.enabled = Boolean.parseBoolean(
                configuredValue(serviceProperties, ENABLED_PROPERTY, "false"))
                && !readOnly;
        this.changedRowsThreshold = configuredChangedRowsThreshold(serviceProperties);
        this.periodMillis = configuredPeriodMillis(serviceProperties);
        this.accepting.set(enabled);
        if (enabled) {
            String threadIdentity = Integer.toHexString(this.databaseIdentity.hashCode());
            this.worker = Thread.ofVirtual()
                    .name("delosdb-rawstore-mvcc-maintenance-" + threadIdentity)
                    .unstarted(this::workerLoop);
            this.periodicScanner = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual()
                            .name("delosdb-rawstore-mvcc-maintenance-scan-" + threadIdentity)
                            .factory());
            worker.start();
            periodicScanner.scheduleWithFixedDelay(
                    this::scanPeriodic,
                    periodMillis,
                    periodMillis,
                    TimeUnit.MILLISECONDS);
        } else {
            this.worker = null;
            this.periodicScanner = null;
        }
    }

    boolean enabled() {
        return enabled;
    }

    void register(MvccRawStoreTable.Descriptor table) {
        try {
            registerInternal(table);
        } catch (RuntimeException notificationFailure) {
            notificationFailureCount.incrementAndGet();
        }
    }

    private void registerInternal(MvccRawStoreTable.Descriptor table) {
        Objects.requireNonNull(table, "table");
        TableTarget created = new TableTarget(table);
        TableTarget existing = targets.putIfAbsent(table.metadataContainer(), created);
        TableTarget target = existing == null ? created : existing;
        if (existing != null) {
            existing.observeDescriptor(table);
        } else if (enabled) {
            target.request(Trigger.REGISTER, 0L);
        }
    }

    void unregister(MvccRawStoreTable.Descriptor table) {
        try {
            if (table == null) {
                return;
            }
            TableTarget target = targets.remove(table.metadataContainer());
            if (target != null) {
                target.deactivate();
            }
        } catch (RuntimeException notificationFailure) {
            notificationFailureCount.incrementAndGet();
        }
    }

    void afterCommit(List<MvccRawStoreTable.PendingVersion> committed) {
        try {
            afterCommitInternal(committed);
        } catch (RuntimeException notificationFailure) {
            // This callback runs after the inherited RawStore commit.
            // Scheduling evidence must never change the user transaction's committed outcome.
            notificationFailureCount.incrementAndGet();
        }
    }

    private void afterCommitInternal(List<MvccRawStoreTable.PendingVersion> committed) {
        if (committed == null || committed.isEmpty()) {
            return;
        }
        Map<ContainerKey, CommitChanges> changes = new HashMap<>();
        for (MvccRawStoreTable.PendingVersion pending : committed) {
            changes.compute(pending.table().metadataContainer(), (ignored, current) -> {
                if (current == null) {
                    return new CommitChanges(pending.table(), 1L);
                }
                return new CommitChanges(
                        current.table(), saturatingAdd(current.changedRows(), 1L));
            });
        }
        for (CommitChanges change : changes.values()) {
            registerInternal(change.table());
            TableTarget target = targets.get(change.table().metadataContainer());
            if (target != null) {
                commitWakeupCount.incrementAndGet();
                target.request(Trigger.COMMIT, change.changedRows());
            }
        }
    }

    TableStorageSnapshot tableStorageSnapshot(ContainerKey metadataContainer) {
        TableTarget target = targets.get(Objects.requireNonNull(
                metadataContainer, "metadataContainer"));
        if (target == null) {
            throw new IllegalArgumentException(
                    "No registered RawStore MVCC table for " + metadataContainer);
        }

        ContextService contextService = ContextService.getFactory();
        ContextManager contextManager = contextService.newContextManager();
        contextService.setCurrentContextManager(contextManager);
        try {
            return captureTableStorageSnapshot(target.descriptor(), contextManager);
        } finally {
            contextService.resetCurrentContextManager(contextManager);
            StandardException close = StandardException.newException(SQLState.CLOSE_REQUEST);
            close.setSeverity(ExceptionSeverity.SESSION_SEVERITY);
            contextManager.cleanupOnError(close, false);
        }
    }

    private TableStorageSnapshot captureTableStorageSnapshot(
            MvccRawStoreTable.Descriptor table,
            ContextManager contextManager) {
        Transaction transaction = null;
        boolean transactionIdle = false;
        try {
            transaction = rawStoreFactory.startTransaction(
                    contextManager, DIAGNOSTICS_TRANSACTION_NAME);
            TableStorageSnapshot snapshot = inspectContainer(
                    transaction, table.metadataContainer())
                    .plus(inspectContainer(transaction, table.versionContainer()))
                    .plus(inspectContainer(transaction, table.orderedIndexContainer()));
            transaction.commit();
            transactionIdle = true;
            return snapshot;
        } catch (StandardException failure) {
            if (transaction != null && !transactionIdle) {
                try {
                    transaction.abort();
                    transactionIdle = true;
                } catch (StandardException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            throw new IllegalStateException(
                    "Could not inspect RawStore MVCC table " + table.metadataContainer(),
                    failure);
        } finally {
            if (transaction != null) {
                try {
                    if (transactionIdle) {
                        transaction.close();
                    } else {
                        transaction.destroy();
                    }
                } catch (StandardException closeFailure) {
                    throw new IllegalStateException(
                            "Could not close RawStore MVCC diagnostics transaction",
                            closeFailure);
                }
            }
        }
    }

    private static TableStorageSnapshot inspectContainer(
            Transaction transaction,
            ContainerKey containerKey) throws StandardException {
        if (containerKey == null) {
            return TableStorageSnapshot.EMPTY;
        }
        ContainerHandle container = transaction.openContainer(
                containerKey,
                MvccRawStorePhysicalLocking.rowLevel(transaction),
                ContainerHandle.MODE_READONLY);
        if (container == null) {
            throw new IllegalStateException(
                    "RawStore MVCC container is absent: " + containerKey);
        }
        Page page = null;
        long nonOverflowPageCount = 0L;
        try {
            SpaceInfo spaceInfo = container.getSpaceInfo();
            long allocatedPageCount = spaceInfo.getNumAllocatedPages();
            long reusablePageCount = spaceInfo.getNumFreePages();
            page = container.getFirstPage();
            while (page != null) {
                nonOverflowPageCount++;
                long pageNumber = page.getPageNumber();
                page.unlatch();
                page = container.getNextPage(pageNumber);
            }
            long overflowPageCount = Math.max(
                    0L, allocatedPageCount - nonOverflowPageCount);
            return new TableStorageSnapshot(
                    allocatedPageCount, overflowPageCount, reusablePageCount);
        } finally {
            if (page != null) {
                page.unlatch();
            }
            container.close();
        }
    }

    Snapshot snapshot() {
        List<TableTarget> ordered = targets.values().stream()
                .sorted(Comparator
                        .comparingLong((TableTarget target) ->
                                target.descriptor().metadataContainer().getSegmentId())
                        .thenComparingLong(target ->
                                target.descriptor().metadataContainer().getContainerId()))
                .toList();
        int queued = 0;
        long oldestQueuedAt = 0L;
        long capturedAt = System.currentTimeMillis();
        long dropped = Math.max(0L, ordered.size() - TABLE_SNAPSHOT_CAPACITY);
        List<DelosStorageMaintenanceTableSnapshot> tableSnapshots = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            TableTarget target = ordered.get(index);
            long queuedAt = target.queuedAtEpochMillis();
            if (queuedAt > 0L) {
                queued++;
                oldestQueuedAt = oldestQueuedAt == 0L
                        ? queuedAt
                        : Math.min(oldestQueuedAt, queuedAt);
            }
            if (index < TABLE_SNAPSHOT_CAPACITY) {
                tableSnapshots.add(target.snapshot());
            }
        }
        return new Snapshot(
                enabled,
                readOnly,
                accepting.get(),
                enabled ? 1 : 0,
                ordered.size(),
                queued,
                oldestQueuedAt,
                oldestQueuedAt == 0L ? 0L : Math.max(0L, capturedAt - oldestQueuedAt),
                activeWorkerCount.get(),
                maximumActiveWorkerCount.get(),
                commitWakeupCount.get(),
                notificationFailureCount.get(),
                periodicScanCount.get(),
                scheduledRunCount.get(),
                completedRunCount.get(),
                skippedRunCount.get(),
                failedRunCount.get(),
                mutatedRunCount.get(),
                removedVersionCount.get(),
                removedLogicalRowCount.get(),
                TABLE_SNAPSHOT_CAPACITY,
                dropped,
                tableSnapshots);
    }

    private void scanPeriodic() {
        if (!accepting.get()) {
            return;
        }
        periodicScanCount.incrementAndGet();
        for (TableTarget target : targets.values()) {
            try {
                if (target.periodicEligible()) {
                    target.request(Trigger.PERIODIC, 0L);
                }
            } catch (RuntimeException notificationFailure) {
                notificationFailureCount.incrementAndGet();
            }
        }
    }

    private void workerLoop() {
        ContextService contextService = ContextService.getFactory();
        ContextManager contextManager = contextService.newContextManager();
        try {
            while (accepting.get() || !queue.isEmpty()) {
                MaintenanceTask task;
                try {
                    task = queue.poll(250L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    if (!accepting.get()) {
                        break;
                    }
                    continue;
                }
                if (task == null) {
                    continue;
                }
                contextService.setCurrentContextManager(contextManager);
                try {
                    task.runWithContext(contextManager);
                } finally {
                    contextService.resetCurrentContextManager(contextManager);
                }
            }
        } finally {
            StandardException close = StandardException.newException(SQLState.CLOSE_REQUEST);
            close.setSeverity(ExceptionSeverity.SESSION_SEVERITY);
            contextManager.cleanupOnError(close, false);
        }
    }

    private void runMaintenance(
            TableTarget target,
            Trigger trigger,
            ContextManager contextManager) {
        if (!target.beginRun(trigger)) {
            return;
        }
        int active = activeWorkerCount.incrementAndGet();
        maximumActiveWorkerCount.accumulateAndGet(active, Math::max);
        Transaction transaction = null;
        boolean transactionIdle = false;
        ContainerKey replacement = null;
        MvccRawStoreVacuum.Result result = null;
        long horizon = 0L;
        try {
            MvccRawStoreTable.Descriptor table = target.descriptor();
            transaction = rawStoreFactory.startTransaction(contextManager, TRANSACTION_NAME);
            if (!runtime.tryLockExclusive(transaction, MvccRawStoreLogicalLock.table(table))) {
                transaction.abort();
                transactionIdle = true;
                skippedRunCount.incrementAndGet();
                target.completeSkipped(trigger, "table-busy");
                return;
            }
            try (MvccRawStoreRuntime.TableMaintenanceBoundary ignored =
                         runtime.enterVacuum(table)) {
                horizon = runtime.vacuumHorizon();
                result = MvccRawStoreVacuum.vacuum(transaction, table, horizon);
                if (result.requiresOrderedIndexReplacement()) {
                    replacement = MvccRawStoreOrderedIndex.createPrivateGeneration(
                            transaction, table);
                    MvccRawStoreTable.rebuildOrderedIndexForMaintenance(
                            transaction, table, replacement);
                    ContainerKey replaced = MvccRawStoreTable.publishOrderedIndexContainer(
                            transaction, table, replacement);
                    if (replaced != null) {
                        transaction.dropContainer(replaced);
                    }
                }
                transaction.commit();
                transactionIdle = true;
            }
            if (replacement != null) {
                target.descriptor().observeOrderedIndexContainer(replacement);
            }
            completedRunCount.incrementAndGet();
            if (result.mutated()) {
                mutatedRunCount.incrementAndGet();
                removedVersionCount.addAndGet(result.removedVersions());
                removedLogicalRowCount.addAndGet(result.removedLogicalRows());
            } else {
                skippedRunCount.incrementAndGet();
            }
            target.completeSuccess(trigger, horizon, result);
        } catch (StandardException | RuntimeException failure) {
            failedRunCount.incrementAndGet();
            if (transaction != null && !transactionIdle) {
                try {
                    transaction.abort();
                    transactionIdle = true;
                } catch (StandardException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            target.completeFailure(trigger, failure);
        } finally {
            if (transaction != null) {
                try {
                    if (transactionIdle) {
                        transaction.close();
                    } else {
                        transaction.destroy();
                    }
                } catch (StandardException closeFailure) {
                    failedRunCount.incrementAndGet();
                    target.completeFailure(trigger, closeFailure);
                }
            }
            activeWorkerCount.decrementAndGet();
            target.finishRun();
        }
    }

    record TableStorageSnapshot(
            long pageCount,
            long overflowPageCount,
            long reusablePageCount) {
        private static final TableStorageSnapshot EMPTY =
                new TableStorageSnapshot(0L, 0L, 0L);

        TableStorageSnapshot {
            if (pageCount < 0L
                    || overflowPageCount < 0L
                    || reusablePageCount < 0L
                    || overflowPageCount > pageCount) {
                throw new IllegalArgumentException(
                        "Invalid RawStore MVCC table-storage snapshot");
            }
        }

        TableStorageSnapshot plus(TableStorageSnapshot other) {
            Objects.requireNonNull(other, "other");
            return new TableStorageSnapshot(
                    Math.addExact(pageCount, other.pageCount),
                    Math.addExact(overflowPageCount, other.overflowPageCount),
                    Math.addExact(reusablePageCount, other.reusablePageCount));
        }
    }

    private boolean enqueue(TableTarget target, Trigger trigger, long ignoredPriority) {
        if (!accepting.get()) {
            return false;
        }
        queue.add(new MaintenanceTask(
                target,
                trigger,
                taskSequence.incrementAndGet()));
        scheduledRunCount.incrementAndGet();
        return true;
    }

    @Override
    public void close() {
        if (!enabled) {
            targets.values().forEach(TableTarget::deactivate);
            targets.clear();
            return;
        }
        accepting.set(false);
        periodicScanner.shutdownNow();
        queue.clear();
        targets.values().forEach(TableTarget::deactivate);
        worker.interrupt();
        boolean interrupted = false;
        try {
            if (!periodicScanner.awaitTermination(
                    SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        "RawStore MVCC maintenance scanner did not terminate for "
                                + databaseIdentity);
            }
            worker.join(SHUTDOWN_TIMEOUT.toMillis());
            if (worker.isAlive()) {
                throw new IllegalStateException(
                        "RawStore MVCC maintenance worker did not terminate for "
                                + databaseIdentity);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            throw new IllegalStateException(
                    "Interrupted while closing RawStore MVCC maintenance for "
                            + databaseIdentity,
                    e);
        } finally {
            targets.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class MaintenanceTask {
        private final TableTarget target;
        private final Trigger trigger;
        @SuppressWarnings("unused")
        private final long sequence;

        private MaintenanceTask(
                TableTarget target,
                Trigger trigger,
                long sequence) {
            this.target = target;
            this.trigger = trigger;
            this.sequence = sequence;
        }

        private void runWithContext(ContextManager contextManager) {
            runMaintenance(target, trigger, contextManager);
        }
    }

    private final class TableTarget {
        private MvccRawStoreTable.Descriptor table;
        private State state = State.IDLE;
        private boolean active = true;
        private boolean rerunRequested;
        private boolean retryRequired = true;
        private long committedChangesSinceLastRun;
        private long queuedAtEpochMillis;
        private long scheduleCount;
        private long runCount;
        private long skipCount;
        private long failureCount;
        private String lastTrigger = "NONE";
        private String lastDecision = "registered";
        private long lastVacuumHorizon;
        private long lastStartedAtEpochMillis;
        private long lastCompletedAtEpochMillis;
        private int lastRemovedVersions;
        private int lastRemovedLogicalRows;
        private int remainingVersions;
        private int remainingLogicalRows;

        private TableTarget(MvccRawStoreTable.Descriptor table) {
            this.table = table;
        }

        synchronized void observeDescriptor(MvccRawStoreTable.Descriptor descriptor) {
            table = descriptor;
            active = true;
        }

        synchronized MvccRawStoreTable.Descriptor descriptor() {
            return table;
        }

        synchronized boolean request(Trigger trigger, long committedChanges) {
            committedChangesSinceLastRun = saturatingAdd(
                    committedChangesSinceLastRun,
                    Math.max(0L, committedChanges));
            if (!active || !accepting.get()) {
                return false;
            }
            if (trigger == Trigger.COMMIT
                    && committedChangesSinceLastRun < changedRowsThreshold
                    && !retryRequired) {
                lastTrigger = trigger.name();
                lastDecision = "changed-rows-below-threshold-" + changedRowsThreshold;
                return false;
            }
            if (state != State.IDLE) {
                rerunRequested = true;
                return false;
            }
            state = State.QUEUED;
            queuedAtEpochMillis = System.currentTimeMillis();
            scheduleCount++;
            lastTrigger = trigger.name();
            if (!enqueue(this, trigger, committedChangesSinceLastRun)) {
                state = State.IDLE;
                return false;
            }
            return true;
        }

        synchronized boolean beginRun(Trigger trigger) {
            if (!active || state != State.QUEUED) {
                state = State.IDLE;
                return false;
            }
            state = State.RUNNING;
            queuedAtEpochMillis = 0L;
            runCount++;
            lastTrigger = trigger.name();
            lastStartedAtEpochMillis = System.currentTimeMillis();
            return true;
        }

        synchronized void completeSuccess(
                Trigger trigger,
                long horizon,
                MvccRawStoreVacuum.Result result) {
            lastTrigger = trigger.name();
            lastVacuumHorizon = horizon;
            lastCompletedAtEpochMillis = System.currentTimeMillis();
            lastRemovedVersions = result.removedVersions();
            lastRemovedLogicalRows = result.removedLogicalRows();
            remainingVersions = result.remainingVersions();
            remainingLogicalRows = result.remainingLogicalRows();
            committedChangesSinceLastRun = 0L;
            retryRequired = remainingVersions > remainingLogicalRows;
            if (result.mutated()) {
                lastDecision = retryRequired
                        ? "vacuumed-retained-history-remains"
                        : "vacuumed";
            } else {
                skipCount++;
                lastDecision = retryRequired
                        ? "retained-snapshot-protects-history"
                        : "no-reclaimable-history";
            }
        }

        synchronized void completeSkipped(Trigger trigger, String decision) {
            skipCount++;
            retryRequired = true;
            lastTrigger = trigger.name();
            lastDecision = decision;
            lastCompletedAtEpochMillis = System.currentTimeMillis();
        }

        synchronized void completeFailure(Trigger trigger, Throwable failure) {
            failureCount++;
            retryRequired = true;
            lastTrigger = trigger.name();
            lastDecision = "failed-" + failure.getClass().getSimpleName();
            lastCompletedAtEpochMillis = System.currentTimeMillis();
        }

        synchronized void finishRun() {
            if (!active) {
                state = State.IDLE;
                rerunRequested = false;
                notifyAll();
                return;
            }
            if (accepting.get() && rerunRequested) {
                rerunRequested = false;
                state = State.QUEUED;
                queuedAtEpochMillis = System.currentTimeMillis();
                scheduleCount++;
                if (enqueue(this, Trigger.COMMIT, committedChangesSinceLastRun)) {
                    return;
                }
            }
            state = State.IDLE;
            queuedAtEpochMillis = 0L;
            notifyAll();
        }

        synchronized boolean periodicEligible() {
            return active && state == State.IDLE
                    && (retryRequired || committedChangesSinceLastRun >= changedRowsThreshold);
        }

        synchronized long queuedAtEpochMillis() {
            return state == State.QUEUED ? queuedAtEpochMillis : 0L;
        }

        synchronized void deactivate() {
            active = false;
            retryRequired = false;
            rerunRequested = false;
            if (state == State.QUEUED) {
                state = State.IDLE;
                queuedAtEpochMillis = 0L;
            }
            notifyAll();
        }

        synchronized DelosStorageMaintenanceTableSnapshot snapshot() {
            ContainerKey ordered = table.orderedIndexContainer();
            return new DelosStorageMaintenanceTableSnapshot(
                    DelosStorageMaintenanceTableSnapshot.CURRENT_SCHEMA_VERSION,
                    table.metadataContainer().getSegmentId(),
                    table.metadataContainer().getContainerId(),
                    table.versionContainer().getContainerId(),
                    ordered == null ? 0L : ordered.getContainerId(),
                    active,
                    state == State.QUEUED,
                    state == State.RUNNING,
                    retryRequired,
                    committedChangesSinceLastRun,
                    queuedAtEpochMillis,
                    scheduleCount,
                    runCount,
                    skipCount,
                    failureCount,
                    lastTrigger,
                    lastDecision,
                    lastVacuumHorizon,
                    lastStartedAtEpochMillis,
                    lastCompletedAtEpochMillis,
                    lastRemovedVersions,
                    lastRemovedLogicalRows,
                    remainingVersions,
                    remainingLogicalRows);
        }
    }

    record Snapshot(
            boolean enabled,
            boolean readOnly,
            boolean accepting,
            int workerCount,
            int registeredTableCount,
            int queuedTableCount,
            long oldestQueuedAtEpochMillis,
            long oldestQueuedAgeMillis,
            int activeWorkerCount,
            int maximumActiveWorkerCount,
            long commitWakeupCount,
            long notificationFailureCount,
            long periodicScanCount,
            long scheduledRunCount,
            long completedRunCount,
            long skippedRunCount,
            long failedRunCount,
            long mutatedRunCount,
            long removedVersionCount,
            long removedLogicalRowCount,
            int tableSnapshotCapacity,
            long tableSnapshotDroppedCount,
            List<DelosStorageMaintenanceTableSnapshot> tableSnapshots) {
        Snapshot {
            tableSnapshots = List.copyOf(tableSnapshots);
        }
    }

    private record CommitChanges(MvccRawStoreTable.Descriptor table, long changedRows) {
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int configuredChangedRowsThreshold(Properties serviceProperties) {
        String configured = configuredValue(
                serviceProperties, CHANGED_ROWS_THRESHOLD_PROPERTY, null);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_CHANGED_ROWS_THRESHOLD;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_CHANGED_ROWS_THRESHOLD;
        }
    }

    private static long configuredPeriodMillis(Properties serviceProperties) {
        String configured = configuredValue(serviceProperties, PERIOD_MILLIS_PROPERTY, null);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PERIOD_MILLIS;
        }
        try {
            return Math.max(10L, Long.parseLong(configured.trim()));
        } catch (NumberFormatException ignored) {
            return DEFAULT_PERIOD_MILLIS;
        }
    }
    private static String configuredValue(
            Properties serviceProperties,
            String property,
            String defaultValue) {
        String serviceValue = serviceProperties == null
                ? null
                : serviceProperties.getProperty(property);
        if (serviceValue != null) {
            return serviceValue.trim();
        }
        String systemValue = System.getProperty(property);
        return systemValue == null ? defaultValue : systemValue.trim();
    }

}
