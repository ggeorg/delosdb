package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.Iterator;
import java.util.List;

import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageRow;
import org.apache.derby.iapi.store.types.DelosStorageScan;
import org.apache.derby.iapi.store.types.StoreDataValue;

/** Read-only scan over the page-backed committed MVCC image. */
final class MvccPageBackedCommittedScan implements DelosStorageScan {
    private final Iterator<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows;
    private DelosStorageRow current;

    MvccPageBackedCommittedScan(List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        this.rows = rows.iterator();
    }

    @Override
    public boolean next() {
        if (!rows.hasNext()) {
            current = null;
            return false;
        }
        PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row = rows.next();
        current = new DelosStorageRow(row.rowId(), row.values());
        return true;
    }

    @Override
    public DelosStorageRow row() {
        if (current == null) {
            throw new IllegalStateException("MVCC page-backed committed scan is not positioned on a row");
        }
        return current;
    }

    @Override
    public void close() {
        current = null;
    }
}
