package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;

/** SPI scan adapter over the MVCC kernel scan. */
public final class DelosMvccScan<K, V> implements VersionedScan<K, V> {
    private final MvccScan<K, V> delegate;

    DelosMvccScan(MvccScan<K, V> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean next() {
        return delegate.next();
    }

    @Override
    public VersionedRow<K, V> row() {
        MvccRow<K, V> row = delegate.row();
        return new VersionedRow<>(row.key(), row.value());
    }

    @Override
    public int visibleRowCount() {
        return delegate.visibleRowCount();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
