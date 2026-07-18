package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;

/**
 * Centralizes the table's operational-state checks, lock acquisition, and
 * database-backup mutation boundary.
 */
final class MvccInheritedTableAccess {
    private final DelosStorageBackupCoordinator backupCoordinator;
    private final Lock readLock;
    private final Lock writeLock;
    private final Runnable requireOperational;

    MvccInheritedTableAccess(
            DelosStorageBackupCoordinator backupCoordinator,
            Lock readLock,
            Lock writeLock,
            Runnable requireOperational) {
        this.backupCoordinator = Objects.requireNonNull(backupCoordinator, "backupCoordinator");
        this.readLock = Objects.requireNonNull(readLock, "readLock");
        this.writeLock = Objects.requireNonNull(writeLock, "writeLock");
        this.requireOperational = Objects.requireNonNull(requireOperational, "requireOperational");
    }

    <T> T read(Supplier<T> operation) {
        requireOperational.run();
        readLock.lock();
        try {
            requireOperational.run();
            return operation.get();
        } finally {
            readLock.unlock();
        }
    }

    void read(Runnable operation) {
        read(() -> {
            operation.run();
            return null;
        });
    }

    <T> T write(Supplier<T> operation) {
        requireOperational.run();
        writeLock.lock();
        try {
            requireOperational.run();
            return operation.get();
        } finally {
            writeLock.unlock();
        }
    }

    void write(Runnable operation) {
        write(() -> {
            operation.run();
            return null;
        });
    }

    <T> T durable(
            DelosStorageBackupCoordinator.Mutation mutation,
            Supplier<T> operation) {
        try (DelosStorageBackupCoordinator.Guard ignored =
                     backupCoordinator.enterDurableMutation(mutation)) {
            return write(operation);
        }
    }

    void durable(
            DelosStorageBackupCoordinator.Mutation mutation,
            Runnable operation) {
        durable(mutation, () -> {
            operation.run();
            return null;
        });
    }
}
