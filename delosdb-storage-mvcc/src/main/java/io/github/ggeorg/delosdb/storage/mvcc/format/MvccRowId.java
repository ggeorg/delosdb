package io.github.ggeorg.delosdb.storage.mvcc.format;

/**
 * Stable logical row identifier for the durable MVCC storage prototype.
 *
 * <p>The identifier is deliberately opaque in Phase A2. Later page-backed
 * tables may allocate it from a row directory or embed page/slot location, but
 * record codecs must not depend on JVM object identity.</p>
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
