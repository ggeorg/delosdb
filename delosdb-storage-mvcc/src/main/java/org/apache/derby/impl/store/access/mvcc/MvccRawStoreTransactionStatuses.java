/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStoreTransactionStatuses

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.derby.iapi.store.access.conglomerate.TransactionManager;
import org.apache.derby.iapi.store.raw.Transaction;
import org.apache.derby.shared.common.error.StandardException;

/** Database-scoped committed-transaction visibility state. */
final class MvccRawStoreTransactionStatuses {
    private final MvccRawStoreDatabaseMetadata metadata;
    private final Map<Long, Long> committedSequences = new ConcurrentHashMap<>();
    private final boolean enabled;
    private volatile boolean loaded;

    MvccRawStoreTransactionStatuses(MvccRawStoreDatabaseMetadata metadata) {
        this.metadata = metadata;
        enabled = Boolean.getBoolean(
                MvccRawStoreFormat.GEN2_TRANSACTION_STATUS_VISIBILITY_ENABLED_PROPERTY);
    }

    void ensureLoaded(TransactionManager transactionManager) throws StandardException {
        if (!enabled || loaded) {
            return;
        }
        synchronized (committedSequences) {
            if (loaded) {
                return;
            }
            committedSequences.putAll(
                    metadata.readCommittedTransactionStatuses(transactionManager));
            loaded = true;
        }
    }

    boolean enabled() {
        return enabled;
    }

    void stage(Transaction rawTransaction, long transactionId, long commitSequence)
            throws StandardException {
        metadata.stageCommittedTransactionStatus(
                rawTransaction, transactionId, commitSequence);
    }

    void publish(long transactionId, long commitSequence) {
        Long previous = committedSequences.put(transactionId, commitSequence);
        if (previous != null && previous.longValue() != commitSequence) {
            throw new IllegalStateException(
                    "RawStore MVCC transaction status changed after commit: tx="
                            + transactionId + ", first=" + previous
                            + ", second=" + commitSequence);
        }
    }

    long committedSequence(long transactionId) {
        if (!enabled || transactionId <= 0L) {
            return 0L;
        }
        Long sequence = committedSequences.get(transactionId);
        return sequence == null ? 0L : sequence.longValue();
    }
}
