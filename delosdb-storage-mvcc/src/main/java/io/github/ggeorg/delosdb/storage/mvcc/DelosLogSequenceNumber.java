package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Minimal provider-local log sequence number used by the MODULE5J WAL/pageLSN
 * skeleton. This is not a Derby log instant and does not claim ARIES semantics.
 */
public record DelosLogSequenceNumber(long value) implements Comparable<DelosLogSequenceNumber> {
    public static final DelosLogSequenceNumber NONE = new DelosLogSequenceNumber(0L);

    public DelosLogSequenceNumber {
        if (value < 0L) {
            throw new IllegalArgumentException("log sequence number must be non-negative: " + value);
        }
    }

    public boolean isNone() {
        return value == 0L;
    }

    @Override
    public int compareTo(DelosLogSequenceNumber other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return isNone() ? "LSN:none" : "LSN:" + value;
    }
}
