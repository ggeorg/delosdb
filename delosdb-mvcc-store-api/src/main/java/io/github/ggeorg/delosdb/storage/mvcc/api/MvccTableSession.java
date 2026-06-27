package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Optional;

/** Transaction-scoped access to one MVCC table. */
public interface MvccTableSession<T> extends AutoCloseable {
    MvccScan<T> openScan();

    Optional<MvccVisibleRow<T>> read(MvccRowLocationHint locationHint);

    MvccWriteResult insert(T payload);

    MvccWriteResult update(MvccRowLocationHint locationHint, T payload);

    MvccWriteResult delete(MvccRowLocationHint locationHint);

    @Override
    void close();
}
