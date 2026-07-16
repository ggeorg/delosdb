package io.github.ggeorg.delosdb.storage.mvcc.format;

/**
 * Stable logical row identifier for durable MVCC storage.
 *
 * <p>The identifier is deliberately opaque. Page-backed tables may associate
 * it with row-directory or page/slot metadata, but record codecs must not
 * depend on JVM object identity.</p>
 */
public record MvccRowId(long value) implements Comparable<MvccRowId> {
    public static final MvccRowId NONE = new MvccRowId(0L);

    public MvccRowId {
        if (value < 0L) {
            throw new IllegalArgumentException("row id must be non-negative: " + value);
        }
    }

    public boolean isNone() {
        return value == 0L;
    }

    @Override
    public int compareTo(MvccRowId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return isNone() ? "row:none" : "row:" + value;
    }
}
