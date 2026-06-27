package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Objects;
import java.util.Optional;

/** Logical row id plus an optional, stale-able physical locator hint. */
public record MvccRowLocationHint(MvccRowId rowId, Optional<MvccVersionLocator> locator) {
    public MvccRowLocationHint {
        rowId = Objects.requireNonNull(rowId, "rowId");
        locator = Objects.requireNonNull(locator, "locator");
    }

    public static MvccRowLocationHint of(MvccRowId rowId) {
        return new MvccRowLocationHint(rowId, Optional.empty());
    }

    public static MvccRowLocationHint of(MvccRowId rowId, MvccVersionLocator locator) {
        return new MvccRowLocationHint(rowId, Optional.of(locator));
    }
}
