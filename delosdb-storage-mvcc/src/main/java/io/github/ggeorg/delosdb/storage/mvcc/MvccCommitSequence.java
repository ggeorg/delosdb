package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Monotonic commit sequence number. MVCC snapshots compare committed
 * transaction sequence numbers against their own high-water mark.
 */
public record MvccCommitSequence(long value) implements Comparable<MvccCommitSequence> {
    public static final MvccCommitSequence NONE = new MvccCommitSequence(0L);

    public MvccCommitSequence {
        if (value < 0L) {
            throw new IllegalArgumentException("commit sequence must be non-negative: " + value);
        }
    }

    @Override
    public int compareTo(MvccCommitSequence other) {
        return Long.compare(value, other.value);
    }

    public boolean isAfter(MvccCommitSequence other) {
        return compareTo(other) > 0;
    }

    public boolean isAtOrBefore(MvccCommitSequence other) {
        return compareTo(other) <= 0;
    }

    @Override
    public String toString() {
        return value == 0L ? "csn:none" : "csn:" + value;
    }
}
