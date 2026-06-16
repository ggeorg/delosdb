package io.github.ggeorg.delosdb.storage.mvcc.io;

/**
 * Stable identifier for a provider-owned MVCC storage page.
 *
 * <p>The first durable-storage step deliberately keeps the identifier simple:
 * page ids are zero-based offsets in an 8 KiB page file. Later row/version ids
 * can safely embed this value without depending on JVM object identity.</p>
 */
public record MvccPageId(long value) implements Comparable<MvccPageId> {
    public MvccPageId {
        if (value < 0L) {
            throw new IllegalArgumentException("page id must be non-negative: " + value);
        }
    }

    long byteOffset(int pageSize) {
        return Math.multiplyExact(value, pageSize);
    }

    public MvccPageId next() {
        return new MvccPageId(value + 1L);
    }

    @Override
    public int compareTo(MvccPageId other) {
        return Long.compare(value, other.value);
    }
}
