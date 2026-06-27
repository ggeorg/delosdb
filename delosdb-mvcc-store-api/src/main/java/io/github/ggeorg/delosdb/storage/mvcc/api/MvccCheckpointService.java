package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Checkpoint boundary owned by the native MVCC implementation. */
public interface MvccCheckpointService {
    void checkpoint();
}
