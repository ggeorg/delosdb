package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Objects;

/** Result of an MVCC insert, update, or delete operation. */
public record MvccWriteResult(MvccRowLocationHint locationHint) {
    public MvccWriteResult {
        locationHint = Objects.requireNonNull(locationHint, "locationHint");
    }
}
