package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Objects;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

/** Physical location of one durable MVCC version record inside a page file. */
public record MvccVersionLocator(DelosPageId pageId, int slotId) {
    public MvccVersionLocator {
        pageId = Objects.requireNonNull(pageId, "pageId");
        if (slotId < 0) {
            throw new IllegalArgumentException("slot id must be non-negative: " + slotId);
        }
    }
}
