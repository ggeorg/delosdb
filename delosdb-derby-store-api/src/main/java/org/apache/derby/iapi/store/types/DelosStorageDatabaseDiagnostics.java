/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageDatabaseDiagnostics

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
package org.apache.derby.iapi.store.types;

import java.util.List;

/** Database-scoped storage runtime and lifecycle diagnostics. */
interface DelosStorageDatabaseDiagnostics {
    String providerId();

    /**
     * Return a diagnostics view bound to an explicit request context.
     *
     * <p>Implementations that need filesystem context, such as the Derby heap
     * compatibility diagnostics, should prefer this method over mutable
     * set/clear hooks. Providers which do not need context may return
     * {@code this}.</p>
     */
    default DelosStorageDiagnostics withContext(DelosStorageDiagnosticsContext context) {
        return (DelosStorageDiagnostics) this;
    }

    default void clearRuntimeStateForTesting() {
    }

    default int runtimeStateCountForTesting() {
        return 0;
    }

    /**
     * Return whether the explicitly bound provider runtime is currently active.
     *
     * <p>Providers which do not expose a database-scoped runtime may retain the
     * default value. This method exists primarily so lifecycle tests can verify
     * one database without making assertions about unrelated databases that may
     * remain booted in the same JVM.</p>
     */
    default boolean runtimeActiveForTesting() {
        return false;
    }

    /** Return a versioned immutable observation for one database runtime. */
    default DelosDatabaseStorageSnapshot databaseStorageSnapshot() {
        return DelosDatabaseStorageSnapshot.unavailable(providerId());
    }

    /** Return the immutable database-owned maintenance and reclamation observation. */
    default DelosStorageMaintenanceSnapshot databaseMaintenanceSnapshot() {
        return DelosStorageMaintenanceSnapshot.unavailable(providerId());
    }

    /** Return database-scoped inherited memory-storage accounting. */
    default DelosDatabaseMemorySnapshot databaseMemorySnapshot() {
        return DelosDatabaseMemorySnapshot.unavailable(providerId());
    }

    /** Return database-scoped shared RawStore page-I/O accounting. */
    default DelosRawStoreIoSnapshot databaseRawStoreIoSnapshot() {
        return DelosRawStoreIoSnapshot.unavailable();
    }

    default List<DelosTableStorageSnapshot> tableStorageSnapshots() {
        return databaseStorageSnapshot().tableSnapshots();
    }

    default List<DelosTransactionSnapshot> transactionSnapshots() {
        return databaseStorageSnapshot().transactionSnapshots();
    }

    default DelosDatabaseCommitTimingSnapshot databaseCommitTimingSnapshotForTesting() {
        return DelosDatabaseCommitTimingSnapshot.EMPTY;
    }

    default void resetDatabaseCommitTimingForTesting() {
    }
}
