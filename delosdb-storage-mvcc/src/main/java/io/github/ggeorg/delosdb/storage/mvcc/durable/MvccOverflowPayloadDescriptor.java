package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Objects;
import java.util.Optional;

/**
 * Root descriptor for an MVCC overflow payload chain.
 *
 * <p>The descriptor is the small value that a future version record can store
 * inline when the row payload itself no longer fits in one page record. The
 * bytes live in linked overflow chunk records stored on overflow pages.</p>
 */
public record MvccOverflowPayloadDescriptor(
        long totalLength,
        int chunkCount,
        Optional<MvccVersionLocator> firstChunkLocator) {
    public MvccOverflowPayloadDescriptor {
        if (totalLength < 0L) {
            throw new IllegalArgumentException("totalLength must be non-negative: " + totalLength);
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount must be non-negative: " + chunkCount);
        }
        firstChunkLocator = Objects.requireNonNull(firstChunkLocator, "firstChunkLocator");
        if (chunkCount == 0) {
            if (totalLength != 0L) {
                throw new IllegalArgumentException("empty overflow chain must have totalLength 0: " + totalLength);
            }
            if (firstChunkLocator.isPresent()) {
                throw new IllegalArgumentException("empty overflow chain must not have a first chunk locator");
            }
        } else if (totalLength == 0L) {
            throw new IllegalArgumentException("non-empty overflow chain must have positive totalLength");
        } else if (firstChunkLocator.isEmpty()) {
            throw new IllegalArgumentException("non-empty overflow chain must have a first chunk locator");
        }
    }

    public static MvccOverflowPayloadDescriptor empty() {
        return new MvccOverflowPayloadDescriptor(0L, 0, Optional.empty());
    }
}
