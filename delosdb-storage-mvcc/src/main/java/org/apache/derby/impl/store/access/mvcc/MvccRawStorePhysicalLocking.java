/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccRawStorePhysicalLocking

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.raw.LockingPolicy;
import org.apache.derby.iapi.store.raw.Transaction;

/** Shared RawStore physical-lock policies for the converged MVCC format. */
final class MvccRawStorePhysicalLocking {
    private MvccRawStorePhysicalLocking() {
    }

    /**
     * MVCC visibility and semantic conflicts are enforced by logical identities.
     * Physical reads therefore need only container intent locks, while physical
     * writes retain RawStore record locks until transaction completion.
     */
    static LockingPolicy rowLevel(Transaction transaction) {
        return transaction.newLockingPolicy(
                LockingPolicy.MODE_RECORD,
                TransactionController.ISOLATION_READ_UNCOMMITTED,
                true);
    }

    /**
     * Container ownership operations still require an inherited exclusive
     * container boundary. They are short DDL/metadata operations, not the
     * normal row-mutation path.
     */
    static LockingPolicy containerExclusive(Transaction transaction) {
        return transaction.newLockingPolicy(
                LockingPolicy.MODE_CONTAINER,
                TransactionController.ISOLATION_SERIALIZABLE,
                true);
    }
}
