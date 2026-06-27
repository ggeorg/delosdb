package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Iterator;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosScan;

/** Materialized storage-api scan cursor for the MVCC provider facade. */
public final class MvccStorageScan implements DelosScan {
    private final Iterator<DelosRow> rows;
    private DelosRow current;

    MvccStorageScan(List<DelosRow> rows) {
        this.rows = List.copyOf(rows).iterator();
    }

    @Override
    public boolean next() {
        if (!rows.hasNext()) {
            current = null;
            return false;
        }
        current = rows.next();
        return true;
    }

    @Override
    public DelosRow row() {
        if (current == null) {
            throw new IllegalStateException("MVCC storage scan is not positioned on a row");
        }
        return current;
    }

    @Override
    public void close() {
        current = null;
    }
}
