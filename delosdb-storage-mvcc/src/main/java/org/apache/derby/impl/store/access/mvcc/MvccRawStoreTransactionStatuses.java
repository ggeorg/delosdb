/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTransactionStatuses

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;

/** Database-scoped committed-transaction visibility state. */
final class MvccRawStoreTransactionStatuses {
    static final String CACHE_SLOTS_PROPERTY =
            "delosdb.mvcc.transactionStatusCacheSlots";
    private static final int DEFAULT_CACHE_SLOTS = 4096;

    private final MvccRawStoreDatabaseMetadata metadata;
    private final MvccRawStoreRuntime runtime;
    private final AtomicReferenceArray<CacheEntry> committedSequences;
    private final boolean enabled;

    MvccRawStoreTransactionStatuses(
            MvccRawStoreDatabaseMetadata metadata,
            MvccRawStoreRuntime runtime) {
        this.metadata = metadata;
        this.runtime = runtime;
        enabled = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_TRANSACTION_STATUS_VISIBILITY_ENABLED_PROPERTY);
        committedSequences = new AtomicReferenceArray<>(configuredCacheSlots());
    }

    boolean enabled() {
        return enabled;
    }

    void stage(
            Transaction rawTransaction,
            long transactionId,
            long commitSequence,
            List<MvccRawStoreTable.PendingVersion> pendingVersions)
            throws StandardException {
        metadata.stageCommittedTransactionStatus(
                rawTransaction,
                transactionId,
                commitSequence,
                dependencyTables(pendingVersions));
    }

    void publish(long transactionId, long commitSequence) {
        cache(transactionId, commitSequence);
    }

    long committedSequence(Transaction transaction, long transactionId)
            throws StandardException {
        if (!enabled || transactionId <= 0L) {
            return 0L;
        }
        CacheEntry cached = cached(transactionId);
        if (cached != null) {
            return cached.commitSequence();
        }
        // A status row is staged before its parent RawStore transaction commits.
        // Do not inspect durable storage while that creator is still active: the
        // cache is published only after RawStore commit and the direct fallback
        // must preserve the same visibility boundary.
        if (runtime.isTransactionActive(transactionId)) {
            return 0L;
        }
        long commitSequence = metadata.readCommittedTransactionStatus(
                transaction, transactionId);
        if (commitSequence > 0L) {
            cache(transactionId, commitSequence);
        }
        return commitSequence;
    }

    MvccRawStoreDatabaseMetadata.TransactionStatusReclamation reclaimDependencies(
            Transaction transaction,
            ContainerKey table) throws StandardException {
        runtime.lockExclusive(
                transaction, MvccRawStoreLogicalLock.transactionStatusReclamation());
        return metadata.reclaimCommittedTransactionStatusDependencies(transaction, table);
    }

    void evictReclaimed(Set<Long> transactionIds) {
        for (long transactionId : transactionIds) {
            int slot = cacheSlot(transactionId);
            CacheEntry cached = committedSequences.get(slot);
            if (cached != null && cached.transactionId() == transactionId) {
                committedSequences.compareAndSet(slot, cached, null);
            }
        }
    }

    int cachedStatusCount() {
        int count = 0;
        for (int index = 0; index < committedSequences.length(); index++) {
            if (committedSequences.get(index) != null) {
                count++;
            }
        }
        return count;
    }

    int cacheCapacity() {
        return committedSequences.length();
    }

    private CacheEntry cached(long transactionId) {
        CacheEntry cached = committedSequences.get(cacheSlot(transactionId));
        return cached != null && cached.transactionId() == transactionId ? cached : null;
    }

    private void cache(long transactionId, long commitSequence) {
        if (transactionId <= 0L || commitSequence <= 0L) {
            throw new IllegalArgumentException(
                    "RawStore MVCC transaction-status cache requires positive IDs: tx="
                            + transactionId + ", commit=" + commitSequence);
        }
        int slot = cacheSlot(transactionId);
        CacheEntry replacement = new CacheEntry(transactionId, commitSequence);
        CacheEntry previous = committedSequences.getAndSet(slot, replacement);
        if (previous != null
                && previous.transactionId() == transactionId
                && previous.commitSequence() != commitSequence) {
            committedSequences.compareAndSet(slot, replacement, previous);
            throw new IllegalStateException(
                    "RawStore MVCC transaction status changed after commit: tx="
                            + transactionId + ", first=" + previous.commitSequence()
                            + ", second=" + commitSequence);
        }
    }

    private int cacheSlot(long transactionId) {
        return Math.floorMod(Long.hashCode(transactionId), committedSequences.length());
    }

    private static int configuredCacheSlots() {
        String configured = System.getProperty(
                CACHE_SLOTS_PROPERTY, Integer.toString(DEFAULT_CACHE_SLOTS));
        try {
            int slots = Integer.parseInt(configured);
            if (slots <= 0) {
                throw new IllegalArgumentException(
                        CACHE_SLOTS_PROPERTY + " must be positive: " + configured);
            }
            return slots;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    CACHE_SLOTS_PROPERTY + " must be a positive integer: " + configured,
                    failure);
        }
    }

    private static List<ContainerKey> dependencyTables(
            List<MvccRawStoreTable.PendingVersion> pendingVersions) {
        Set<ContainerKey> dependencies = new LinkedHashSet<>();
        for (MvccRawStoreTable.PendingVersion pending : pendingVersions) {
            dependencies.add(pending.table().metadataContainer());
        }
        List<ContainerKey> ordered = new ArrayList<>(dependencies);
        ordered.sort(java.util.Comparator
                .comparingLong(ContainerKey::getSegmentId)
                .thenComparingLong(ContainerKey::getContainerId));
        return List.copyOf(ordered);
    }

    private record CacheEntry(long transactionId, long commitSequence) {
    }
}
