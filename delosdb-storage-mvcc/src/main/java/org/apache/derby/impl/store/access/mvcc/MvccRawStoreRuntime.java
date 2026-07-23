/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreRuntime

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.LockFactory;
import org.apache.derby.iapi.services.locks.ShExQual;

import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.RawStoreFactory;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.DelosDatabaseMemorySnapshot;
import org.apache.derby.iapi.store.types.DelosRawStoreIoMetrics;
import org.apache.derby.iapi.store.types.DelosRawStoreIoSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageMaintenanceSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageProviderIds;
import org.apache.derby.io.DatabaseMemoryStorage;
import org.apache.derby.shared.common.error.StandardException;

/** Database-scoped semantic coordinator for the isolated RawStore table format. */
final class MvccRawStoreRuntime {
    static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    static final String AFTER_STAMP_BEFORE_RAW_COMMIT =
            "after-stamp-before-raw-commit";
    static final String AFTER_RAW_COMMIT_BEFORE_PUBLICATION =
            "after-raw-commit-before-publication";
    static final String AFTER_VACUUM_BEFORE_RAW_COMMIT =
            "after-vacuum-before-raw-commit";
    static final String AFTER_VACUUM_RAW_COMMIT_BEFORE_PUBLICATION =
            "after-vacuum-raw-commit-before-publication";

    private final Object databaseIdentity;
    private final LockFactory lockFactory;
    private final DatabaseMemoryStorage memoryStorage;
    private final DelosRawStoreIoMetrics rawStoreIoMetrics;
    private final ReentrantLock commitPublicationLock = new ReentrantLock();
    private final MvccRawStoreDatabaseMetadata metadata = new MvccRawStoreDatabaseMetadata();
    private final AtomicLong publishedHighWater = new AtomicLong();
    private final AtomicLong diagnosticCaptureSequence = new AtomicLong();
    private final AtomicLong nextSnapshotLeaseId = new AtomicLong(1L);
    private final Map<Long, Long> retainedSnapshotSequences = new HashMap<>();
    private final Map<Long, TableIdentityAllocator> tableIdentityAllocators = new HashMap<>();
    private final Map<ContainerKey, ReentrantReadWriteLock> tableMaintenanceBoundaries =
            new ConcurrentHashMap<>();
    private final Set<Long> activeTransactionIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String diagnosticIdentity = "<unbound>";
    private volatile MvccRawStoreMaintenanceService maintenanceService;

    MvccRawStoreRuntime(
            Object databaseIdentity,
            LockFactory lockFactory,
            DatabaseMemoryStorage memoryStorage,
            DelosRawStoreIoMetrics rawStoreIoMetrics,
            String diagnosticIdentity) {
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        this.lockFactory = Objects.requireNonNull(lockFactory, "lockFactory");
        this.memoryStorage = memoryStorage;
        this.rawStoreIoMetrics = Objects.requireNonNull(
                rawStoreIoMetrics, "rawStoreIoMetrics");
        this.diagnosticIdentity = Objects.requireNonNull(
                diagnosticIdentity, "diagnosticIdentity");
    }

    Object databaseIdentity() {
        return databaseIdentity;
    }

    synchronized void startMaintenance(
            String databaseIdentity,
            RawStoreFactory rawStoreFactory,
            boolean readOnly,
            Properties serviceProperties) {
        if (maintenanceService != null) {
            throw new IllegalStateException("RawStore MVCC maintenance is already started");
        }
        diagnosticIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        maintenanceService = new MvccRawStoreMaintenanceService(
                diagnosticIdentity, rawStoreFactory, this, readOnly, serviceProperties);
    }

    void registerTable(MvccRawStoreTable.Descriptor table) {
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        if (maintenance != null) {
            maintenance.register(table);
        }
    }

    void unregisterTable(MvccRawStoreTable.Descriptor table) {
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        if (maintenance != null) {
            maintenance.unregister(table);
        }
    }

    void afterUserCommit(List<MvccRawStoreTable.PendingVersion> committed) {
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        if (maintenance != null) {
            maintenance.afterCommit(committed);
        }
    }

