/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccBridgeIsolationPolicy

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package org.apache.derby.impl.store.access.mvcc;

import org.apache.derby.iapi.store.access.TransactionController;

/**
 * Explicit MVCC read-view policy at the Derby access-method boundary.
 *
 * <p>The storage-api table already owns the native MVCC snapshot object. This
 * policy records only how Derby isolation levels should choose that snapshot:
 * READ COMMITTED and weaker levels get a fresh statement read view, while
 * REPEATABLE READ and SERIALIZABLE reuse the Derby transaction-scoped reader
 * snapshot held in {@code DelosStorageTransactionRegistry}. Keeping this
 * mapping here prevents future changes from hiding the isolation contract in a
 * boolean expression inside the scan controller.</p>
 *
 * <p>This policy is a read-view selection policy only. It does not introduce
 * new predicate-lock, range-lock, or phantom-prevention semantics; those remain
 * the responsibility of Derby's access/locking layers and must not be inferred
 * from the SERIALIZABLE-to-transaction-snapshot mapping alone.</p>
 */
final class MvccBridgeIsolationPolicy {
    private final int derbyIsolationLevel;
    private final boolean transactionScopedSnapshot;

    private MvccBridgeIsolationPolicy(int derbyIsolationLevel, boolean transactionScopedSnapshot) {
        this.derbyIsolationLevel = derbyIsolationLevel;
        this.transactionScopedSnapshot = transactionScopedSnapshot;
    }

    static MvccBridgeIsolationPolicy fromDerbyIsolationLevel(int isolationLevel) {
        return switch (isolationLevel) {
            case TransactionController.ISOLATION_REPEATABLE_READ,
                    TransactionController.ISOLATION_SERIALIZABLE ->
                    new MvccBridgeIsolationPolicy(isolationLevel, true);
            default -> new MvccBridgeIsolationPolicy(isolationLevel, false);
        };
    }

    int derbyIsolationLevel() {
        return derbyIsolationLevel;
    }

    boolean usesTransactionScopedSnapshot() {
        return transactionScopedSnapshot;
    }

    boolean usesStatementScopedSnapshot() {
        return !transactionScopedSnapshot;
    }
}
