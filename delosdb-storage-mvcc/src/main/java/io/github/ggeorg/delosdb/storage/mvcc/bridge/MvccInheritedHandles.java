package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccSnapshot;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;

import org.apache.derby.iapi.store.types.DelosStorageSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageTransaction;

final class MvccInheritedHandles {
    private MvccInheritedHandles() {
    }

    static Transaction transaction(DelosStorageTransaction transaction) {
        if (!(transaction instanceof Transaction mvccTransaction)) {
            throw new IllegalArgumentException("Expected delos_mvcc transaction handle");
        }
        return mvccTransaction;
    }

    static Snapshot snapshot(DelosStorageSnapshot snapshot) {
        if (!(snapshot instanceof Snapshot mvccSnapshot)) {
            throw new IllegalArgumentException("Expected delos_mvcc snapshot handle");
        }
        return mvccSnapshot;
    }

    record Transaction(MvccTransaction nativeTransaction) implements DelosStorageTransaction {
        Transaction {
            nativeTransaction = Objects.requireNonNull(nativeTransaction, "nativeTransaction");
        }

        @Override
        public String providerName() {
            return DelosMvccStorageProvider.PROVIDER_NAME;
        }
    }

    record Snapshot(MvccSnapshot nativeSnapshot) implements DelosStorageSnapshot {
        Snapshot {
            nativeSnapshot = Objects.requireNonNull(nativeSnapshot, "nativeSnapshot");
        }

        @Override
        public String providerName() {
            return DelosMvccStorageProvider.PROVIDER_NAME;
        }
    }
}
