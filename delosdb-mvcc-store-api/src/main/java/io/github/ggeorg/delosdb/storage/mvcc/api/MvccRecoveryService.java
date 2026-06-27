package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Recovery boundary owned by the native MVCC implementation. */
public interface MvccRecoveryService {
    void recover();
}
