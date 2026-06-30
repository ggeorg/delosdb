package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;

/**
 * Small MVCC-owned decoded-page cache boundary.
 *
 * <p>This deliberately does not import Derby raw-store cache classes yet. It
 * gives the page-backed MVCC table one explicit cache/invalidating boundary so
 * callers stop scattering direct page-volume reads through allocation,
 * consistency checking, and append paths. The cache stores immutable page images
 * and returns a fresh decoded page for every read, which keeps the current
 * mutable {@link DelosPage} API from leaking dirty in-memory page instances.</p>
 */
final class MvccPageCache {
    private static final int DEFAULT_MAX_PAGES = 128;

    private final int maxPages;
    private final LinkedHashMap<Long, byte[]> pages;
    private long hits;
    private long misses;
    private long writes;
    private long invalidations;

    MvccPageCache() {
        this(DEFAULT_MAX_PAGES);
    }

    MvccPageCache(int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }
        this.maxPages = maxPages;
        this.pages = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                boolean remove = size() > MvccPageCache.this.maxPages;
                if (remove) {
                    invalidations++;
                }
                return remove;
            }
        };
    }

    synchronized DelosPage read(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(pageId, "pageId");
        byte[] cached = pages.get(pageId.value());
        if (cached != null) {
            hits++;
            return DelosPageIo.decode(cached, pageId);
        }
        misses++;
        DelosPage page = volume.readPage(pageId);
        pages.put(pageId.value(), page.toBytes());
        return page;
    }

    synchronized void put(DelosPage page) {
        Objects.requireNonNull(page, "page");
        pages.put(page.pageId().value(), page.toBytes());
        writes++;
    }

    synchronized void invalidate(DelosPageId pageId) {
        Objects.requireNonNull(pageId, "pageId");
        if (pages.remove(pageId.value()) != null) {
            invalidations++;
        }
    }

    synchronized void clear() {
        invalidations += pages.size();
        pages.clear();
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(pages.size(), hits, misses, writes, invalidations);
    }

    record Snapshot(long size, long hits, long misses, long writes, long invalidations) {
    }
}
