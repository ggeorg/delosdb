package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Per-transaction command boundary used for statement-level own-write visibility.
 *
 * <p>The MVCC kernel already models transaction snapshots. This value type adds
 * the command/statement dimension needed to distinguish a row version written by
 * an earlier command in the same transaction from a row version written by the
 * current or a later command. It is deliberately small: SQL statement wiring is
 * a later proof, while this A46 proof keeps the command model at the kernel
 * level.</p>
 */
public record MvccCommandSequence(long value) implements Comparable<MvccCommandSequence> {
    /** Default command used by legacy kernel calls before statement wiring. */
    public static final MvccCommandSequence FIRST = new MvccCommandSequence(0L);

    /** Compatibility boundary that preserves the older transaction-level own-write behavior. */
    public static final MvccCommandSequence LATEST_VISIBLE = new MvccCommandSequence(Long.MAX_VALUE);

    public MvccCommandSequence {
        if (value < 0L) {
            throw new IllegalArgumentException("command sequence must be non-negative: " + value);
        }
    }

    public static MvccCommandSequence of(long value) {
        return new MvccCommandSequence(value);
    }

    public boolean isBefore(MvccCommandSequence other) {
        return compareTo(other) < 0;
    }

    public boolean isAtOrBefore(MvccCommandSequence other) {
        return compareTo(other) <= 0;
    }

    @Override
    public int compareTo(MvccCommandSequence other) {
        if (other == null) {
            throw new IllegalArgumentException("other command sequence must not be null");
        }
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value == Long.MAX_VALUE ? "latest" : Long.toString(value);
    }
}
