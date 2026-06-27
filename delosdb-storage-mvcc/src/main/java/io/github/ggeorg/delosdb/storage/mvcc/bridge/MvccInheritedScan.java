package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import io.github.ggeorg.delosdb.storage.mvcc.MvccRow;
import io.github.ggeorg.delosdb.storage.mvcc.MvccScan;

import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.shared.common.error.StandardException;

final class MvccInheritedScan implements DelosStorageScan {
    private final MvccScan<Long, StoreDataValue[]> scan;
    private DelosStorageRow current;

    MvccInheritedScan(MvccScan<Long, StoreDataValue[]> scan) {
        this.scan = scan;
    }

    @Override
    public boolean next() throws StandardException {
        if (!scan.next()) {
            current = null;
            return false;
        }
        MvccRow<Long, StoreDataValue[]> row = scan.row();
        current = new DelosStorageRow(row.key(), row.value());
        return true;
    }

    @Override
    public DelosStorageRow row() {
        if (current == null) {
            throw new IllegalStateException("MVCC storage-api scan is not positioned on a row");
        }
        return current;
    }

    @Override
    public void close() {
        scan.close();
        current = null;
    }
}
