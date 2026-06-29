package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;

import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageProviderFactory;

/** storage-api service entry for inherited Derby MVCC compatibility. */
public final class MvccInheritedStorageProviderFactory implements DelosStorageProviderFactory {
    @Override
    public String providerName() {
        return "delos_mvcc";
    }

    @Override
    public DelosStorageStore openStore(Path databaseDirectory) {
        return new MvccInheritedStore(databaseDirectory);
    }
}
