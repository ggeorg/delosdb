package io.github.ggeorg.delosdb.storage.io.page;

/**
 * Stable identifier for a fixed-size DelosDB storage page.
 *
 * <p>Page ids are zero-based offsets in a page volume. The identifier carries
 * no transaction, visibility, row, heap, or provider semantics.</p>
 */
public record DelosPageId(long value) implements Comparable<DelosPageId> {
    public DelosPageId {
        if (value < 0L) {
            throw new IllegalArgumentException("page id must be non-negative: " + value);
        }
    }

    public long byteOffset(int pageSize) {
        return Math.multiplyExact(value, pageSize);
    }

    public DelosPageId next() {
        return new DelosPageId(value + 1L);
    }

    @Override
    public int compareTo(DelosPageId other) {
        return Long.compare(value, other.value);
    }
}
