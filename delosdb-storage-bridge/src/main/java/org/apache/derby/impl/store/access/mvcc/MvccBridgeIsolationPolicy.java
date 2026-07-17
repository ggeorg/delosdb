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
import org.apache.derby.shared.common.error.StandardException;

/**
 * Explicit MVCC read-view policy at the Derby access-method boundary.
 *
 * <p>The storage-api table already owns the native MVCC snapshot object. This
 * policy records only how Derby isolation levels should choose that snapshot:
 * READ COMMITTED and weaker levels get a fresh statement read view, while
 * REPEATABLE READ reuses the Derby transaction-scoped reader snapshot held in
 * {@code DelosStorageTransactionRegistry}. Derby also requests SERIALIZABLE
 * scans internally while building constraints and indexes; those internal
 * scans use the same transaction-scoped view. User-visible SERIALIZABLE access
 * is rejected at the SQL execution boundary, where Derby can distinguish the
 * connection contract from internal access-method work. Keeping this
 * mapping here prevents future changes from hiding the isolation contract in a
 * boolean expression inside the scan controller.</p>
 *
 * <p>For {@code delos_mvcc}, user-visible SERIALIZABLE remains deliberately
 * unsupported in v1. This class must nevertheless accept Derby's internal
 * SERIALIZABLE scans used by DDL maintenance. The engine-level SQL gate rejects
 * the user contract before opening the user scan.</p>
 */
final class MvccBridgeIsolationPolicy {
    private final int derbyIsolationLevel;
    private final boolean transactionScopedSnapshot;

    private MvccBridgeIsolationPolicy(int derbyIsolationLevel, boolean transactionScopedSnapshot) {
        this.derbyIsolationLevel = derbyIsolationLevel;
        this.transactionScopedSnapshot = transactionScopedSnapshot;
    }

    static MvccBridgeIsolationPolicy fromDerbyIsolationLevel(int isolationLevel)
            throws StandardException {
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
