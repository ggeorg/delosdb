package io.github.ggeorg.delosdb.storage.mvcc;

/** Raised when the in-memory MVCC kernel detects an unsafe writer conflict. */
public final class MvccWriteConflictException extends RuntimeException {
    public MvccWriteConflictException(String message) {
        super(message);
    }
}
