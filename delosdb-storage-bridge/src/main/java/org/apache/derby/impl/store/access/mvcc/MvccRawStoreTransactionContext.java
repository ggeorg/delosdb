/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTransactionContext

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.DatabaseInstant;
import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;
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

    private long transactionId;
    private long snapshotSequence = UNCAPTURED_SNAPSHOT;
    private long reservedCommitSequence;
    private boolean publicationLockHeld;

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
        try {
            DelosStorageTransactionRegistry.registerRawStoreOwnedMvcc(transactionManager);
        } catch (IllegalStateException mixedAuthorities) {
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    mixedAuthorities,
                    mixedAuthorities.getMessage());
        }
        ensureTransactionId();
    }

    void addPending(MvccRawStoreTable.PendingVersion version) {
        pending.add(version);
    }

    long snapshotSequence() {
        if (snapshotSequence == UNCAPTURED_SNAPSHOT) {
            snapshotSequence = runtime.captureSnapshot();
        }
        return snapshotSequence;
    }

    @Override
    public void beforeCommit(CommitMode mode) throws StandardException {
        if (pending.isEmpty()) {
            return;
        }
        reservedCommitSequence = runtime.reserveCommitSequence(rawTransaction);
        publicationLockHeld = true;
        try {
            MvccRawStoreTable.stampPendingVersions(
                    rawTransaction,
                    List.copyOf(pending),
                    reservedCommitSequence);
            runtime.stageCommittedHighWater(rawTransaction, reservedCommitSequence);
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_STAMP_BEFORE_RAW_COMMIT,
                    91);
        } catch (StandardException | RuntimeException | Error failure) {
            runtime.unlockWithoutPublication();
            publicationLockHeld = false;
            reservedCommitSequence = 0L;
            throw failure;
        }
    }

    @Override
    public void afterCommit(CommitMode mode, DatabaseInstant instant) {
        if (publicationLockHeld) {
            MvccRawStoreRuntime.haltAtFailurePoint(
                    MvccRawStoreRuntime.AFTER_RAW_COMMIT_BEFORE_PUBLICATION,
                    92);
            runtime.publishAndUnlock(reservedCommitSequence);
        }
        clearLocalState();
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
        pending.clear();
        savepoints.clear();
        transactionId = 0L;
        snapshotSequence = UNCAPTURED_SNAPSHOT;
        reservedCommitSequence = 0L;
        publicationLockHeld = false;
    }

    private record SavepointMarker(SavepointIdentity identity, int pendingSize) {
    }
}
