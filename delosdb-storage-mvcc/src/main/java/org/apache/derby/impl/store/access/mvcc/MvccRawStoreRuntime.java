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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.LockFactory;
import org.apache.derby.iapi.services.locks.ShExQual;

import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.access.AccessFactory;
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
    static final String COMMIT_SEQUENCE_RESERVATION_BLOCK_SIZE_PROPERTY =
            "delosdb.mvcc.rawStoreCommitSequenceReservationBlockSize";
    static final String CONCURRENT_COMMIT_PUBLICATION_PROPERTY =
            "delosdb.mvcc.rawStoreConcurrentCommitPublication";
    /** Fixed slot count shared by the permanent current-row anchor/image cache. */
    static final String CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY =
            "delosdb.mvcc.currentRowReadCache.slots";
    static final String EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_PROPERTY =
            "delosdb.experimental.mvccSnapshotLeaseRegistry";
    static final String EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_SLOTS_PROPERTY =
            "delosdb.experimental.mvccSnapshotLeaseRegistry.slots";
    private static final long FREE_SNAPSHOT_LEASE_SLOT = -1L;
    private static final long CLAIMING_SNAPSHOT_LEASE_SLOT = -2L;
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
    private final Condition commitPublicationAdvanced = commitPublicationLock.newCondition();
    private final MvccRawStoreDatabaseMetadata metadata = new MvccRawStoreDatabaseMetadata();
    private final AtomicLong publishedHighWater = new AtomicLong();
    private final AtomicLong diagnosticCaptureSequence = new AtomicLong();
    private final AtomicLong nextSnapshotLeaseId = new AtomicLong(1L);
    private final Map<Long, Long> retainedSnapshotSequences = new HashMap<>();
    private final boolean experimentalSnapshotLeaseRegistry;
    private final AtomicLongArray snapshotLeaseSlots;
    private final AtomicLong snapshotLeaseClaimCursor = new AtomicLong();
    private final Map<Long, TableIdentityAllocator> tableIdentityAllocators = new HashMap<>();
    private final Map<ContainerKey, ReentrantReadWriteLock> tableMaintenanceBoundaries =
            new ConcurrentHashMap<>();
    private final Set<Long> activeTransactionIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int commitSequenceReservationBlockSize;
    private final boolean concurrentCommitPublication;
    private final AtomicReferenceArray<CurrentRowAnchor> currentRowAnchors;
    private final AtomicReferenceArray<CurrentVersionReadImage> currentVersionReadImages;
    private final TreeSet<Long> terminalCommitSequences = new TreeSet<>();
    private long nextCommitSequence;
    private long commitSequenceReservationLimit;
    private long nextPublicationSequence = 1L;
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
        String blockSize = System.getProperty(
                COMMIT_SEQUENCE_RESERVATION_BLOCK_SIZE_PROPERTY, "64");
        try {
            commitSequenceReservationBlockSize = Integer.parseInt(blockSize);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    COMMIT_SEQUENCE_RESERVATION_BLOCK_SIZE_PROPERTY
                            + " must be a positive integer: " + blockSize,
                    failure);
        }
        if (commitSequenceReservationBlockSize <= 0) {
            throw new IllegalArgumentException(
                    COMMIT_SEQUENCE_RESERVATION_BLOCK_SIZE_PROPERTY
                            + " must be positive: " + blockSize);
        }
        concurrentCommitPublication = Boolean.parseBoolean(System.getProperty(
                CONCURRENT_COMMIT_PUBLICATION_PROPERTY, "true"));
        String slotText = System.getProperty(CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY, "4096");
        int slots;
        try {
            slots = Integer.parseInt(slotText);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY
                            + " must be a positive integer: " + slotText,
                    failure);
        }
        if (slots <= 0) {
            throw new IllegalArgumentException(
                    CURRENT_ROW_READ_CACHE_SLOTS_PROPERTY
                            + " must be positive: " + slotText);
        }
        currentRowAnchors = new AtomicReferenceArray<>(slots);
        currentVersionReadImages = new AtomicReferenceArray<>(slots);

        experimentalSnapshotLeaseRegistry = Boolean.parseBoolean(System.getProperty(
                EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_PROPERTY, "false"));
        if (experimentalSnapshotLeaseRegistry) {
            String leaseSlotText = System.getProperty(
                    EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_SLOTS_PROPERTY, "256");
            int leaseSlots;
            try {
                leaseSlots = Integer.parseInt(leaseSlotText);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_SLOTS_PROPERTY
                                + " must be a positive integer: " + leaseSlotText,
                        failure);
            }
            if (leaseSlots <= 0) {
                throw new IllegalArgumentException(
                        EXPERIMENTAL_SNAPSHOT_LEASE_REGISTRY_SLOTS_PROPERTY
                                + " must be positive: " + leaseSlotText);
            }
            snapshotLeaseSlots = new AtomicLongArray(leaseSlots);
            for (int index = 0; index < leaseSlots; index++) {
                snapshotLeaseSlots.set(index, FREE_SNAPSHOT_LEASE_SLOT);
            }
        } else {
            snapshotLeaseSlots = null;
        }
    }

    Object databaseIdentity() {
        return databaseIdentity;
    }

    synchronized void startMaintenance(
            String databaseIdentity,
            AccessFactory accessFactory,
            boolean readOnly,
            Properties serviceProperties) {
        if (maintenanceService != null) {
            throw new IllegalStateException("RawStore MVCC maintenance is already started");
        }
        diagnosticIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        maintenanceService = new MvccRawStoreMaintenanceService(
                diagnosticIdentity, accessFactory, this, readOnly, serviceProperties);
    }

    void registerTable(MvccRawStoreTable.Descriptor table) {
        MvccRawStoreMaintenanceService maintenance = maintenanceService;
        if (maintenance != null) {
            maintenance.register(table);
        }
    }

    void unregisterTable(MvccRawStoreTable.Descriptor table) {
        clearCurrentRowAnchors(table);
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


    MvccRawStoreTable.VersionRecord currentVersionReadImage(
            MvccRawStoreTable.Descriptor table,
            CurrentRowAnchor anchor) {
        AtomicReferenceArray<CurrentVersionReadImage> images = currentVersionReadImages;
        if (anchor == null) {
            return null;
        }
        int slot = currentRowAnchorSlot(table.metadataContainer(), anchor.rowId(), images.length());
        CurrentVersionReadImage image = images.get(slot);
        return image != null && image.anchor() == anchor ? image.version() : null;
    }

    void publishCurrentVersionReadImage(
            MvccRawStoreTable.Descriptor table,
            CurrentRowAnchor anchor,
            MvccRawStoreTable.VersionRecord version) {
        AtomicReferenceArray<CurrentVersionReadImage> images = currentVersionReadImages;
        AtomicReferenceArray<CurrentRowAnchor> anchors = currentRowAnchors;
        if (anchor == null || version == null
                || version.rowId() != anchor.rowId()
                || version.versionId() != anchor.versionId()
                || version.beginSequence() != anchor.beginSequence()
                || version.flags() != anchor.flags()) {
            return;
        }
        int slot = currentRowAnchorSlot(table.metadataContainer(), anchor.rowId(), images.length());
        if (anchors.get(slot) != anchor) {
            return;
        }
        CurrentVersionReadImage image = new CurrentVersionReadImage(anchor, version);
        images.set(slot, image);
        if (anchors.get(slot) != anchor) {
            images.compareAndSet(slot, image, null);
        }
    }

    CurrentRowAnchor currentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            long rowId) {
        AtomicReferenceArray<CurrentRowAnchor> anchors = currentRowAnchors;
        int slot = currentRowAnchorSlot(table.metadataContainer(), rowId, anchors.length());
        CurrentRowAnchor anchor = anchors.get(slot);
        return anchor != null
                && anchor.rowId() == rowId
                && anchor.tableKey().equals(table.metadataContainer())
                ? anchor
                : null;
    }

    void observeCurrentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.DirectoryRecord directory) {
        if (directory == null) {
            return;
        }
        MvccRawStoreTable.DirectoryHead head = directory.head();
        MvccRawStoreTable.DirectoryHeadSummary summary = head.summary();
        if (!summary.available()
                || summary.beginSequence() == MvccRawStoreFormat.UNCOMMITTED_SEQUENCE
                || !head.hint().valid()) {
            return;
        }
        putCurrentRowAnchor(new CurrentRowAnchor(
                table.metadataContainer(),
                directory.rowId(),
                head.versionId(),
                summary.beginSequence(),
                summary.flags(),
                head.hint(),
                MvccRawStoreRowDirectory.location(directory.rowId(), directory.handle())));
    }

    void publishCommittedAnchors(
            List<MvccRawStoreTable.PendingVersion> committed,
            long commitSequence) {
        if (commitSequence <= 0L) {
            return;
        }
        for (MvccRawStoreTable.PendingVersion pending : committed) {
            putCurrentRowAnchor(new CurrentRowAnchor(
                    pending.table().metadataContainer(),
                    pending.rowId(),
                    pending.versionId(),
                    commitSequence,
                    pending.flags(),
                    MvccRawStoreTable.RecordHint.of(pending.handle()),
                    pending.directoryLocation()));
        }
    }

    void invalidateCurrentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            long rowId,
            CurrentRowAnchor expected) {
        AtomicReferenceArray<CurrentRowAnchor> anchors = currentRowAnchors;
        if (expected == null) {
            return;
        }
        int slot = currentRowAnchorSlot(table.metadataContainer(), rowId, anchors.length());
        if (anchors.compareAndSet(slot, expected, null)) {
            clearCurrentVersionReadImage(slot, expected);
        }
    }

    private void putCurrentRowAnchor(CurrentRowAnchor anchor) {
        AtomicReferenceArray<CurrentRowAnchor> anchors = currentRowAnchors;
        int slot = currentRowAnchorSlot(anchor.tableKey(), anchor.rowId(), anchors.length());
        currentVersionReadImages.set(slot, null);
        anchors.set(slot, anchor);
    }

    private void clearCurrentVersionReadImage(int slot, CurrentRowAnchor expectedAnchor) {
        AtomicReferenceArray<CurrentVersionReadImage> images = currentVersionReadImages;
        CurrentVersionReadImage image = images.get(slot);
        if (image != null && image.anchor() == expectedAnchor) {
            images.compareAndSet(slot, image, null);
        }
    }

    private static int currentRowAnchorSlot(
            ContainerKey tableKey,
            long rowId,
            int length) {
        long mixed = rowId ^ (rowId >>> 33) ^ tableKey.hashCode();
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return Math.floorMod((int) mixed, length);
    }

    private void clearCurrentRowAnchors(MvccRawStoreTable.Descriptor table) {
        AtomicReferenceArray<CurrentRowAnchor> anchors = currentRowAnchors;
        ContainerKey tableKey = table.metadataContainer();
        for (int index = 0; index < anchors.length(); index++) {
            CurrentRowAnchor anchor = anchors.get(index);
            if (anchor != null && anchor.tableKey().equals(tableKey)
                    && anchors.compareAndSet(index, anchor, null)) {
                clearCurrentVersionReadImage(index, anchor);
            }
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
        long recoveryPublicationCeiling = metadata.ensureInitialized(
                transactionManager, concurrentCommitPublication);
        if (recoveryPublicationCeiling >= 0L) {
            observeRecoveryPublicationCeiling(recoveryPublicationCeiling);
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
        if (experimentalSnapshotLeaseRegistry) {
            SnapshotLease lease = tryClaimCurrentSnapshotLeaseSlot();
            if (lease != null) {
                return lease;
            }
        }
        return openLockedSnapshotLease();
    }

    private SnapshotLease openLockedSnapshotLease() {
        commitPublicationLock.lock();
        MvccSnapshotLeaseDiagnostics.lockedCurrentOpen();
        try {
            long sequence = publishedHighWater.get();
            return registerLockedSnapshotLease(sequence);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    SnapshotLease retainSnapshot(long sequence) {
        if (sequence < 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC retained snapshot must be committed: " + sequence);
        }
        if (experimentalSnapshotLeaseRegistry) {
            SnapshotLease lease = tryClaimRetainedSnapshotLeaseSlot(sequence);
            if (lease != null) {
                return lease;
            }
        }
        return retainLockedSnapshot(sequence);
    }

    private SnapshotLease retainLockedSnapshot(long sequence) {
        commitPublicationLock.lock();
        MvccSnapshotLeaseDiagnostics.lockedRetainedOpen();
        try {
            long published = publishedHighWater.get();
            if (sequence > published) {
                throw new IllegalArgumentException(
                        "RawStore MVCC retained snapshot is ahead of publication: "
                                + sequence + " > " + published);
            }
            return registerLockedSnapshotLease(sequence);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    long vacuumHorizon() {
        long published = publishedHighWater.get();
        SnapshotLeaseSlotSummary slotSummary = snapshotLeaseSlotSummary(published);
        commitPublicationLock.lock();
        try {
            long horizon = slotSummary.horizon();
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

    private SnapshotLease registerLockedSnapshotLease(long sequence) {
        long leaseId = nextSnapshotLeaseId.getAndIncrement();
        retainedSnapshotSequences.put(leaseId, sequence);
        return SnapshotLease.locked(this, leaseId, sequence);
    }

    private SnapshotLease tryClaimCurrentSnapshotLeaseSlot() {
        AtomicLongArray slots = snapshotLeaseSlots;
        int slot = claimSnapshotLeaseSlot(slots);
        if (slot < 0) {
            MvccSnapshotLeaseDiagnostics.currentSlotClaimFailure();
            return null;
        }
        boolean opened = false;
        try {
            long sequence = publishedHighWater.get();
            SnapshotLease lease = SnapshotLease.slotted(this, slot, sequence);
            slots.set(slot, sequence);
            MvccSnapshotLeaseDiagnostics.slottedCurrentOpen();
            opened = true;
            return lease;
        } finally {
            if (!opened) {
                slots.set(slot, FREE_SNAPSHOT_LEASE_SLOT);
            }
        }
    }

    private SnapshotLease tryClaimRetainedSnapshotLeaseSlot(long sequence) {
        AtomicLongArray slots = snapshotLeaseSlots;
        int slot = claimSnapshotLeaseSlot(slots);
        if (slot < 0) {
            MvccSnapshotLeaseDiagnostics.retainedSlotClaimFailure();
            return null;
        }
        boolean opened = false;
        try {
            long published = publishedHighWater.get();
            if (sequence > published) {
                throw new IllegalArgumentException(
                        "RawStore MVCC retained snapshot is ahead of publication: "
                                + sequence + " > " + published);
            }
            SnapshotLease lease = SnapshotLease.slotted(this, slot, sequence);
            slots.set(slot, sequence);
            MvccSnapshotLeaseDiagnostics.slottedRetainedOpen();
            opened = true;
            return lease;
        } finally {
            if (!opened) {
                slots.set(slot, FREE_SNAPSHOT_LEASE_SLOT);
            }
        }
    }

    private int claimSnapshotLeaseSlot(AtomicLongArray slots) {
        int length = slots.length();
        int start = Math.floorMod((int) snapshotLeaseClaimCursor.getAndIncrement(), length);
        for (int offset = 0; offset < length; offset++) {
            int slot = (start + offset) % length;
            if (slots.compareAndSet(
                    slot, FREE_SNAPSHOT_LEASE_SLOT, CLAIMING_SNAPSHOT_LEASE_SLOT)) {
                return slot;
            }
        }
        return -1;
    }

    private SnapshotLeaseSlotSummary snapshotLeaseSlotSummary(long published) {
        AtomicLongArray slots = snapshotLeaseSlots;
        if (slots == null) {
            return new SnapshotLeaseSlotSummary(published, 0);
        }
        scan:
        while (true) {
            long horizon = published;
            int count = 0;
            for (int index = 0; index < slots.length(); index++) {
                long sequence = slots.get(index);
                if (sequence == CLAIMING_SNAPSHOT_LEASE_SLOT) {
                    Thread.onSpinWait();
                    continue scan;
                }
                if (sequence >= 0L) {
                    count++;
                    horizon = Math.min(horizon, sequence);
                }
            }
            return new SnapshotLeaseSlotSummary(horizon, count);
        }
    }

    private void closeSnapshotLease(long leaseId, int slot, long sequence) {
        if (slot >= 0) {
            if (!snapshotLeaseSlots.compareAndSet(slot, sequence, FREE_SNAPSHOT_LEASE_SLOT)) {
                throw new IllegalStateException(
                        "RawStore MVCC snapshot lease slot changed before close: " + slot);
            }
            MvccSnapshotLeaseDiagnostics.slottedClose();
            return;
        }
        commitPublicationLock.lock();
        MvccSnapshotLeaseDiagnostics.lockedClose();
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
            if (nextCommitSequence == commitSequenceReservationLimit) {
                nextCommitSequence = metadata.reserveCommitSequences(
                        rawTransaction,
                        commitSequenceReservationBlockSize,
                        concurrentCommitPublication);
                commitSequenceReservationLimit = Math.addExact(
                        nextCommitSequence, commitSequenceReservationBlockSize);
            }
            long sequence = nextCommitSequence++;
            if (concurrentCommitPublication) {
                commitPublicationLock.unlock();
            }
            return sequence;
        } catch (StandardException | RuntimeException | Error failure) {
            if (commitPublicationLock.isHeldByCurrentThread()) {
                commitPublicationLock.unlock();
            }
            throw failure;
        }
    }

    boolean concurrentCommitPublication() {
        return concurrentCommitPublication;
    }

    void stageCommittedHighWater(Transaction rawTransaction, long sequence)
            throws StandardException {
        metadata.stageCommittedHighWater(rawTransaction, sequence);
    }

    void publishAndUnlock(long sequence) {
        publishedHighWater.accumulateAndGet(sequence, Math::max);
        commitPublicationLock.unlock();
    }

    void publishConcurrentCommit(long transactionId, long sequence) {
        commitPublicationLock.lock();
        try {
            retireTransaction(transactionId);
            finishConcurrentCommitSequence(sequence);
            while (publishedHighWater.get() < sequence) {
                commitPublicationAdvanced.awaitUninterruptibly();
            }
        } finally {
            commitPublicationLock.unlock();
        }
    }

    void abandonConcurrentCommitSequence(long sequence) {
        if (!concurrentCommitPublication || sequence <= 0L) {
            return;
        }
        commitPublicationLock.lock();
        try {
            finishConcurrentCommitSequence(sequence);
        } finally {
            commitPublicationLock.unlock();
        }
    }

    private void finishConcurrentCommitSequence(long sequence) {
        if (sequence < nextPublicationSequence || !terminalCommitSequences.add(sequence)) {
            return;
        }
        long previousHighWater = publishedHighWater.get();
        while (terminalCommitSequences.remove(nextPublicationSequence)) {
            nextPublicationSequence++;
        }
        long highWater = nextPublicationSequence - 1L;
        publishedHighWater.set(highWater);
        if (highWater > previousHighWater) {
            commitPublicationAdvanced.signalAll();
        }
    }

    void unlockWithoutPublication() {
        if (commitPublicationLock.isHeldByCurrentThread()) {
            commitPublicationLock.unlock();
        }
    }

    void observeRecoveryPublicationCeiling(long sequence) {
        if (!concurrentCommitPublication) {
            publishedHighWater.accumulateAndGet(sequence, Math::max);
            return;
        }
        commitPublicationLock.lock();
        try {
            if (sequence > publishedHighWater.get()) {
                publishedHighWater.set(sequence);
                nextPublicationSequence = sequence + 1L;
                terminalCommitSequences.headSet(nextPublicationSequence).clear();
            }
        } finally {
            commitPublicationLock.unlock();
        }
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
        long published = publishedHighWater.get();
        SnapshotLeaseSlotSummary slotSummary = snapshotLeaseSlotSummary(published);
        long horizon;
        int retainedCount;
        commitPublicationLock.lock();
        try {
            horizon = slotSummary.horizon();
            retainedCount = slotSummary.count() + retainedSnapshotSequences.size();
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
        if (currentRowAnchors != null) {
            for (int index = 0; index < currentRowAnchors.length(); index++) {
                currentRowAnchors.set(index, null);
            }
        }
        tableMaintenanceBoundaries.clear();
        activeTransactionIds.clear();
        commitPublicationLock.lock();
        try {
            retainedSnapshotSequences.clear();
        } finally {
            commitPublicationLock.unlock();
        }
        AtomicLongArray slots = snapshotLeaseSlots;
        if (slots != null) {
            for (int index = 0; index < slots.length(); index++) {
                slots.set(index, FREE_SNAPSHOT_LEASE_SLOT);
            }
        }
    }

    private record SnapshotLeaseSlotSummary(long horizon, int count) {
    }

    private record CurrentVersionReadImage(
            CurrentRowAnchor anchor,
            MvccRawStoreTable.VersionRecord version) {
    }

    record CurrentRowAnchor(
            ContainerKey tableKey,
            long rowId,
            long versionId,
            long beginSequence,
            int flags,
            MvccRawStoreTable.RecordHint hint,
            MvccRowLocation directoryLocation) {
        CurrentRowAnchor {
            Objects.requireNonNull(tableKey, "tableKey");
            Objects.requireNonNull(hint, "hint");
            Objects.requireNonNull(directoryLocation, "directoryLocation");
            directoryLocation = (MvccRowLocation) directoryLocation.cloneValue(false);
        }

        boolean tombstone() {
            return (flags & MvccRawStoreFormat.TOMBSTONE_FLAGS) != 0;
        }

        boolean visibleTo(long snapshotSequence) {
            return beginSequence <= snapshotSequence;
        }
    }

    static final class SnapshotLease implements AutoCloseable {
        private final MvccRawStoreRuntime runtime;
        private final long leaseId;
        private final int slot;
        private final long sequence;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SnapshotLease(
                MvccRawStoreRuntime runtime,
                long leaseId,
                int slot,
                long sequence) {
            this.runtime = runtime;
            this.leaseId = leaseId;
            this.slot = slot;
            this.sequence = sequence;
        }

        private static SnapshotLease locked(
                MvccRawStoreRuntime runtime,
                long leaseId,
                long sequence) {
            return new SnapshotLease(runtime, leaseId, -1, sequence);
        }

        private static SnapshotLease slotted(
                MvccRawStoreRuntime runtime,
                int slot,
                long sequence) {
            return new SnapshotLease(runtime, 0L, slot, sequence);
        }

        long sequence() {
            return sequence;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                runtime.closeSnapshotLease(leaseId, slot, sequence);
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
