package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Monotonic transaction identifier used by the MVCC engine.
 * MVCC kernel. Transaction id {@code 0} is reserved for the absence of a
 * deleting transaction in storage-facing records; real transactions start at 1.
 */
public record MvccTransactionId(long value) implements Comparable<MvccTransactionId> {
    public static final MvccTransactionId NONE = new MvccTransactionId(0L);

    public MvccTransactionId {
        if (value < 0L) {
            throw new IllegalArgumentException("transaction id must be non-negative: " + value);
        }
    }

    public boolean isNone() {
        return value == 0L;
    }

    @Override
    public int compareTo(MvccTransactionId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return isNone() ? "tx:none" : "tx:" + value;
    }
}
