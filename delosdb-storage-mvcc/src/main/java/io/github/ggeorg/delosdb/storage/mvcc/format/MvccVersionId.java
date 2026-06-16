package io.github.ggeorg.delosdb.storage.mvcc.format;

/**
 * Stable identifier for a physical MVCC row-version record.
 *
 * <p>A row has one logical {@link MvccRowId} and may have many version ids as
 * updates append new physical versions. {@link #NONE} is used for the absence
 * of a previous version.</p>
 */
public record MvccVersionId(long value) implements Comparable<MvccVersionId> {
    public static final MvccVersionId NONE = new MvccVersionId(0L);

    public MvccVersionId {
        if (value < 0L) {
            throw new IllegalArgumentException("version id must be non-negative: " + value);
        }
    }

    public boolean isNone() {
        return value == 0L;
    }

    @Override
    public int compareTo(MvccVersionId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return isNone() ? "version:none" : "version:" + value;
    }
}
