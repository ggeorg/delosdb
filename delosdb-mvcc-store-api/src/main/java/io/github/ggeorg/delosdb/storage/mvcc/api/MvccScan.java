package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Optional;

/** Adapter-neutral scan over visible MVCC rows. */
public interface MvccScan<T> extends AutoCloseable {
    Optional<MvccVisibleRow<T>> next();

    @Override
    void close();
}
