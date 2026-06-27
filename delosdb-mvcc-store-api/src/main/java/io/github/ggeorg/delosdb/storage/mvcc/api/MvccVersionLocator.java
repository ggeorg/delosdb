package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Optional physical version locator. It is a hint, not stable row identity. */
public record MvccVersionLocator(long pageId, int slotId) {
    public MvccVersionLocator {
        if (pageId < 0) {
            throw new IllegalArgumentException("page id must be non-negative: " + pageId);
        }
        if (slotId < 0) {
            throw new IllegalArgumentException("slot id must be non-negative: " + slotId);
        }
    }
}
