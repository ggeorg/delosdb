package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import org.apache.derby.iapi.store.types.DelosRowIdentity;

/** Opaque storage-api row identity for the native MVCC provider. */
public record MvccStorageLocator(Object nativeIdentity) implements DelosRowIdentity {
    public MvccStorageLocator {
        nativeIdentity = Objects.requireNonNull(nativeIdentity, "nativeIdentity");
    }

    public static MvccStorageLocator of(long rowKey) {
        return new MvccStorageLocator(rowKey);
    }

    @Override
    public String providerName() {
        return MvccStorageProvider.PROVIDER_NAME;
    }

    public long rowKey() {
        return requireLong(this);
    }

    public static long requireLong(DelosRowIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!MvccStorageProvider.PROVIDER_NAME.equals(identity.providerName())) {
            throw new IllegalArgumentException("Row identity belongs to provider " + identity.providerName());
        }
        Object nativeIdentity = identity.nativeIdentity();
        if (nativeIdentity instanceof Long rowKey) {
            return rowKey;
        }
        throw new IllegalArgumentException("delos_mvcc row identity must wrap a Long key");
    }
}
