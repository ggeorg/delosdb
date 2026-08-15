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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.derby.iapi.store.access.DatabaseInstant;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreTypeUtil;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Transaction-local MVCC semantics attached to one inherited access transaction. */
final class MvccRawStoreTransactionContext implements AccessMethodTransactionLifecycle {
    private static final long UNCAPTURED_SNAPSHOT = Long.MIN_VALUE;

    private final MvccRawStoreRuntime runtime;
    private final TransactionManager transactionManager;
    private final Transaction rawTransaction;
    private final List<MvccRawStoreTable.PendingVersion> pending = new ArrayList<>();
    private final List<DeletedKeyProof> deletedKeyProofs = new ArrayList<>();
    private final List<SavepointMarker> savepoints = new ArrayList<>();
    private final Set<MvccRawStoreLogicalLock> sharedLocks = new HashSet<>();
    private final Set<MvccRawStoreLogicalLock> exclusiveLocks = new HashSet<>();
    private final Map<Long, AllocatorReservation> allocatorReservations = new LinkedHashMap<>();
    private final Map<Long, OrderedIndexReplacement> orderedIndexReplacements = new LinkedHashMap<>();
    private final Set<MvccRawStoreTable.Descriptor> orderedIndexGenerationInvalidations =
            new HashSet<>();
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

    TransactionManager transactionManager() {
        return transactionManager;
    }

    boolean currentRowAnchorEnabled() {
        return runtime.currentRowAnchorEnabled();
    }

    boolean currentVersionReadImageEnabled() {
        return runtime.currentVersionReadImageEnabled();
    }

    MvccRawStoreTable.VersionRecord currentVersionReadImage(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreRuntime.CurrentRowAnchor anchor) {
        return runtime.currentVersionReadImage(table, anchor);
    }

    void publishCurrentVersionReadImage(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreRuntime.CurrentRowAnchor anchor,
            MvccRawStoreTable.VersionRecord version) {
        runtime.publishCurrentVersionReadImage(table, anchor, version);
    }

