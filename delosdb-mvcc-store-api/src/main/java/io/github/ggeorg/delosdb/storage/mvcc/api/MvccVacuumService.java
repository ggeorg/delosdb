package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Vacuum boundary owned by the native MVCC implementation. */
public interface MvccVacuumService {
    void vacuum();
}
