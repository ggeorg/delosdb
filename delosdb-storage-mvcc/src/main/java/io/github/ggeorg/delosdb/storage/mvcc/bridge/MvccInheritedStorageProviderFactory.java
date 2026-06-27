package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.nio.file.Path;

import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import org.apache.derby.iapi.store.types.DelosStorageStore;
import org.apache.derby.iapi.store.types.DelosStorageProviderFactory;

/** storage-api service entry for inherited Derby MVCC compatibility. */
public final class MvccInheritedStorageProviderFactory implements DelosStorageProviderFactory {
    @Override
    public String providerName() {
        return DelosMvccStorageProvider.PROVIDER_NAME;
    }

    @Override
    public DelosStorageStore openStore(Path databaseDirectory) {
        return new MvccInheritedStore(databaseDirectory);
    }
}
