/*

   Derby - Class org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator

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

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Database-scoped boundary between MVCC durable mutations and online backup.
 *
 * <p>Normal MVCC durable mutations take a shared guard. The raw-store backup
 * copier takes the exclusive guard for the same normalized database identity,
 * producing one cross-subsystem sidecar image without blocking unrelated
 * databases in the same JVM.</p>
 */
public final class DelosStorageBackupCoordinator {
    private static final Object REGISTRY_MONITOR = new Object();
    private static final Map<String, RegistryEntry> DATABASES = new HashMap<>();
    private static final AtomicLong ISOLATED_IDENTITIES = new AtomicLong();

    private final String databaseIdentity;
    private final ReentrantReadWriteLock boundary = new ReentrantReadWriteLock(true);
    private final AtomicLongArray mutationEntries = new AtomicLongArray(Mutation.values().length);
    private final AtomicInteger waitingDurableMutations = new AtomicInteger();
    private final AtomicInteger activeDurableMutations = new AtomicInteger();
    private final AtomicInteger maximumActiveDurableMutations = new AtomicInteger();
    private final AtomicLong durableMutationWaitNanos = new AtomicLong();
    private final AtomicLong maximumDurableMutationWaitNanos = new AtomicLong();
    private final AtomicInteger waitingBackupSnapshots = new AtomicInteger();
    private final AtomicLong backupSnapshotStarts = new AtomicLong();
    private final AtomicLong backupSnapshotCompletions = new AtomicLong();
    private final AtomicLong backupSnapshotWaitNanos = new AtomicLong();
    private final AtomicLong maximumBackupSnapshotWaitNanos = new AtomicLong();
    private final AtomicLong committedTransactions = new AtomicLong();
    private final AtomicLong lastBackupStartCommittedTransactions = new AtomicLong();
    private final AtomicLong lastBackupEndCommittedTransactions = new AtomicLong();

    private DelosStorageBackupCoordinator(String databaseIdentity) {
        this.databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
    }

    /** Opens a shared coordinator lease for one normalized database directory. */
    public static DatabaseLease openDatabase(Path databaseDirectory) {
        if (databaseDirectory == null) {
            return isolatedDatabase("unnamed-database");
        }
        return openDatabase(normalizePath(databaseDirectory));
    }

