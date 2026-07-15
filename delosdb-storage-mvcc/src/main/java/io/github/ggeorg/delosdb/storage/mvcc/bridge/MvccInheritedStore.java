package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;

final class MvccInheritedStore implements DelosStorageStore {
    private final Path databaseDirectory;
    private final MvccDatabaseMaintenanceService maintenanceService;
    private final Set<MvccInheritedTable> openTables = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    MvccInheritedStore(Path databaseDirectory) {
        this(databaseDirectory, new MvccDatabaseMaintenanceService(databaseDirectory));
    }

    MvccInheritedStore(
            Path databaseDirectory,
            MvccDatabaseMaintenanceService maintenanceService) {
        this.databaseDirectory = databaseDirectory == null
                ? null
                : databaseDirectory.toAbsolutePath().normalize();
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService");
    }

    @Override
    public synchronized DelosStorageTable openTable(DelosStorageTableKey key) {
        if (closed.get()) {
            throw new IllegalStateException("delos_mvcc store is closed");
        }
        MvccInheritedTable table = new MvccInheritedTable(
                key.segmentId(),
                key.containerId(),
                databaseDirectory,
                maintenanceService,
                openTables::remove);
        openTables.add(table);
        if (closed.get()) {
            table.close();
            throw new IllegalStateException("delos_mvcc store is closed");
        }
        return table;
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        maintenanceService.close();
        for (MvccInheritedTable table : new ArrayList<>(openTables)) {
            table.close();
        }
        openTables.clear();
    }

    MvccDatabaseMaintenanceService maintenanceServiceForTesting() {
        return maintenanceService;
    }
}
