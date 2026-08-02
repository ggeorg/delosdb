/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTransactionContext

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.derby.iapi.store.access.DatabaseInstant;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Transaction-local MVCC semantics attached to one inherited access transaction. */
final class MvccRawStoreTransactionContext implements AccessMethodTransactionLifecycle {
    private static final long UNCAPTURED_SNAPSHOT = Long.MIN_VALUE;

    private final MvccRawStoreRuntime runtime;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private final List<MvccRawStoreTable.PendingVersion> pending = new ArrayList<>();
    private final List<SavepointMarker> savepoints = new ArrayList<>();
    private final Set<MvccRawStoreLogicalLock> sharedLocks = new HashSet<>();
    private final Set<MvccRawStoreLogicalLock> exclusiveLocks = new HashSet<>();
    private final Map<Long, AllocatorReservation> allocatorReservations = new LinkedHashMap<>();
    private final Map<Long, OrderedIndexGeneration> orderedIndexGenerations = new LinkedHashMap<>();
    private final Map<ContainerKey, MvccRawStoreTable.Descriptor> createdTables =
            new LinkedHashMap<>();
    private final Map<ContainerKey, MvccRawStoreTable.Descriptor> droppedTables =
            new LinkedHashMap<>();
    private final Map<ContainerKey, MvccRawStoreRuntime.TableMaintenanceBoundary> vacuumBoundaries =
            new LinkedHashMap<>();

    private long transactionId;
    private long snapshotSequence = UNCAPTURED_SNAPSHOT;
    private MvccRawStoreRuntime.SnapshotLease snapshotLease;
    private long reservedCommitSequence;
    private boolean publicationLockHeld;
    private boolean vacuumMutation;

    MvccRawStoreTransactionContext(
            MvccRawStoreRuntime runtime,
            TransactionManager transactionManager,
            Transaction rawTransaction) {
        this.runtime = runtime;
        this.transactionManager = transactionManager;
        this.rawTransaction = rawTransaction;
    }

    long transactionId() {
        return transactionId;
    }

    void beforeWrite() throws StandardException {
        if (transactionManager.isGlobal()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore-backed delos_mvcc XA participation");
        }
        ensureTransactionId();
    }

    void beforeTableWrite(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeWrite();
        acquireShared(MvccRawStoreLogicalLock.table(table));
    }

    void beforeRowWrite(MvccRawStoreTable.Descriptor table, long rowId)
            throws StandardException {
        beforeTableWrite(table);
        acquireExclusive(MvccRawStoreLogicalLock.row(table, rowId));
    }

    void beforeSchemaChange(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeWrite();
        acquireExclusive(MvccRawStoreLogicalLock.table(table));
    }

    void markCreatedTable(MvccRawStoreTable.Descriptor table) {
        createdTables.put(table.metadataContainer(), table);
    }

    void beforeDrop(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeSchemaChange(table);
        droppedTables.put(table.metadataContainer(), table);

        OrderedIndexGeneration generation = orderedIndexGenerations.get(
                table.metadataContainer().getContainerId());
        if (generation != null) {
            rawTransaction.dropContainer(generation.privateContainer());
        }
    }

    void beforeVacuum(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeSchemaChange(table);
        ContainerKey tableKey = table.metadataContainer();
        vacuumBoundaries.computeIfAbsent(tableKey, ignored -> runtime.enterVacuum(table));
    }

