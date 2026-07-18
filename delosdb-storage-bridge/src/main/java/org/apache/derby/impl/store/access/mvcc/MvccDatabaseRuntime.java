/*

   Derby - Class org.apache.derby.impl.store.access.mvcc.MvccDatabaseRuntime

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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.apache.derby.iapi.store.types.DelosDatabaseStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosTableStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosTransactionSnapshot;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;

/**
 * Database-scoped runtime ownership for the {@code delos_mvcc} access method.
 *
 * <p>One runtime is created and owned by one booted
 * {@link MvccConglomerateFactory}.  The runtime owns the provider store and all
 * bridge table states for that database.  No static registry participates in
 * runtime ownership or database selection.</p>
 */
final class MvccDatabaseRuntime implements AutoCloseable {
    static final int TABLE_SNAPSHOT_CAPACITY = 256;
    static final int TRANSACTION_SNAPSHOT_CAPACITY = 512;
    private final Object databaseIdentity;
    private final Path databaseDirectory;
    private final DelosStorageStore store;
    private final MvccBridgeDiagnosticsSupport diagnostics = new MvccBridgeDiagnosticsSupport();
    private final Map<TableIdentity, MvccConglomerateState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    MvccDatabaseRuntime(Object databaseIdentity, Path databaseDirectory) {
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
        this.databaseDirectory = normalize(databaseDirectory);
        MvccConglomerateLifecycleFiles.recoverInterruptedCreates(this.databaseDirectory);
        this.store = MvccConglomerateState.openStore(this.databaseDirectory);
        MvccRuntimeDiagnosticsDirectory.register(this.databaseDirectory, this);
    }

    Object databaseIdentity() {
        return databaseIdentity;
    }

    Path databaseDirectory() {
        return databaseDirectory;
    }

    MvccConglomerateState stateFor(ContainerKey key) {
        ensureOpen();
        TableIdentity identity = new TableIdentity(key);
        return states.computeIfAbsent(
                identity,
                ignored -> new MvccConglomerateState(identity.containerKey(), store, diagnostics));
    }

    MvccConglomerateState stateForDiagnostics(int segment, long containerId) {
        return stateFor(new ContainerKey(segment, containerId));
    }

    void stageCreate(DelosMvccConglomerateLifecycle lifecycle) {
        ensureOpen();
        MvccConglomerateLifecycleFiles.stageCreate(databaseDirectory, lifecycle);
    }

    void completeCreate(DelosMvccConglomerateLifecycle lifecycle) {
        ensureOpen();
        MvccConglomerateLifecycleFiles.completeCreate(databaseDirectory, lifecycle);
    }

    void abortCreate(
            ContainerKey key,
            DelosMvccConglomerateLifecycle lifecycle) {
        ensureOpen();
        TableIdentity identity = new TableIdentity(key);
        MvccConglomerateState currentState = states.get(identity);
        Throwable failure = null;
        if (currentState != null) {
            try {
                retireState(identity, currentState);
            } catch (RuntimeException | Error retirementFailure) {
                failure = retirementFailure;
            }
        }
        if (failure == null) {
            try {
                MvccConglomerateLifecycleFiles.abortCreate(databaseDirectory, lifecycle);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = cleanupFailure;
            }
        }
        rethrow(failure);
    }

    void completeDrop(
            ContainerKey key,
            DelosMvccConglomerateLifecycle lifecycle) {
        ensureOpen();
        TableIdentity identity = new TableIdentity(key);
        MvccConglomerateState currentState = states.get(identity);
        Throwable failure = null;
        if (currentState != null) {
            try {
                retireState(identity, currentState);
            } catch (RuntimeException | Error retirementFailure) {
                failure = retirementFailure;
            }
        }
        if (failure == null) {
            try {
                MvccConglomerateLifecycleFiles.completeDrop(databaseDirectory, lifecycle);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = cleanupFailure;
            }
        }
        rethrow(failure);
    }