    MvccRawStoreTransactionContext context(
            TransactionManager transactionManager,
            Transaction rawTransaction) throws StandardException {
        AccessMethodTransactionLifecycle existing =
                transactionManager.accessMethodTransactionLifecycle(this);
        if (existing != null) {
            return (MvccRawStoreTransactionContext) existing;
        }
        ensureMetadata(transactionManager);
        MvccRawStoreTransactionContext context = new MvccRawStoreTransactionContext(
                this,
                transactionManager,
                rawTransaction);
        transactionManager.registerAccessMethodTransactionLifecycle(this, context);
        return context;
    }

    void ensureMetadata(TransactionManager transactionManager) throws StandardException {
        long committedHighWater = metadata.ensureInitialized(transactionManager);
        if (committedHighWater >= 0L) {
            observeCommittedHighWater(committedHighWater);
        }
    }

    void lockShared(Transaction transaction, MvccRawStoreLogicalLock lock)
            throws StandardException {
        lock(transaction, lock, ShExQual.SH);
    }

    void lockExclusive(Transaction transaction, MvccRawStoreLogicalLock lock)
            throws StandardException {
        lock(transaction, lock, ShExQual.EX);
    }

    boolean tryLockExclusive(Transaction transaction, MvccRawStoreLogicalLock lock)
            throws StandardException {
        return lockFactory.lockObject(
                transaction.getCompatibilitySpace(),
                transaction,
                lock,
                ShExQual.EX,
                C_LockFactory.NO_WAIT);
    }

    private void lock(Transaction transaction, MvccRawStoreLogicalLock lock, Object qualifier)
            throws StandardException {
        lockFactory.lockObject(
                transaction.getCompatibilitySpace(),
                transaction,
                lock,
                qualifier,
                C_LockFactory.TIMED_WAIT);
    }

    long reserveTransactionId(Transaction rawTransaction) throws StandardException {
        long transactionId = metadata.reserveTransactionId(rawTransaction);
        if (!activeTransactionIds.add(transactionId)) {
            throw new IllegalStateException(
                    "RawStore MVCC transaction identity is already active: " + transactionId);
        }
        return transactionId;
    }

    boolean isTransactionActive(long transactionId) {
        return transactionId > 0L && activeTransactionIds.contains(transactionId);
    }

    void retireTransaction(long transactionId) {
        if (transactionId > 0L) {
            activeTransactionIds.remove(transactionId);
        }
    }

    synchronized MvccRawStoreTable.Allocation reserveInsertIdentifiers(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        TableIdentityAllocator allocator = tableIdentityAllocator(transaction, table);
        return new MvccRawStoreTable.Allocation(
                allocator.nextRowId++,
                allocator.nextVersionId++);
    }

    synchronized long reserveVersionIdentifier(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        return tableIdentityAllocator(transaction, table).nextVersionId++;
    }

    private TableIdentityAllocator tableIdentityAllocator(
            Transaction transaction,
            MvccRawStoreTable.Descriptor table) throws StandardException {
        long tableId = table.metadataContainer().getContainerId();
        TableIdentityAllocator existing = tableIdentityAllocators.get(tableId);
        if (existing != null) {
            return existing;
        }
        MvccRawStoreTable.AllocatorHighWater persisted =
                MvccRawStoreTable.readAllocatorHighWater(transaction, table);
        TableIdentityAllocator created = new TableIdentityAllocator(
                persisted.nextRowId(),
                persisted.nextVersionId());
        tableIdentityAllocators.put(tableId, created);
        return created;
    }

    long captureSnapshot() {
        commitPublicationLock.lock();
        try {
            return publishedHighWater.get();
        } finally {
            commitPublicationLock.unlock();
        }
    }