    void lockUniqueKeys(
            MvccRawStoreTable.Descriptor table,
            List<MvccRawStoreTable.UniqueConstraint> constraints,
            StoreDataValue[]... rows) throws StandardException {
        TreeSet<MvccRawStoreLogicalLock> ordered = new TreeSet<>();
        try {
            for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
                for (StoreDataValue[] row : rows) {
                    if (row == null
                            || (constraint.duplicateNullsAllowed()
                                && containsNull(row, constraint.columns()))) {
                        continue;
                    }
                    ordered.add(MvccRawStoreLogicalLock.uniqueKey(table, constraint, row));
                }
            }
        } catch (RuntimeException comparisonFailure) {
            if (comparisonFailure.getCause() instanceof StandardException standard) {
                throw standard;
            }
            throw comparisonFailure;
        }
        for (MvccRawStoreLogicalLock lock : ordered) {
            acquireExclusive(lock);
        }
    }

    MvccRawStoreTable.Allocation reserveInsertIdentifiers(
            MvccRawStoreTable.Descriptor table) throws StandardException {
        MvccRawStoreTable.Allocation allocation =
                runtime.reserveInsertIdentifiers(rawTransaction, table);
        observeAllocatorReservation(
                table,
                allocation.rowId() + 1L,
                allocation.versionId() + 1L);
        return allocation;
    }

    long reserveVersionIdentifier(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        long versionId = runtime.reserveVersionIdentifier(rawTransaction, table);
        observeAllocatorReservation(table, 0L, versionId + 1L);
        return versionId;
    }

    private void observeAllocatorReservation(
            MvccRawStoreTable.Descriptor table,
            long nextRowId,
            long nextVersionId) throws StandardException {
        long tableId = table.metadataContainer().getContainerId();
        AllocatorReservation current = allocatorReservations.get(tableId);
        if (current == null) {
            MvccRawStoreTable.AllocatorHighWater persisted =
                    MvccRawStoreTable.readAllocatorHighWater(rawTransaction, table);
            current = new AllocatorReservation(
                    table,
                    persisted.nextRowId(),
                    persisted.nextVersionId());
        }
        allocatorReservations.put(
                tableId,
                new AllocatorReservation(
                        table,
                        Math.max(current.nextRowId(), nextRowId),
                        Math.max(current.nextVersionId(), nextVersionId)));
    }

    ContainerKey orderedIndexForRead(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        OrderedIndexGeneration generation = orderedIndexGenerations.get(
                table.metadataContainer().getContainerId());
        if (generation != null) {
            return generation.privateContainer();
        }
        return MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                rawTransaction,
                table,
                false);
    }

    ContainerKey orderedIndexForWrite(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        long tableId = table.metadataContainer().getContainerId();
        OrderedIndexGeneration generation = orderedIndexGenerations.get(tableId);
        if (generation != null) {
            return generation.privateContainer();
        }
        ContainerKey published = MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                rawTransaction,
                table,
                false);
        ContainerKey privateContainer = MvccRawStoreOrderedIndex.createPrivateGeneration(
                rawTransaction,
                table);
        MvccRawStoreTable.rebuildOrderedIndexForTransaction(
                rawTransaction,
                table,
                privateContainer,
                this);
        orderedIndexGenerations.put(
                tableId,
                new OrderedIndexGeneration(table, published, privateContainer));
        return privateContainer;
    }

    void addPending(MvccRawStoreTable.PendingVersion version) {
        pending.add(version);
    }

    long snapshotSequence() {
        if (snapshotSequence == UNCAPTURED_SNAPSHOT) {
            snapshotLease = runtime.openSnapshotLease();
            snapshotSequence = snapshotLease.sequence();
        }
        return snapshotSequence;
    }

    MvccRawStoreRuntime.SnapshotLease retainSnapshotLease() {
        return runtime.retainSnapshot(snapshotSequence());
    }

    long currentCommittedSequence() {
        return runtime.captureSnapshot();
    }

    long vacuumHorizon() {
        return runtime.vacuumHorizon();
    }

    void markVacuumMutation() {
        vacuumMutation = true;
    }

    boolean isTransactionActive(long candidateTransactionId) {
        return runtime.isTransactionActive(candidateTransactionId);
    }

    @Override
    public void beforeCommit(CommitMode mode) throws StandardException {
        List<MvccRawStoreTable.PendingVersion> committableVersions =
                committablePendingVersions();
        List<OrderedIndexGeneration> committableIndexes =
                committableOrderedIndexes();
        boolean hasPendingVersions = !committableVersions.isEmpty();
        boolean hasPrivateIndexes = !committableIndexes.isEmpty();
        if (!hasPendingVersions && !hasPrivateIndexes && !vacuumMutation) {
            return;
        }

        if (!hasPendingVersions) {
            publishOrderedIndexes(committableIndexes);
            if (vacuumMutation) {
                MvccRawStoreRuntime.haltAtFailurePoint(
                        MvccRawStoreRuntime.AFTER_VACUUM_BEFORE_RAW_COMMIT,
                        93);
            }
            return;
        }

        reservedCommitSequence = runtime.reserveCommitSequence(rawTransaction);
        publicationLockHeld = true;
        try {
            stageAllocatorHighWaters();
            MvccRawStoreTable.stampPendingVersions(
                    rawTransaction,
                    committableVersions,
                    reservedCommitSequence);
            publishOrderedIndexes(committableIndexes);
            runtime.stageCommittedHighWater(rawTransaction, reservedCommitSequence);
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_STAMP_BEFORE_RAW_COMMIT,
                    91);
            if (vacuumMutation) {
                MvccRawStoreRuntime.haltAtFailurePoint(
                        MvccRawStoreRuntime.AFTER_VACUUM_BEFORE_RAW_COMMIT,
                        93);
            }
        } catch (StandardException | RuntimeException | Error failure) {
            runtime.unlockWithoutPublication();
            publicationLockHeld = false;
            reservedCommitSequence = 0L;
            throw failure;
        }
    }

    @Override
    public void afterCommit(CommitMode mode, DatabaseInstant instant) {
        List<MvccRawStoreTable.PendingVersion> committedVersions =
                committablePendingVersions();
        List<MvccRawStoreTable.Descriptor> committedCreates =
                committableCreatedTables();
        List<MvccRawStoreTable.Descriptor> committedDrops =
                List.copyOf(droppedTables.values());
        if (vacuumMutation) {
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_VACUUM_RAW_COMMIT_BEFORE_PUBLICATION,
                    94);
        }
        if (publicationLockHeld) {
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_RAW_COMMIT_BEFORE_PUBLICATION,
                    92);
        }
        for (OrderedIndexGeneration generation : committableOrderedIndexes()) {
            generation.table().observeOrderedIndexContainer(generation.privateContainer());
        }
        if (publicationLockHeld) {
            // A snapshot at the newly published high-water must never reject
            // this transaction's committed versions as though the writer were
            // still active. Retire the identity before releasing publication.
            runtime.retireTransaction(transactionId);
            runtime.publishAndUnlock(reservedCommitSequence);
        }
        clearLocalState();
        committedCreates.forEach(runtime::registerTable);
        runtime.afterUserCommit(committedVersions);
        committedDrops.forEach(runtime::unregisterTable);
    }

    @Override
    public void commitFailed(CommitMode mode, Throwable failure) {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
        reservedCommitSequence = 0L;
    }

    @Override
    public void beforeAbort() {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
    }

    @Override
    public void afterAbort() {
        clearLocalState();
    }

    @Override
    public void abortFailed(Throwable failure) {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
    }

    @Override
    public void afterSetSavepoint(SavepointIdentity savepoint) {
        savepoints.add(new SavepointMarker(savepoint, pending.size()));
    }

    @Override
    public void afterRollbackToSavepoint(SavepointIdentity savepoint) throws StandardException {
        // The lifecycle participant may be registered after the target savepoint
        // was created. RawStore has already completed physical rollback, so the
        // surviving version rows are the authoritative pending set.
        for (int index = pending.size() - 1; index >= 0; index--) {
            if (!MvccRawStoreTable.pendingVersionExists(
                    rawTransaction,
                    pending.get(index),
                    transactionId)) {
                pending.remove(index);
            }
        }

        orderedIndexGenerations.entrySet().removeIf(entry -> {
            try {
                return !MvccRawStoreOrderedIndex.containerExists(
                        rawTransaction,
                        entry.getValue().privateContainer());
            } catch (StandardException failure) {
                throw new OrderedIndexReconciliationFailure(failure);
            }
        });

        createdTables.entrySet().removeIf(entry -> {
            try {
                return !MvccRawStoreOrderedIndex.containerExists(
                        rawTransaction, entry.getKey());
            } catch (StandardException failure) {
                throw new OrderedIndexReconciliationFailure(failure);
            }
        });
        droppedTables.entrySet().removeIf(entry -> {
            try {
                return MvccRawStoreOrderedIndex.containerExists(
                        rawTransaction, entry.getKey());
            } catch (StandardException failure) {
                throw new OrderedIndexReconciliationFailure(failure);
            }
        });

        int markerIndex = findSavepoint(savepoint);
        if (markerIndex < 0) {
            savepoints.clear();
            return;
        }
        while (savepoints.size() > markerIndex + 1) {
            savepoints.remove(savepoints.size() - 1);
        }
    }

    @Override
    public void afterReleaseSavepoint(SavepointIdentity savepoint) {
        int markerIndex = findSavepoint(savepoint);
        if (markerIndex < 0) {
            savepoints.clear();
            return;
        }
        while (savepoints.size() > markerIndex) {
            savepoints.remove(savepoints.size() - 1);
        }
    }

    @Override
    public void beforeNestedUserTransaction(boolean readOnly) throws StandardException {
        if (!readOnly) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore-backed delos_mvcc nested update transactions");
        }
    }

    @Override
    public void beforeXaOperation(XaOperation operation) throws StandardException {
        throw StandardException.newException(
                SQLState.NOT_IMPLEMENTED,
                "RawStore-backed delos_mvcc XA operation " + operation);
    }

    @Override
    public void beforeDestroy() {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
    }

    @Override
    public void afterDestroy() {
        clearLocalState();
    }

    private void publishOrderedIndexes(List<OrderedIndexGeneration> generations)
            throws StandardException {
        List<OrderedIndexGeneration> ordered = new ArrayList<>(generations);
        ordered.sort(java.util.Comparator.comparingLong(
                generation -> generation.table().metadataContainer().getContainerId()));
        for (OrderedIndexGeneration generation : ordered) {
            MvccRawStoreTable.rebuildOrderedIndexForPublication(
                    rawTransaction,
                    generation.table(),
                    generation.privateContainer(),
                    transactionId,
                    this);
            ContainerKey replaced = MvccRawStoreTableMetadata.publishOrderedIndexContainer(
                    rawTransaction,
                    generation.table(),
                    generation.privateContainer());
            if (replaced != null && !replaced.equals(generation.privateContainer())) {
                rawTransaction.dropContainer(replaced);
            }
        }
    }

    private void stageAllocatorHighWaters() throws StandardException {
        List<AllocatorReservation> ordered = new ArrayList<>(allocatorReservations.values());
        ordered.sort(java.util.Comparator.comparingLong(
                reservation -> reservation.table().metadataContainer().getContainerId()));
        for (AllocatorReservation reservation : ordered) {
            if (isDropped(reservation.table())) {
                continue;
            }
            MvccRawStoreTable.stageAllocatorHighWater(
                    rawTransaction,
                    reservation.table(),
                    reservation.nextRowId(),
                    reservation.nextVersionId());
        }
    }

    private List<MvccRawStoreTable.Descriptor> committableCreatedTables() {
        List<MvccRawStoreTable.Descriptor> committable =
                new ArrayList<>(createdTables.size());
        for (MvccRawStoreTable.Descriptor table : createdTables.values()) {
            if (!isDropped(table)) {
                committable.add(table);
            }
        }
        return List.copyOf(committable);
    }

    private List<MvccRawStoreTable.PendingVersion> committablePendingVersions() {
        List<MvccRawStoreTable.PendingVersion> committable = new ArrayList<>(pending.size());
        for (MvccRawStoreTable.PendingVersion version : pending) {
            if (!isDropped(version.table())) {
                committable.add(version);
            }
        }
        return List.copyOf(committable);
    }

    private List<OrderedIndexGeneration> committableOrderedIndexes() {
        List<OrderedIndexGeneration> committable = new ArrayList<>(orderedIndexGenerations.size());
        for (OrderedIndexGeneration generation : orderedIndexGenerations.values()) {
            if (!isDropped(generation.table())) {
                committable.add(generation);
            }
        }
        return List.copyOf(committable);
    }

    private boolean isDropped(MvccRawStoreTable.Descriptor table) {
        return droppedTables.containsKey(table.metadataContainer());
    }

    private void acquireShared(MvccRawStoreLogicalLock lock) throws StandardException {
        if (exclusiveLocks.contains(lock) || !sharedLocks.add(lock)) {
            return;
        }
        try {
            runtime.lockShared(rawTransaction, lock);
        } catch (StandardException | RuntimeException | Error failure) {
            sharedLocks.remove(lock);
            throw failure;
        }
    }

    private void acquireExclusive(MvccRawStoreLogicalLock lock) throws StandardException {
        if (!exclusiveLocks.add(lock)) {
            return;
        }
        try {
            runtime.lockExclusive(rawTransaction, lock);
        } catch (StandardException | RuntimeException | Error failure) {
            exclusiveLocks.remove(lock);
            throw failure;
        }
    }

    private static boolean containsNull(StoreDataValue[] row, int[] columns)
            throws StandardException {
        for (int column : columns) {
            if (StoreTypeUtil.isNull(row[column])) {
                return true;
            }
        }
        return false;
    }

    private int findSavepoint(SavepointIdentity identity) {
        for (int index = savepoints.size() - 1; index >= 0; index--) {
            if (savepoints.get(index).identity().equals(identity)) {
                return index;
            }
        }
        return -1;
    }

    private void ensureTransactionId() throws StandardException {
        if (transactionId == 0L) {
            transactionId = runtime.reserveTransactionId(rawTransaction);
        }
    }

    private void clearLocalState() {
        runtime.retireTransaction(transactionId);
        if (snapshotLease != null) {
            snapshotLease.close();
            snapshotLease = null;
        }
        for (MvccRawStoreRuntime.TableMaintenanceBoundary boundary : vacuumBoundaries.values()) {
            boundary.close();
        }
        vacuumBoundaries.clear();
        pending.clear();
        savepoints.clear();
        sharedLocks.clear();
        exclusiveLocks.clear();
        allocatorReservations.clear();
        orderedIndexGenerations.clear();
        createdTables.clear();
        droppedTables.clear();
        transactionId = 0L;
        snapshotSequence = UNCAPTURED_SNAPSHOT;
        reservedCommitSequence = 0L;
        publicationLockHeld = false;
        vacuumMutation = false;
    }

    private record SavepointMarker(SavepointIdentity identity, int pendingSize) {
    }

    private record AllocatorReservation(
            MvccRawStoreTable.Descriptor table,
            long nextRowId,
            long nextVersionId) {
    }

    private record OrderedIndexGeneration(
            MvccRawStoreTable.Descriptor table,
            ContainerKey publishedContainer,
            ContainerKey privateContainer) {
    }

    private static final class OrderedIndexReconciliationFailure extends RuntimeException {
        OrderedIndexReconciliationFailure(StandardException cause) {
            super(cause);
        }
    }
}
