package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** One linked chunk of an MVCC overflow payload. */
public final class MvccOverflowPayloadChunk {
    private final int chunkIndex;
    private final int chunkCount;
    private final long totalLength;
    private final byte[] payload;
    private final Optional<MvccVersionLocator> nextChunkLocator;

    public MvccOverflowPayloadChunk(
            int chunkIndex,
            int chunkCount,
            long totalLength,
            byte[] payload,
            Optional<MvccVersionLocator> nextChunkLocator) {
        if (chunkCount <= 0) {
            throw new IllegalArgumentException("chunkCount must be positive: " + chunkCount);
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException(
                    "chunkIndex out of range: " + chunkIndex + ", chunkCount=" + chunkCount);
        }
        if (totalLength <= 0L) {
            throw new IllegalArgumentException("totalLength must be positive: " + totalLength);
        }
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        if (this.payload.length == 0) {
            throw new IllegalArgumentException("overflow chunk payload must not be empty");
        }
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.totalLength = totalLength;
        this.nextChunkLocator = Objects.requireNonNull(nextChunkLocator, "nextChunkLocator");
        if (chunkIndex == chunkCount - 1 && this.nextChunkLocator.isPresent()) {
            throw new IllegalArgumentException("last overflow chunk must not point to another chunk");
        }
        if (chunkIndex < chunkCount - 1 && this.nextChunkLocator.isEmpty()) {
            throw new IllegalArgumentException("non-final overflow chunk must point to the next chunk");
        }
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public long totalLength() {
        return totalLength;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public Optional<MvccVersionLocator> nextChunkLocator() {
        return nextChunkLocator;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccOverflowPayloadChunk that)) {
            return false;
        }
        return chunkIndex == that.chunkIndex
                && chunkCount == that.chunkCount
                && totalLength == that.totalLength
                && Arrays.equals(payload, that.payload)
                && nextChunkLocator.equals(that.nextChunkLocator);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(chunkIndex);
        result = 31 * result + Integer.hashCode(chunkCount);
        result = 31 * result + Long.hashCode(totalLength);
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + nextChunkLocator.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "MvccOverflowPayloadChunk[index=" + chunkIndex
                + ", count=" + chunkCount
                + ", totalLength=" + totalLength
                + ", payloadBytes=" + payload.length
                + ", next=" + nextChunkLocator
                + ']';
    }
}
