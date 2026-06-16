package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;

/** Raised when the in-memory MVCC kernel detects an unsafe writer conflict. */
public final class MvccWriteConflictException extends VersionedWriteConflictException {
    public MvccWriteConflictException(String message) {
        super(message);
    }
}