    MvccRawStoreRuntime.CurrentRowAnchor currentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            long rowId) {
        return runtime.currentRowAnchor(table, rowId);
    }

    void observeCurrentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            MvccRawStoreTable.DirectoryRecord directory) {
        runtime.observeCurrentRowAnchor(table, directory);
    }

    void invalidateCurrentRowAnchor(
            MvccRawStoreTable.Descriptor table,
            long rowId,
            MvccRawStoreRuntime.CurrentRowAnchor expected) {
        runtime.invalidateCurrentRowAnchor(table, rowId, expected);
    }

    boolean hasPendingVersion(MvccRawStoreTable.Descriptor table, long rowId) {
        for (MvccRawStoreTable.PendingVersion version : pending) {
            if (version.rowId() == rowId
                    && version.table().metadataContainer().equals(table.metadataContainer())) {
                return true;
            }
        }
        return false;
    }

    void beforeWrite() throws StandardException {
        ensureWriteSupported();
        ensureTransactionId();
    }

    void beforeTableWrite(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeWrite();
        acquireShared(MvccRawStoreLogicalLock.table(table));
    }

    void beforeRowWrite(MvccRawStoreTable.Descriptor table, long rowId)
            throws StandardException {
        beforeWrite();
        acquireRowUpdateLocks(table, rowId);
    }

    void lockRowForUpdate(MvccRawStoreTable.Descriptor table, long rowId)
            throws StandardException {
        ensureWriteSupported();
        acquireRowUpdateLocks(table, rowId);
    }

    private void acquireRowUpdateLocks(MvccRawStoreTable.Descriptor table, long rowId)
            throws StandardException {
        acquireShared(MvccRawStoreLogicalLock.table(table));
        acquireExclusive(MvccRawStoreLogicalLock.row(table, rowId));
    }

    void lockRowForReadCommittedUpdate(
            MvccRawStoreTable.Descriptor table, MvccRowLocation location)
            throws StandardException {
        lockRowForUpdate(table, location.rowId());
    }

    void beforeSchemaChange(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeWrite();
        acquireExclusive(MvccRawStoreLogicalLock.table(table));
        orderedIndexGenerationInvalidations.add(table);
        table.invalidateOrderedIndexGeneration();
    }

    void markCreatedTable(MvccRawStoreTable.Descriptor table) {
        createdTables.put(table.metadataContainer(), table);
    }

    void beforeDrop(MvccRawStoreTable.Descriptor table) throws StandardException {
        beforeSchemaChange(table);
        droppedTables.put(table.metadataContainer(), table);

        OrderedIndexReplacement replacement = orderedIndexReplacements.get(
                table.metadataContainer().getContainerId());
        if (replacement != null) {
            MvccRawStoreOrderedIndexGeneration.dropGeneration(
                    transactionManager, table, replacement.privateContainer());
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
        OrderedIndexReplacement replacement = orderedIndexReplacements.get(
                table.metadataContainer().getContainerId());
        if (replacement != null) {
            return replacement.privateContainer();
        }
        ContainerKey published = table.orderedIndexContainer();
        return published != null
                ? published
                : MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                        rawTransaction,
                        table,
                        false);
    }

    long orderedIndexBtreeForRead(
            MvccRawStoreTable.Descriptor table,
            ContainerKey directoryKey,
            int column) throws StandardException {
        if (directoryKey == null) {
            return 0L;
        }
        OrderedIndexReplacement replacement = orderedIndexReplacements.get(
                table.metadataContainer().getContainerId());
        if (replacement == null) {
            MvccRawStoreTable.Descriptor.OrderedIndexGeneration observed =
                    table.orderedIndexGeneration(directoryKey);
            if (observed != null) {
                return observed.btree(column);
            }
        }

        long[] btrees = MvccRawStoreOrderedIndexGeneration.readBtreeConglomerates(
                rawTransaction,
                table,
                directoryKey);
        if (btrees == null) {
            return 0L;
        }
        if (replacement == null
                && !orderedIndexGenerationInvalidations.contains(table)
                && directoryKey.equals(table.orderedIndexContainer())) {
            table.observeOrderedIndexGeneration(directoryKey, btrees);
        }
        return btrees[column];
    }

    ContainerKey orderedIndexForWrite(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        OrderedIndexReplacement replacement = orderedIndexReplacements.get(
                table.metadataContainer().getContainerId());
        if (replacement != null) {
            return replacement.privateContainer();
        }
        ContainerKey published = MvccRawStoreTableMetadata.discoverOrderedIndexContainer(
                rawTransaction,
                table,
                false);
        if (published == null
                || !MvccRawStoreOrderedIndexGeneration.hasBtreeGeneration(
                        transactionManager, table, published)) {
            prepareOrderedIndexReplacement(table);
            return orderedIndexReplacements.get(
                    table.metadataContainer().getContainerId()).privateContainer();
        }
        return published;
    }

    void prepareOrderedIndexReplacement(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        long tableId = table.metadataContainer().getContainerId();
        if (orderedIndexReplacements.containsKey(tableId)) {
            return;
        }
        ContainerKey privateContainer = MvccRawStoreOrderedIndexGeneration.createPrivateGeneration(
                transactionManager,
                table);
        MvccRawStoreTable.rebuildOrderedIndexForTransaction(
                rawTransaction,
                table,
                privateContainer,
                this);
        orderedIndexReplacements.put(
                tableId,
                new OrderedIndexReplacement(table, privateContainer));
    }

    void refreshOrderedIndexReplacement(MvccRawStoreTable.Descriptor table)
            throws StandardException {
        OrderedIndexReplacement replacement = orderedIndexReplacements.get(
                table.metadataContainer().getContainerId());
        if (replacement == null) {
            prepareOrderedIndexReplacement(table);
            return;
        }
        MvccRawStoreTable.rebuildOrderedIndexForTransaction(
                rawTransaction, table, replacement.privateContainer(), this);
    }

    boolean hasOrderedIndexReplacement(MvccRawStoreTable.Descriptor table) {
        return orderedIndexReplacements.containsKey(
                table.metadataContainer().getContainerId());
    }

    void addPending(MvccRawStoreTable.PendingVersion version) {
        pending.add(version);
    }

    void rememberDeletedKeyProof(
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] deletedValues,
            MvccRawStoreTable.PendingVersion tombstone) throws StandardException {
        List<MvccRawStoreTable.UniqueConstraint> constraints = table.uniqueConstraints();
        if (constraints.isEmpty()) {
            return;
        }
        deletedKeyProofs.add(new DeletedKeyProof(
                table,
                deletedValues,
                tombstone,
                constraints));
    }

    DeletedKeyProof deletedKeyProof(
            MvccRawStoreTable.Descriptor table,
            StoreDataValue[] values) throws StandardException {
        for (int index = deletedKeyProofs.size() - 1; index >= 0; index--) {
            DeletedKeyProof candidate = deletedKeyProofs.get(index);
            if (!candidate.available()
                    || !candidate.belongsTo(table)
                    || !candidate.matchesCurrentConstraints(table.uniqueConstraints())
                    || !candidate.matchesUniqueKeys(values)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    void markDeletedKeyProofConsumed(
            DeletedKeyProof proof,
            MvccRawStoreTable.PendingVersion replacement) {
        proof.consume(replacement);
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
        List<OrderedIndexReplacement> committableIndexes =
                committableOrderedIndexReplacements();
        boolean hasPendingVersions = !committableVersions.isEmpty();
        boolean hasIndexReplacements = !committableIndexes.isEmpty();
        if (!hasPendingVersions && !hasIndexReplacements && !vacuumMutation) {
            return;
        }

        if (!hasPendingVersions) {
            publishOrderedIndexReplacements(committableIndexes);
            if (vacuumMutation) {
                MvccRawStoreRuntime.haltAtFailurePoint(
                        MvccRawStoreRuntime.AFTER_VACUUM_BEFORE_RAW_COMMIT,
                        93);
            }
            return;
        }

        reservedCommitSequence = runtime.reserveCommitSequence(rawTransaction);
        publicationLockHeld = !runtime.concurrentCommitPublication();
        try {
            stageAllocatorHighWaters();
            MvccRawStoreTable.stampPendingVersions(
                    rawTransaction,
                    committableVersions,
                    reservedCommitSequence);
            publishOrderedIndexReplacements(committableIndexes);
            if (publicationLockHeld) {
                runtime.stageCommittedHighWater(rawTransaction, reservedCommitSequence);
            }
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
            if (publicationLockHeld) {
                reservedCommitSequence = 0L;
            }
            publicationLockHeld = false;
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
        if (reservedCommitSequence > 0L) {
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_RAW_COMMIT_BEFORE_PUBLICATION,
                    92);
            // The RawStore commit is durable at this point, but the commit
            // sequence is not yet visible to new snapshots. Publish the
            // transient current-row anchor first so a snapshot which observes
            // the new sequence can never see an older cached current head.
            runtime.publishCommittedAnchors(committedVersions, reservedCommitSequence);
        }
        for (OrderedIndexReplacement replacement : committableOrderedIndexReplacements()) {
            replacement.table().observeOrderedIndexContainer(replacement.privateContainer());
        }
        if (publicationLockHeld) {
            // A snapshot at the newly published high-water must never reject
            // this transaction's committed versions as though the writer were
            // still active. Retire the identity before releasing publication.
            runtime.retireTransaction(transactionId);
            runtime.publishAndUnlock(reservedCommitSequence);
        } else if (reservedCommitSequence > 0L && runtime.concurrentCommitPublication()) {
            runtime.publishConcurrentCommit(transactionId, reservedCommitSequence);
        }
        reservedCommitSequence = 0L;
        clearLocalState();
        committedCreates.forEach(runtime::registerTable);
        runtime.afterUserCommit(committedVersions);
        committedDrops.forEach(runtime::unregisterTable);
    }

    @Override
    public void commitFailed(CommitMode mode, Throwable failure) {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
        if (!runtime.concurrentCommitPublication()) {
            reservedCommitSequence = 0L;
        }
    }

    @Override
    public void beforeAbort() {
        runtime.unlockWithoutPublication();
        publicationLockHeld = false;
    }

    @Override
    public void afterAbort() {
        abandonConcurrentCommitSequence();
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
        reconcileDeletedKeyProofs();

        orderedIndexReplacements.entrySet().removeIf(entry -> {
            try {
                return !MvccRawStoreOrderedIndexGeneration.containerExists(
                        rawTransaction,
                        entry.getValue().privateContainer(),
                        entry.getValue().table().temporary());
            } catch (StandardException failure) {
                throw new OrderedIndexReconciliationFailure(failure);
            }
        });

        createdTables.entrySet().removeIf(entry -> {
            try {
                return !MvccRawStoreOrderedIndexGeneration.containerExists(
                        rawTransaction, entry.getKey(), entry.getValue().temporary());
            } catch (StandardException failure) {
                throw new OrderedIndexReconciliationFailure(failure);
            }
        });
        droppedTables.entrySet().removeIf(entry -> {
            try {
                return MvccRawStoreOrderedIndexGeneration.containerExists(
                        rawTransaction, entry.getKey(), entry.getValue().temporary());
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
        abandonConcurrentCommitSequence();
        clearLocalState();
    }

    private void publishOrderedIndexReplacements(List<OrderedIndexReplacement> replacements)
            throws StandardException {
        List<OrderedIndexReplacement> ordered = new ArrayList<>(replacements);
        ordered.sort(java.util.Comparator.comparingLong(
                replacement -> replacement.table().metadataContainer().getContainerId()));
        for (OrderedIndexReplacement replacement : ordered) {
            MvccRawStoreTable.rebuildOrderedIndexForPublication(
                    rawTransaction,
                    replacement.table(),
                    replacement.privateContainer(),
                    transactionId,
                    this);
            ContainerKey replaced = MvccRawStoreTableMetadata.publishOrderedIndexContainer(
                    rawTransaction,
                    replacement.table(),
                    replacement.privateContainer());
            if (replaced != null && !replaced.equals(replacement.privateContainer())) {
                MvccRawStoreOrderedIndexGeneration.dropGeneration(
                        transactionManager, replacement.table(), replaced);
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

    private List<OrderedIndexReplacement> committableOrderedIndexReplacements() {
        List<OrderedIndexReplacement> committable = new ArrayList<>(orderedIndexReplacements.size());
        for (OrderedIndexReplacement replacement : orderedIndexReplacements.values()) {
            if (!isDropped(replacement.table())) {
                committable.add(replacement);
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

    private void ensureWriteSupported() throws StandardException {
        if (transactionManager.isGlobal()) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore-backed delos_mvcc XA participation");
        }
    }

    private void ensureTransactionId() throws StandardException {
        if (transactionId == 0L) {
            transactionId = runtime.reserveTransactionId(rawTransaction);
        }
    }

    private void abandonConcurrentCommitSequence() {
        if (reservedCommitSequence > 0L && runtime.concurrentCommitPublication()) {
            runtime.abandonConcurrentCommitSequence(reservedCommitSequence);
            reservedCommitSequence = 0L;
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
        deletedKeyProofs.clear();
        savepoints.clear();
        sharedLocks.clear();
        exclusiveLocks.clear();
        allocatorReservations.clear();
        orderedIndexReplacements.clear();
        for (MvccRawStoreTable.Descriptor table : orderedIndexGenerationInvalidations) {
            table.invalidateOrderedIndexGeneration();
        }
        orderedIndexGenerationInvalidations.clear();
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

    private record OrderedIndexReplacement(
            MvccRawStoreTable.Descriptor table,
            ContainerKey privateContainer) {
    }

    static final class DeletedKeyProof {
        private final MvccRawStoreTable.Descriptor table;
        private final MvccRawStoreTable.PendingVersion tombstone;
        private final List<MvccRawStoreTable.UniqueConstraint> constraints;
        private final List<StoreDataValue[]> uniqueKeys;
        private MvccRawStoreTable.PendingVersion replacement;

        private DeletedKeyProof(
                MvccRawStoreTable.Descriptor table,
                StoreDataValue[] deletedValues,
                MvccRawStoreTable.PendingVersion tombstone,
                List<MvccRawStoreTable.UniqueConstraint> constraints) throws StandardException {
            this.table = table;
            this.tombstone = tombstone;
            this.constraints = List.copyOf(constraints);
            List<StoreDataValue[]> keys = new ArrayList<>(constraints.size());
            for (MvccRawStoreTable.UniqueConstraint constraint : constraints) {
                int[] columns = constraint.columns();
                StoreDataValue[] key = new StoreDataValue[columns.length];
                for (int index = 0; index < columns.length; index++) {
                    key[index] = StoreValueCopySupport.cloneValue(
                            deletedValues[columns[index]],
                            true);
                }
                keys.add(key);
            }
            this.uniqueKeys = List.copyOf(keys);
        }

        boolean belongsTo(MvccRawStoreTable.Descriptor candidate) {
            return table.metadataContainer().equals(candidate.metadataContainer())
                    && table.versionContainer().equals(candidate.versionContainer());
        }

        MvccRawStoreTable.PendingVersion tombstone() {
            return tombstone;
        }

        boolean available() {
            return replacement == null;
        }

        void consume(MvccRawStoreTable.PendingVersion replacement) {
            if (this.replacement != null) {
                throw new IllegalStateException(
                        "RawStore MVCC deleted-key proof was already consumed");
            }
            this.replacement = replacement;
        }

        void releaseReplacement() {
            replacement = null;
        }

        MvccRawStoreTable.PendingVersion replacement() {
            return replacement;
        }

        boolean matchesCurrentConstraints(
                List<MvccRawStoreTable.UniqueConstraint> current) {
            if (constraints.size() != current.size()) {
                return false;
            }
            for (int index = 0; index < constraints.size(); index++) {
                MvccRawStoreTable.UniqueConstraint expected = constraints.get(index);
                MvccRawStoreTable.UniqueConstraint actual = current.get(index);
                if (expected.ordinal() != actual.ordinal()
                        || !expected.matches(
                                actual.columns(),
                                actual.duplicateNullsAllowed())) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesUniqueKeys(StoreDataValue[] values) throws StandardException {
            if (values == null || values.length != table.columnCount()) {
                return false;
            }
            for (int constraintIndex = 0;
                    constraintIndex < constraints.size();
                    constraintIndex++) {
                MvccRawStoreTable.UniqueConstraint constraint =
                        constraints.get(constraintIndex);
                int[] columns = constraint.columns();
                StoreDataValue[] deletedKey = uniqueKeys.get(constraintIndex);
                if (constraint.duplicateNullsAllowed()
                        && (containsNull(deletedKey)
                            || MvccRawStoreTransactionContext.containsNull(values, columns))) {
                    return false;
                }
                for (int index = 0; index < columns.length; index++) {
                    if (StoreTypeUtil.compare(
                            deletedKey[index],
                            values[columns[index]],
                            true) != 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static boolean containsNull(StoreDataValue[] values)
                throws StandardException {
            for (StoreDataValue value : values) {
                if (StoreTypeUtil.isNull(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void reconcileDeletedKeyProofs() throws StandardException {
        for (int index = deletedKeyProofs.size() - 1; index >= 0; index--) {
            DeletedKeyProof proof = deletedKeyProofs.get(index);
            if (!MvccRawStoreTable.pendingVersionExists(
                    rawTransaction,
                    proof.tombstone(),
                    transactionId)) {
                deletedKeyProofs.remove(index);
                continue;
            }
            MvccRawStoreTable.PendingVersion replacement = proof.replacement();
            if (replacement != null
                    && !MvccRawStoreTable.pendingVersionExists(
                            rawTransaction,
                            replacement,
                            transactionId)) {
                proof.releaseReplacement();
            }
        }
    }

    private static final class OrderedIndexReconciliationFailure extends RuntimeException {
        OrderedIndexReconciliationFailure(StandardException cause) {
            super(cause);
        }
    }
}