    private void retireState(
            TableIdentity identity,
            MvccConglomerateState currentState) {
        // Provider transactions and snapshots must be detached before the table
        // is removed from runtime ownership or its durable state is deleted.
        DelosStorageTransactionRegistry.abortTableParticipants(currentState.table());
        currentState.dropDurableState();
        if (!states.remove(identity, currentState)) {
            throw new IllegalStateException(
                    "delos_mvcc runtime ownership changed while retiring "
                            + identity.containerKey());
        }
        currentState.close();
    }

    int stateCount() {
        return states.size();
    }

    DelosDatabaseStorageSnapshot databaseStorageSnapshotForDiagnostics() {
        ensureOpen();
        long capturedAtEpochMillis = System.currentTimeMillis();
        String databaseIdentity = databaseDirectory.toString();
        List<Map.Entry<TableIdentity, MvccConglomerateState>> orderedStates = states.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey(Comparator
                        .comparingLong(TableIdentity::segmentId)
                        .thenComparingLong(TableIdentity::containerId)))
                .toList();

        List<DelosTableStorageSnapshot> tableSnapshots = new ArrayList<>();
        List<DelosTransactionSnapshot> transactionSnapshots = new ArrayList<>();
        long droppedTables = Math.max(0L, orderedStates.size() - TABLE_SNAPSHOT_CAPACITY);
        long droppedTransactions = 0L;

        for (int i = 0; i < orderedStates.size(); i++) {
            MvccConglomerateState state = orderedStates.get(i).getValue();
            List<DelosTransactionSnapshot> tableTransactions;
            try {
                if (i < TABLE_SNAPSHOT_CAPACITY) {
                    MvccConglomerateState.StructuredObservation observation =
                            state.structuredObservation(databaseIdentity, capturedAtEpochMillis);
                    tableSnapshots.add(observation.tableSnapshot());
                    tableTransactions = observation.transactionSnapshots();
                } else {
                    tableTransactions = state.transactionSnapshots(
                            databaseIdentity, capturedAtEpochMillis);
                }
            } catch (IllegalStateException retiredDuringCapture) {
                if (i < TABLE_SNAPSHOT_CAPACITY) {
                    droppedTables++;
                }
                continue;
            }
            for (DelosTransactionSnapshot transactionSnapshot : tableTransactions) {
                if (transactionSnapshots.size() < TRANSACTION_SNAPSHOT_CAPACITY) {
                    transactionSnapshots.add(transactionSnapshot);
                } else {
                    droppedTransactions++;
                }
            }
        }

        return diagnostics.snapshot(
                databaseDirectory,
                true,
                stateCount(),
                capturedAtEpochMillis,
                TABLE_SNAPSHOT_CAPACITY,
                droppedTables,
                tableSnapshots,
                TRANSACTION_SNAPSHOT_CAPACITY,
                droppedTransactions,
                transactionSnapshots,
                store.databaseCommitTimingSnapshotForTesting());
    }

    MvccBridgeDiagnosticsSupport diagnosticsForBridge() {
        ensureOpen();
        return diagnostics;
    }

    DelosDatabaseCommitTimingSnapshot databaseCommitTimingSnapshotForDiagnostics() {
        ensureOpen();
        return store.databaseCommitTimingSnapshotForTesting();
    }

    void resetDatabaseCommitTimingForDiagnostics() {
        ensureOpen();
        store.resetDatabaseCommitTimingForTesting();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        MvccRuntimeDiagnosticsDirectory.unregister(databaseDirectory, this);
        states.clear();
        store.close();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "delos_mvcc database runtime is closed: " + databaseDirectory);
        }
    }

    private static Path normalize(Path databaseDirectory) {
        Path absolute = Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .toAbsolutePath()
                .normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException ignored) {
            // Derby can construct the service path before every filesystem
            // component is visible. The normalized absolute path remains a
            // stable fallback; subsequent acquisitions use the same service root.
            return absolute;
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private record TableIdentity(long segmentId, long containerId) {
        private TableIdentity(ContainerKey key) {
            this(
                    Objects.requireNonNull(key, "key").getSegmentId(),
                    key.getContainerId());
        }

        private ContainerKey containerKey() {
            return new ContainerKey(segmentId, containerId);
        }
    }
}
