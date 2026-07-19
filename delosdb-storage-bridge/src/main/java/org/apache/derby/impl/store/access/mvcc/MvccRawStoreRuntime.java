/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreRuntime

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.derby.iapi.store.access.conglomerate.AccessMethodTransactionLifecycle;
import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;
import org.apache.derby.shared.common.reference.SQLState;

/** Database-scoped semantic coordinator for the isolated RawStore table format. */
final class MvccRawStoreRuntime {
    static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";
    static final String AFTER_STAMP_BEFORE_RAW_COMMIT =
            "after-stamp-before-raw-commit";
    static final String AFTER_RAW_COMMIT_BEFORE_PUBLICATION =
            "after-raw-commit-before-publication";

    private final Object databaseIdentity;
    private final ReentrantLock commitPublicationLock = new ReentrantLock();
    private final AtomicLong nextTransactionId = new AtomicLong(1L);
    private final AtomicLong publishedHighWater = new AtomicLong();

    MvccRawStoreRuntime(Object databaseIdentity) {
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
    }

    Object databaseIdentity() {
        return databaseIdentity;
    }

    MvccRawStoreTransactionContext context(
            TransactionManager transactionManager,
            Transaction rawTransaction) {
        AccessMethodTransactionLifecycle existing =
                transactionManager.accessMethodTransactionLifecycle(this);
        if (existing != null) {
            return (MvccRawStoreTransactionContext) existing;
        }
        MvccRawStoreTransactionContext context = new MvccRawStoreTransactionContext(
                this,
                transactionManager,
                rawTransaction,
                nextTransactionId.getAndIncrement());
        transactionManager.registerAccessMethodTransactionLifecycle(this, context);
        return context;
    }

    long captureSnapshot() {
        commitPublicationLock.lock();
        try {
            return publishedHighWater.get();
        } finally {
            commitPublicationLock.unlock();
        }
    }

    long reserveCommitSequence() throws StandardException {
        commitPublicationLock.lock();
        long published = publishedHighWater.get();
        if (published == Long.MAX_VALUE) {
            commitPublicationLock.unlock();
            throw StandardException.newException(
                    SQLState.NOT_IMPLEMENTED,
                    "RawStore-backed MVCC commit sequence is exhausted");
        }
        return published + 1L;
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

    static void haltAtFailurePoint(String failurePoint, int status) {
        if (failurePoint.equals(System.getProperty(FAILURE_POINT_PROPERTY))) {
            Runtime.getRuntime().halt(status);
        }
    }
}
