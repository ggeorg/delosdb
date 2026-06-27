package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Monotonic MVCC commit sequence value. */
public record MvccCommitSequence(long value) {
    public MvccCommitSequence {
        if (value < 0) {
            throw new IllegalArgumentException("commit sequence must be non-negative: " + value);
        }
    }
}
