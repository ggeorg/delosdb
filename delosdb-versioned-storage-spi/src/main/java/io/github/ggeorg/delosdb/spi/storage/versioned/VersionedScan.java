package io.github.ggeorg.delosdb.spi.storage.versioned;

/** Cursor over rows visible to a {@link TxView}. */
public interface VersionedScan<K, V> extends AutoCloseable {
    boolean next();

    VersionedRow<K, V> row();

    int visibleRowCount();

    @Override
    void close();
}
