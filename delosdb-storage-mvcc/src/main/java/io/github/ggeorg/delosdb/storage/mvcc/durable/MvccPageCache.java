package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageIo;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;

/**
 * MVCC-owned decoded-page cache boundary with scoped pins and dirty-page
 * write-through accounting.
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
    private final LinkedHashMap<Long, CachedPage> pages;
    private long hits;
    private long misses;
    private long writes;
    private long evictions;
    private long invalidations;
    private long pins;
    private long unpins;
    private long flushes;
    private long pinnedEvictionSkips;
    private long nextGeneration = 1L;
    private long lastPageGeneration;

    MvccPageCache() {
        this(DEFAULT_MAX_PAGES);
    }

    MvccPageCache(int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }
        this.maxPages = maxPages;
        this.pages = new LinkedHashMap<>(16, 0.75f, true);
    }

    DelosPage read(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        try (PinnedPage pinned = readPinned(volume, pageId)) {
            return pinned.page();
        }
    }

    synchronized PinnedPage readPinned(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(pageId, "pageId");
        CachedPage cached = pages.get(pageId.value());
        if (cached != null) {
            hits++;
            cached.pinCount++;
            pins++;
            return new PinnedPage(pageId.value(), DelosPageIo.decode(cached.bytes, pageId));
        }
        misses++;
        DelosPage page = volume.readPage(pageId);
        cached = putUnlocked(page, false, true);
        pins++;
        return new PinnedPage(pageId.value(), page);
    }

    synchronized void put(DelosPage page) {
        putClean(page);
    }

    synchronized void putClean(DelosPage page) {
        putUnlocked(Objects.requireNonNull(page, "page"), false, false);
    }

    synchronized void putDirty(DelosPage page) {
        putUnlocked(Objects.requireNonNull(page, "page"), true, false);
    }

    synchronized void flush(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(pageId, "pageId");
        CachedPage cached = pages.get(pageId.value());
        if (cached == null || !cached.dirty) {
            return;
        }
        volume.writePage(DelosPageIo.decode(cached.bytes, pageId));
        cached.dirty = false;
        flushes++;
        trimToMaxPagesUnlocked();
    }

    synchronized void flushAll(DelosPageVolume volume) throws IOException {
        Objects.requireNonNull(volume, "volume");
        for (Map.Entry<Long, CachedPage> entry : pages.entrySet()) {
            CachedPage cached = entry.getValue();
            if (!cached.dirty) {
                continue;
            }
            DelosPageId pageId = new DelosPageId(entry.getKey());
            volume.writePage(DelosPageIo.decode(cached.bytes, pageId));
            cached.dirty = false;
            flushes++;
        }
        trimToMaxPagesUnlocked();
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
        long pinnedPages = 0L;
        long dirtyPages = 0L;
        for (CachedPage page : pages.values()) {
            if (page.pinCount > 0) {
                pinnedPages++;
            }
            if (page.dirty) {
                dirtyPages++;
            }
        }
        return new Snapshot(
                maxPages,
                pages.size(),
                hits,
                misses,
                writes,
                evictions,
                invalidations,
                pins,
                unpins,
                pinnedPages,
                dirtyPages,
                dirtyPages,
                flushes,
                pinnedEvictionSkips,
                lastPageGeneration);
    }

    private CachedPage putUnlocked(DelosPage page, boolean dirty, boolean pinned) {
        long pageNumber = page.pageId().value();
        CachedPage cached = pages.get(pageNumber);
        if (cached == null) {
            cached = new CachedPage(page.toBytes(), dirty, nextGeneration++);
            pages.put(pageNumber, cached);
        } else {
            cached.bytes = page.toBytes();
            cached.dirty = cached.dirty || dirty;
            cached.generation = nextGeneration++;
        }
        if (pinned) {
            cached.pinCount++;
        }
        lastPageGeneration = cached.generation;
        writes++;
        trimToMaxPagesUnlocked();
        return cached;
    }

    private void trimToMaxPagesUnlocked() {
        while (pages.size() > maxPages) {
            boolean removed = false;
            Iterator<Map.Entry<Long, CachedPage>> iterator = pages.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, CachedPage> entry = iterator.next();
                CachedPage cached = entry.getValue();
                if (cached.pinCount > 0 || cached.dirty) {
                    pinnedEvictionSkips++;
                    continue;
                }
                iterator.remove();
                evictions++;
                removed = true;
                break;
            }
            if (!removed) {
                return;
            }
        }
    }

    private synchronized void unpin(long pageNumber) {
        CachedPage cached = pages.get(pageNumber);
        if (cached == null) {
            return;
        }
        if (cached.pinCount <= 0) {
            throw new IllegalStateException("MVCC page cache unpin without matching pin: page " + pageNumber);
        }
        cached.pinCount--;
        unpins++;
        trimToMaxPagesUnlocked();
    }

    final class PinnedPage implements AutoCloseable {
        private final long pageNumber;
        private final DelosPage page;
        private boolean closed;

        private PinnedPage(long pageNumber, DelosPage page) {
            this.pageNumber = pageNumber;
            this.page = Objects.requireNonNull(page, "page");
        }

        DelosPage page() {
            if (closed) {
                throw new IllegalStateException("MVCC pinned page handle is closed: page " + pageNumber);
            }
            return page;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                MvccPageCache.this.unpin(pageNumber);
            }
        }
    }

    private static final class CachedPage {
        private byte[] bytes;
        private boolean dirty;
        private int pinCount;
        private long generation;

        private CachedPage(byte[] bytes, boolean dirty, long generation) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.dirty = dirty;
            this.generation = generation;
        }
    }

    record Snapshot(
            long maxPages,
            long size,
            long hits,
            long misses,
            long writes,
            long evictions,
            long invalidations,
            long pins,
            long unpins,
            long pinnedPages,
            long dirtyPages,
            long flushListPages,
            long flushes,
            long pinnedEvictionSkips,
            long lastPageGeneration) {
    }
}
