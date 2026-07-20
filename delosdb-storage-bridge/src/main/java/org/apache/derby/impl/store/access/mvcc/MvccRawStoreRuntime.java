/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreRuntime

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.derby.iapi.services.locks.C_LockFactory;
import org.apache.derby.iapi.services.locks.LockFactory;
import org.apache.derby.iapi.services.locks.ShExQual;

import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;

/** Database-scoped semantic coordinator for the isolated RawStore table format. */
final class MvccRawStoreRuntime {
    static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    static final String AFTER_STAMP_BEFORE_RAW_COMMIT =
            "after-stamp-before-raw-commit";
    static final String AFTER_RAW_COMMIT_BEFORE_PUBLICATION =
            "after-raw-commit-before-publication";

    private final Object databaseIdentity;
    private final LockFactory lockFactory;
    private final ReentrantLock commitPublicationLock = new ReentrantLock();
    private final MvccRawStoreDatabaseMetadata metadata = new MvccRawStoreDatabaseMetadata();
    private final AtomicLong publishedHighWater = new AtomicLong();
    private final Map<Long, TableIdentityAllocator> tableIdentityAllocators = new HashMap<>();
    private final Set<Long> activeTransactionIds = ConcurrentHashMap.newKeySet();

    MvccRawStoreRuntime(Object databaseIdentity, LockFactory lockFactory) {
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        this.lockFactory = Objects.requireNonNull(lockFactory, "lockFactory");
    }

    Object databaseIdentity() {
        return databaseIdentity;
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
