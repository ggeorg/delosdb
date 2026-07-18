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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.derby.iapi.store.raw.ContainerKey;
import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.apache.derby.iapi.store.types.DelosMvccConglomerateLifecycle;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTransactionRegistry;

/**
 * Database-scoped runtime ownership for the {@code delos_mvcc} access method.
 *
 * <p>One runtime owns the provider store and all bridge table states for one
 * canonicalized Derby database directory. Conglomerates are attached explicitly
 * through their owning {@link MvccConglomerateFactory}; no operation selects a
 * database through mutable ambient state.</p>
 */
final class MvccDatabaseRuntime implements AutoCloseable {
    private static final Object REGISTRY_MONITOR = new Object();
    private static final Map<DatabaseIdentity, RegistryEntry> RUNTIMES = new HashMap<>();

    private final Path databaseDirectory;
    private final DelosStorageStore store;
    private final Map<TableIdentity, MvccConglomerateState> states = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private MvccDatabaseRuntime(Path databaseDirectory) {
        this.databaseDirectory = normalize(databaseDirectory);
        MvccConglomerateLifecycleFiles.recoverInterruptedCreates(this.databaseDirectory);
        this.store = MvccConglomerateState.openStore(this.databaseDirectory);
    }

    static Lease acquire(Path databaseDirectory) {
        DatabaseIdentity identity = new DatabaseIdentity(databaseDirectory);
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = RUNTIMES.get(identity);
            if (entry == null) {
                entry = new RegistryEntry(new MvccDatabaseRuntime(identity.databaseDirectory()));
                RUNTIMES.put(identity, entry);
            }
            entry.references++;
            return new Lease(identity, entry.runtime);
        }
    }

    static MvccDatabaseRuntime require(Path databaseDirectory) {
        DatabaseIdentity identity = new DatabaseIdentity(databaseDirectory);
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = RUNTIMES.get(identity);
            if (entry == null) {
                throw new IllegalStateException(
                        "No active delos_mvcc runtime for database " + identity.databaseDirectory());
            }
            return entry.runtime;
        }
    }

    static boolean isActive(Path databaseDirectory) {
        DatabaseIdentity identity = new DatabaseIdentity(databaseDirectory);
        synchronized (REGISTRY_MONITOR) {
            return RUNTIMES.containsKey(identity);
        }
    }

    static int stateCountForDiagnostics(Path databaseDirectory) {
        DatabaseIdentity identity = new DatabaseIdentity(databaseDirectory);
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = RUNTIMES.get(identity);
            return entry == null ? 0 : entry.runtime.stateCount();
        }
    }

    static MvccDatabaseRuntime requireSingleForDiagnostics() {
        synchronized (REGISTRY_MONITOR) {
            if (RUNTIMES.size() != 1) {
                throw new IllegalStateException(
                        "MVCC diagnostics require an explicit database directory when "
                                + RUNTIMES.size() + " database runtimes are active");
            }
            return RUNTIMES.values().iterator().next().runtime;
        }
    }

    static int totalStateCountForDiagnostics() {
        synchronized (REGISTRY_MONITOR) {
            return RUNTIMES.values().stream()
                    .mapToInt(entry -> entry.runtime.stateCount())
                    .sum();
        }
    }

    static int runtimeCountForDiagnostics() {
        synchronized (REGISTRY_MONITOR) {
            return RUNTIMES.size();
        }
    }

    static void clearAllForTesting() {
        List<MvccDatabaseRuntime> runtimes;
        synchronized (REGISTRY_MONITOR) {
            runtimes = RUNTIMES.values().stream()
                    .map(entry -> entry.runtime)
                    .distinct()
                    .toList();
            RUNTIMES.clear();
        }
        Throwable failure = null;
        for (MvccDatabaseRuntime runtime : runtimes) {
            try {
                runtime.close();
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        rethrow(failure);
    }

    Path databaseDirectory() {
        return databaseDirectory;
    }

    MvccConglomerateState stateFor(ContainerKey key) {
        ensureOpen();
        TableIdentity identity = new TableIdentity(key);
        return states.computeIfAbsent(
                identity,
                ignored -> new MvccConglomerateState(identity.containerKey(), store));
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
        states.clear();
        store.close();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "delos_mvcc database runtime is closed: " + databaseDirectory);
        }
    }

    private static void release(DatabaseIdentity identity, MvccDatabaseRuntime runtime) {
        boolean close = false;
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = RUNTIMES.get(identity);
            if (entry == null || entry.runtime != runtime) {
                return;
            }
            entry.references--;
            if (entry.references == 0) {
                RUNTIMES.remove(identity);
                close = true;
            }
        }
        if (close) {
            runtime.close();
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

    static final class Lease implements AutoCloseable {
        private final DatabaseIdentity identity;
        private final MvccDatabaseRuntime runtime;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(DatabaseIdentity identity, MvccDatabaseRuntime runtime) {
            this.identity = identity;
            this.runtime = runtime;
        }

        MvccDatabaseRuntime runtime() {
            if (closed.get()) {
                throw new IllegalStateException("delos_mvcc database runtime lease is closed");
            }
            return runtime;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(identity, runtime);
            }
        }
    }

    private static final class RegistryEntry {
        private final MvccDatabaseRuntime runtime;
        private int references;

        private RegistryEntry(MvccDatabaseRuntime runtime) {
            this.runtime = runtime;
        }
    }

    private record DatabaseIdentity(Path databaseDirectory) {
        private DatabaseIdentity {
            databaseDirectory = normalize(databaseDirectory);
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
