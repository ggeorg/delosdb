package io.github.ggeorg.delosdb.storage.mvcc.api;

import java.util.Objects;

/** Visible row returned by an MVCC scan or lookup. */
public record MvccVisibleRow<T>(MvccRowLocationHint locationHint, T payload) {
    public MvccVisibleRow {
        locationHint = Objects.requireNonNull(locationHint, "locationHint");
        payload = Objects.requireNonNull(payload, "payload");
    }
}