    SnapshotLease openSnapshotLease() {
        commitPublicationLock.lock();
        try {
            long sequence = publishedHighWater.get();
            return registerSnapshotLease(sequence);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    SnapshotLease retainSnapshot(long sequence) {
        if (sequence < 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC retained snapshot must be committed: " + sequence);
        }
        commitPublicationLock.lock();
        try {
            long published = publishedHighWater.get();
            if (sequence > published) {
                throw new IllegalArgumentException(
                        "RawStore MVCC retained snapshot is ahead of publication: "
                                + sequence + " > " + published);
            }
            return registerSnapshotLease(sequence);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    long vacuumHorizon() {
        commitPublicationLock.lock();
        try {
            long horizon = publishedHighWater.get();
            for (long retained : retainedSnapshotSequences.values()) {
                horizon = Math.min(horizon, retained);
            }
            return horizon;
        } finally {
            commitPublicationLock.unlock();
        }
    }

    TableReadBoundary enterTableRead(MvccRawStoreTable.Descriptor table) {
        ReentrantReadWriteLock.ReadLock readLock = tableMaintenanceBoundary(table).readLock();
        readLock.lock();
        return new TableReadBoundary(readLock);
    }

    TableMaintenanceBoundary enterVacuum(MvccRawStoreTable.Descriptor table) {
        ReentrantReadWriteLock.WriteLock writeLock = tableMaintenanceBoundary(table).writeLock();
        writeLock.lock();
        return new TableMaintenanceBoundary(writeLock);
    }

    private SnapshotLease registerSnapshotLease(long sequence) {
        long leaseId = nextSnapshotLeaseId.getAndIncrement();
        retainedSnapshotSequences.put(leaseId, sequence);
        return new SnapshotLease(this, leaseId, sequence);
    }

    private void closeSnapshotLease(long leaseId) {
        commitPublicationLock.lock();
        try {
            retainedSnapshotSequences.remove(leaseId);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    private ReentrantReadWriteLock tableMaintenanceBoundary(
            MvccRawStoreTable.Descriptor table) {
        return tableMaintenanceBoundaries.computeIfAbsent(
                table.metadataContainer(),
                ignored -> new ReentrantReadWriteLock(true));
    }

    long reserveCommitSequence(Transaction rawTransaction) throws StandardException {
        commitPublicationLock.lock();
        try {
            return metadata.reserveCommitSequence(rawTransaction);
        } catch (StandardException | RuntimeException | Error failure) {
            commitPublicationLock.unlock();
            throw failure;
        }
    }

    void stageCommittedHighWater(Transaction rawTransaction, long sequence)
            throws StandardException {
        metadata.stageCommittedHighWater(rawTransaction, sequence);
    }

    void publishAndUnlock(long sequence) {
        publishedHighWater.accumulateAndGet(sequence, Math::max);
        commitPublicationLock.unlock();
    }

    void unlockWithoutPublication() {
        if (commitPublicationLock.isHeldByCurrentThread()) {
            commitPublicationLock.unlock();
        }
    }

    void observeCommittedHighWater(long sequence) {
        publishedHighWater.accumulateAndGet(sequence, Math::max);
    }


    MvccRawStoreMaintenanceService.TableStorageSnapshot tableStorageSnapshot(
            int segment,
            long containerId) {
        if (segment < 0 || containerId < 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC container identity must not be negative: "
                            + segment + ':' + containerId);
        }
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        if (maintenance == null || closed.get()) {
            throw new IllegalStateException(
                    "RawStore MVCC runtime is not active for " + diagnosticIdentity);
        }
        return maintenance.tableStorageSnapshot(new ContainerKey(segment, containerId));
    }

    DelosStorageMaintenanceSnapshot maintenanceSnapshot() {
        long published;
        long horizon;
        int retainedCount;
        commitPublicationLock.lock();
        try {
            published = publishedHighWater.get();
            horizon = published;
            retainedCount = retainedSnapshotSequences.size();
            for (long retained : retainedSnapshotSequences.values()) {
                horizon = Math.min(horizon, retained);
            }
        } finally {
            commitPublicationLock.unlock();
        }

        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        MvccRawStoreMaintenanceService.Snapshot snapshot = maintenance == null
                ? new MvccRawStoreMaintenanceService.Snapshot(
                        false, false, false, 0, 0, 0, 0L, 0L, 0, 0,
                        0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                        0, 0L, List.of())
                : maintenance.snapshot();
        return new DelosStorageMaintenanceSnapshot(
                DelosStorageMaintenanceSnapshot.CURRENT_SCHEMA_VERSION,
                DelosStorageProviderIds.MVCC_PROVIDER_ID,
                diagnosticIdentity,
                DelosStorageMaintenanceSnapshot.RAWSTORE_MVCC_MODE,
                DelosStorageMaintenanceSnapshot.IMMUTABLE_COLLECTION,
                diagnosticCaptureSequence.incrementAndGet(),
                System.currentTimeMillis(),
                !closed.get(),
                snapshot.enabled(),
                snapshot.readOnly(),
                snapshot.accepting(),
                snapshot.workerCount(),
                snapshot.registeredTableCount(),
                snapshot.queuedTableCount(),
                snapshot.oldestQueuedAtEpochMillis(),
                snapshot.oldestQueuedAgeMillis(),
                snapshot.activeWorkerCount(),
                snapshot.maximumActiveWorkerCount(),
                snapshot.commitWakeupCount(),
                snapshot.notificationFailureCount(),
                snapshot.periodicScanCount(),
                snapshot.scheduledRunCount(),
                snapshot.completedRunCount(),
                snapshot.skippedRunCount(),
                snapshot.failedRunCount(),
                snapshot.mutatedRunCount(),
                snapshot.removedVersionCount(),
                snapshot.removedLogicalRowCount(),
                published,
                horizon,
                retainedCount,
                horizon,
                activeTransactionIds.size(),
                snapshot.tableSnapshotCapacity(),
                snapshot.tableSnapshotDroppedCount(),
                snapshot.tableSnapshots());
    }

    DelosRawStoreIoSnapshot rawStoreIoSnapshot() {
        return rawStoreIoMetrics.snapshot();
    }

    DelosDatabaseMemorySnapshot memorySnapshot() {
        DatabaseMemoryStorage storage = memoryStorage;
        if (storage == null) {
            return new DelosDatabaseMemorySnapshot(
                    DelosDatabaseMemorySnapshot.CURRENT_SCHEMA_VERSION,
                    DelosStorageProviderIds.MVCC_PROVIDER_ID,
                    diagnosticIdentity,
                    !closed.get(),
                    false,
                    0L,
                    0L,
                    0L,
                    0L,
                    0);
        }
        return new DelosDatabaseMemorySnapshot(
                DelosDatabaseMemorySnapshot.CURRENT_SCHEMA_VERSION,
                DelosStorageProviderIds.MVCC_PROVIDER_ID,
                diagnosticIdentity,
                !closed.get(),
                true,
                storage.memoryLimitBytes(),
                storage.memoryUsedBytes(),
                storage.memoryPeakBytes(),
                storage.memoryRejectedGrowthCount(),
                storage.memoryEntryCount());
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        maintenanceService = null;
        if (maintenance != null) {
            maintenance.close();
        }
        tableIdentityAllocators.clear();
        tableMaintenanceBoundaries.clear();
        activeTransactionIds.clear();
        commitPublicationLock.lock();
        try {
            retainedSnapshotSequences.clear();
        } finally {
            commitPublicationLock.unlock();
        }
    }

    static final class SnapshotLease implements AutoCloseable {
        private final MvccRawStoreRuntime runtime;
        private final long leaseId;
        private final long sequence;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SnapshotLease(MvccRawStoreRuntime runtime, long leaseId, long sequence) {
            this.runtime = runtime;
            this.leaseId = leaseId;
            this.sequence = sequence;
        }

        long sequence() {
            return sequence;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                runtime.closeSnapshotLease(leaseId);
            }
        }
    }

    static final class TableReadBoundary implements AutoCloseable {
        private final ReentrantReadWriteLock.ReadLock readLock;
        private boolean closed;

        private TableReadBoundary(ReentrantReadWriteLock.ReadLock readLock) {
            this.readLock = readLock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                readLock.unlock();
            }
        }
    }

    static final class TableMaintenanceBoundary implements AutoCloseable {
        private final ReentrantReadWriteLock.WriteLock writeLock;
        private boolean closed;

        private TableMaintenanceBoundary(ReentrantReadWriteLock.WriteLock writeLock) {
            this.writeLock = writeLock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                writeLock.unlock();
            }
        }
    }

    private static final class TableIdentityAllocator {
        private long nextRowId;
        private long nextVersionId;

        private TableIdentityAllocator(long nextRowId, long nextVersionId) {
            this.nextRowId = nextRowId;
            this.nextVersionId = nextVersionId;
        }
    }

    static void haltAtFailurePoint(String failurePoint, int status) {
        if (failurePoint.equals(System.getProperty(FAILURE_POINT_PROPERTY))) {
            Runtime.getRuntime().halt(status);
        }
    }
}
