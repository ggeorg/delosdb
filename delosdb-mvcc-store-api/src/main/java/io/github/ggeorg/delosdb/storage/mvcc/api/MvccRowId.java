package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Stable logical row identity exposed by MVCC storage. */
public record MvccRowId(long value) {
    public MvccRowId {
        if (value <= 0) {
            throw new IllegalArgumentException("row id must be positive: " + value);
        }
    }
}
