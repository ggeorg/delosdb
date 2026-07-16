package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.derby.iapi.store.types.DelosStorageBackupCoordinator;
import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;

final class MvccInheritedStore implements DelosStorageStore {
    private final Path databaseDirectory;
    private final MvccDatabaseMaintenanceService maintenanceService;
    private final DelosStorageBackupCoordinator.DatabaseLease backupCoordinatorLease;
    private final DelosStorageBackupCoordinator backupCoordinator;
    private final MvccDatabaseCommitCoordinator transactionCoordinator;
    private final Set<MvccInheritedTable> openTables = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    MvccInheritedStore(Path databaseDirectory) {
        this(
                databaseDirectory,
                new MvccDatabaseMaintenanceService(databaseDirectory),
                DelosStorageBackupCoordinator.openDatabase(databaseDirectory));
    }

    MvccInheritedStore(
            Path databaseDirectory,
            MvccDatabaseMaintenanceService maintenanceService) {
        this(
                databaseDirectory,
                maintenanceService,
                DelosStorageBackupCoordinator.openDatabase(databaseDirectory));
    }

    private MvccInheritedStore(
            Path databaseDirectory,
            MvccDatabaseMaintenanceService maintenanceService,
            DelosStorageBackupCoordinator.DatabaseLease backupCoordinatorLease) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
        this.backupCoordinatorLease = Objects.requireNonNull(backupCoordinatorLease, "backupCoordinatorLease");
        this.backupCoordinator = backupCoordinatorLease.coordinator();
        this.transactionCoordinator = new MvccDatabaseCommitCoordinator(this.databaseDirectory);
    }

    @Override
    public synchronized DelosStorageTable openTable(DelosStorageTableKey key) {
        if (closeStarted.get() || closed.get()) {
            throw new IllegalStateException("delos_mvcc store is closed");
        }
        MvccInheritedTable table = new MvccInheritedTable(
                key.segmentId(),
                key.containerId(),
                databaseDirectory,
                maintenanceService,
                backupCoordinator,
                transactionCoordinator,
                openTables::remove);
        openTables.add(table);
        if (closeStarted.get() || closed.get()) {
            table.close();
            throw new IllegalStateException("delos_mvcc store is closed");
        }
        return table;
    }

    @Override
    public synchronized void close() {
        if (closed.get()) {
            return;
        }
        closeStarted.set(true);
        maintenanceService.close();
        Throwable failure = null;
        for (MvccInheritedTable table : new ArrayList<>(openTables)) {
            try {
                table.close();
            } catch (RuntimeException | Error tableFailure) {
                if (failure == null) {
                    failure = tableFailure;
                } else {
                    failure.addSuppressed(tableFailure);
                }
            }
        }
        openTables.clear();
        try {
            backupCoordinatorLease.close();
        } catch (RuntimeException | Error leaseFailure) {
            if (failure == null) {
                failure = leaseFailure;
            } else {
                failure.addSuppressed(leaseFailure);
            }
        }
        closed.set(true);
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    MvccDatabaseMaintenanceService maintenanceServiceForTesting() {
        return maintenanceService;
    }

    DelosStorageBackupCoordinator backupCoordinatorForTesting() {
        return backupCoordinator;
    }
}
