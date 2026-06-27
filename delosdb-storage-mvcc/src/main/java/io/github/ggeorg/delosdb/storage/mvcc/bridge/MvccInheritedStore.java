package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;

import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageTable;
import org.apache.derby.iapi.store.types.DelosStorageTableKey;

final class MvccInheritedStore implements DelosStorageStore {
    private final Path databaseDirectory;

    MvccInheritedStore(Path databaseDirectory) {
        this.databaseDirectory = databaseDirectory == null ? null : databaseDirectory.toAbsolutePath().normalize();
    }

    @Override
    public DelosStorageTable openTable(DelosStorageTableKey key) {
        return new MvccInheritedTable(key.segmentId(), key.containerId(), databaseDirectory);
    }
}