    /** Opens a shared coordinator lease for one canonical database identity. */
    public static DatabaseLease openDatabase(String canonicalDatabaseIdentity) {
        String identity = normalizeIdentity(canonicalDatabaseIdentity);
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = DATABASES.get(identity);
            if (entry == null) {
                entry = new RegistryEntry(new DelosStorageBackupCoordinator(identity));
                DATABASES.put(identity, entry);
            }
            entry.references++;
            return new DatabaseLease(identity, entry.coordinator, true);
        }
    }

    /** Creates an unregistered coordinator for standalone provider tests. */
    public static DatabaseLease isolatedDatabase(String description) {
        String identity = "isolated:" + normalizeDescription(description) + ':'
                + ISOLATED_IDENTITIES.incrementAndGet();
        return new DatabaseLease(identity, new DelosStorageBackupCoordinator(identity), false);
    }

    public String databaseIdentity() {
        return databaseIdentity;
    }

    public Guard enterDurableMutation(Mutation mutation) {
        mutation = Objects.requireNonNull(mutation, "mutation");
        Lock lock = boundary.readLock();
        waitingDurableMutations.incrementAndGet();
        long started = System.nanoTime();
        lock.lock();
        long waited = System.nanoTime() - started;
        waitingDurableMutations.decrementAndGet();
        durableMutationWaitNanos.addAndGet(waited);
        updateMaximum(maximumDurableMutationWaitNanos, waited);
        mutationEntries.incrementAndGet(mutation.ordinal());
        int active = activeDurableMutations.incrementAndGet();
        updateMaximum(maximumActiveDurableMutations, active);
        return new Guard(() -> {
            activeDurableMutations.decrementAndGet();
            lock.unlock();
        });
    }

    public Guard enterBackupSnapshot() {
        Lock lock = boundary.writeLock();
        waitingBackupSnapshots.incrementAndGet();
        long started = System.nanoTime();
        lock.lock();
        long waited = System.nanoTime() - started;
        waitingBackupSnapshots.decrementAndGet();
        backupSnapshotWaitNanos.addAndGet(waited);
        updateMaximum(maximumBackupSnapshotWaitNanos, waited);
        backupSnapshotStarts.incrementAndGet();
        lastBackupStartCommittedTransactions.set(committedTransactions.get());
        return new Guard(() -> {
            lastBackupEndCommittedTransactions.set(committedTransactions.get());
            backupSnapshotCompletions.incrementAndGet();
            lock.unlock();
        });
    }

    /** Records terminal COMMITTED statuses while the durable-mutation guard is held. */
    public void recordCommittedTransactions(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        if (count == 0) {
            return;
        }
        if (boundary.getReadHoldCount() == 0 && !boundary.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException(
                    "committed transactions must be recorded inside the backup boundary");
        }
        committedTransactions.addAndGet(count);
    }

    public Snapshot snapshot() {
        EnumMap<Mutation, Long> entries = new EnumMap<>(Mutation.class);
        for (Mutation mutation : Mutation.values()) {
            entries.put(mutation, mutationEntries.get(mutation.ordinal()));
        }
        return new Snapshot(
                databaseIdentity,
                Map.copyOf(entries),
                waitingDurableMutations.get(),
                activeDurableMutations.get(),
                maximumActiveDurableMutations.get(),
                durableMutationWaitNanos.get(),
                maximumDurableMutationWaitNanos.get(),
                waitingBackupSnapshots.get(),
                backupSnapshotStarts.get(),
                backupSnapshotCompletions.get(),
                backupSnapshotWaitNanos.get(),
                maximumBackupSnapshotWaitNanos.get(),
                committedTransactions.get(),
                lastBackupStartCommittedTransactions.get(),
                lastBackupEndCommittedTransactions.get());
    }

    private static void releaseDatabase(String identity, DelosStorageBackupCoordinator coordinator) {
        synchronized (REGISTRY_MONITOR) {
            RegistryEntry entry = DATABASES.get(identity);
            if (entry == null || entry.coordinator != coordinator) {
                return;
            }
            entry.references--;
            if (entry.references == 0) {
                DATABASES.remove(identity);
            }
        }
    }

    private static String normalizeIdentity(String identity) {
        Objects.requireNonNull(identity, "canonicalDatabaseIdentity");
        String normalized = identity.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("canonicalDatabaseIdentity must not be blank");
        }
        try {
            return normalizePath(Path.of(normalized));
        } catch (RuntimeException invalidPath) {
            return normalized;
        }
    }

    private static String normalizePath(Path path) {
        try {
            return path.toFile().getCanonicalPath();
        } catch (IOException | SecurityException unavailable) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static String normalizeDescription(String description) {
        String value = Objects.requireNonNullElse(description, "database").trim();
        return value.isEmpty() ? "database" : value;
    }

    private static void updateMaximum(AtomicLong maximum, long candidate) {
        long current = maximum.get();
        while (candidate > current && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        int current = maximum.get();
        while (candidate > current && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    public enum Mutation {
        TRANSACTION_BEGIN,
        COMMIT_PUBLICATION,
        TRANSACTION_ABORT,
        PREPARATION_FAILURE_CLEANUP,
        VACUUM,
        ASYNCHRONOUS_MAINTENANCE,
        DROP_DURABLE_STATE,
        TABLE_CLOSE
    }

    public record Snapshot(
            String databaseIdentity,
            Map<Mutation, Long> mutationEntries,
            int waitingDurableMutationCount,
            int activeDurableMutationCount,
            int maximumActiveDurableMutationCount,
            long durableMutationWaitNanos,
            long maximumDurableMutationWaitNanos,
            int waitingBackupSnapshotCount,
            long backupSnapshotStartCount,
            long backupSnapshotCompletionCount,
            long backupSnapshotWaitNanos,
            long maximumBackupSnapshotWaitNanos,
            long committedTransactionCount,
            long lastBackupStartCommittedTransactionCount,
            long lastBackupEndCommittedTransactionCount) {
        public Snapshot {
            databaseIdentity = Objects.requireNonNull(databaseIdentity, "databaseIdentity");
            mutationEntries = Map.copyOf(Objects.requireNonNull(mutationEntries, "mutationEntries"));
        }

        public long mutationEntryCount(Mutation mutation) {
            return mutationEntries.getOrDefault(Objects.requireNonNull(mutation, "mutation"), 0L);
        }
    }

    public static final class DatabaseLease implements AutoCloseable {
        private final String identity;
        private final DelosStorageBackupCoordinator coordinator;
        private final boolean registered;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DatabaseLease(
                String identity,
                DelosStorageBackupCoordinator coordinator,
                boolean registered) {
            this.identity = identity;
            this.coordinator = coordinator;
            this.registered = registered;
        }

        public DelosStorageBackupCoordinator coordinator() {
            if (closed.get()) {
                throw new IllegalStateException("backup coordinator lease is closed: " + identity);
            }
            return coordinator;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && registered) {
                releaseDatabase(identity, coordinator);
            }
        }
    }

    public static final class Guard implements AutoCloseable {
        private Runnable release;

        private Guard(Runnable release) {
            this.release = release;
        }

        @Override
        public void close() {
            Runnable held = release;
            if (held != null) {
                release = null;
                held.run();
            }
        }
    }

    private static final class RegistryEntry {
        private final DelosStorageBackupCoordinator coordinator;
        private int references;

        private RegistryEntry(DelosStorageBackupCoordinator coordinator) {
            this.coordinator = coordinator;
        }
    }
}
