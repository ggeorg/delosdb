package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>Ordinary unpinned reads use second-touch admission once the cache is full.
 * The first cold miss is served directly and remembered in a bounded ghost
 * history; a repeated miss is admitted. This prevents one-pass sequential
 * scans from displacing the resident working set while still allowing a newly
 * hot page to enter on its second access. Explicit pins and page mutations are
 * always admitted because their lifecycle must remain cache-owned.</p>
 */
final class MvccPageCache {
    private static final int DEFAULT_MAX_PAGES = 128;
    private static final int GHOST_HISTORY_MULTIPLIER = 4;

    private final int maxPages;
    private final LinkedHashMap<Long, CachedPage> pages;
    private final LinkedHashMap<Long, Boolean> bypassedReadPages;
    private final int ghostHistoryLimit;
    private final MvccBufferReplacementStrategy replacementPolicy;
    private long hits;
    private long misses;
    private long writes;
    private long evictions;
    private long invalidations;
    private long pins;
    private long unpins;
    private long flushes;
    private long groupedForceBatches;
    private long groupedForcedPages;
    private long walBeforeFlushChecks;
    private long walBeforeFlushFailures;
    private long pinnedEvictionSkips;
    private long replacementScans;
    private long replacementDirtyProtectionSkips;
    private long replacementNoVictimCount;
    private long readAdmissionBypasses;
    private long secondTouchAdmissions;
    private long nextGeneration = 1L;
    private long lastPageGeneration;
    private boolean noVictimKnown;

    MvccPageCache() {
        this(DEFAULT_MAX_PAGES);
    }

    MvccPageCache(int maxPages) {
        this(maxPages, new MvccBufferReplacementPolicy());
    }

    MvccPageCache(int maxPages, MvccBufferReplacementStrategy replacementPolicy) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
        }
        this.maxPages = maxPages;
        this.pages = new LinkedHashMap<>(16, 0.75f, true);
        this.bypassedReadPages = new LinkedHashMap<>();
        this.ghostHistoryLimit = Math.multiplyExact(maxPages, GHOST_HISTORY_MULTIPLIER);
        this.replacementPolicy = Objects.requireNonNull(replacementPolicy, "replacementPolicy");
    }

    DelosPage read(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        try (PinnedPage pinned = readPinned(volume, pageId, true)) {
            return pinned.page();
        }
    }

    synchronized PinnedPage readPinned(DelosPageVolume volume, DelosPageId pageId) throws IOException {
        return readPinned(volume, pageId, false);
    }

    private synchronized PinnedPage readPinned(
            DelosPageVolume volume,
            DelosPageId pageId,
            boolean allowAdmissionBypass) throws IOException {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(pageId, "pageId");
        long pageNumber = pageId.value();
        CachedPage cached = pages.get(pageNumber);
        if (cached != null) {
            bypassedReadPages.remove(pageNumber);
            hits++;
            cached.pinCount++;
            pins++;
            return new PinnedPage(pageNumber, DelosPageIo.decode(cached.bytes, pageId), true);
        }
        misses++;
        DelosPage page = volume.readPage(pageId);
        if (allowAdmissionBypass && shouldBypassReadAdmissionUnlocked(pageNumber)) {
            pins++;
            return new PinnedPage(pageNumber, page, false);
        }
        bypassedReadPages.remove(pageNumber);
        cached = putUnlocked(page, false, true);
        pins++;
        return new PinnedPage(pageNumber, page, true);
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

    synchronized long flushAll(
            DelosPageVolume volume,
            MvccBufferFlushCoordinator flushCoordinator) throws IOException {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(flushCoordinator, "flushCoordinator");
        MvccBufferFlushCoordinator.Snapshot before = flushCoordinator.snapshot();
        List<FlushCandidate> candidates = new ArrayList<>();
        try {
            for (Map.Entry<Long, CachedPage> entry : pages.entrySet()) {
                CachedPage cached = entry.getValue();
                if (!cached.dirty) {
                    continue;
                }
                DelosPage page = DelosPageIo.decode(cached.bytes, new DelosPageId(entry.getKey()));
                flushCoordinator.beforePageFlush(page);
                candidates.add(new FlushCandidate(cached, page));
            }

            for (FlushCandidate candidate : candidates) {
                volume.writePage(candidate.page());
            }
            flushCoordinator.forcePageVolumeAfterBatch(volume, candidates.size());

            // A page remains dirty until the complete WAL-checked write batch
            // and its grouped force boundary succeed. Partial writes are safe
            // to repeat; prematurely clearing dirty state is not.
            for (FlushCandidate candidate : candidates) {
                candidate.cachedPage().dirty = false;
            }
            if (!candidates.isEmpty()) {
                noVictimKnown = false;
                flushes += candidates.size();
            }
            trimToMaxPagesUnlocked();
            return candidates.size();
        } finally {
            MvccBufferFlushCoordinator.Snapshot after = flushCoordinator.snapshot();
            groupedForceBatches = after.pageFlushBatches();
            groupedForcedPages = after.pageFlushPages();
            walBeforeFlushChecks += Math.max(
                    0L, after.walBeforeFlushChecks() - before.walBeforeFlushChecks());
            walBeforeFlushFailures += Math.max(
                    0L, after.walBeforeFlushFailures() - before.walBeforeFlushFailures());
        }
    }

    synchronized void invalidate(DelosPageId pageId) {
        Objects.requireNonNull(pageId, "pageId");
        bypassedReadPages.remove(pageId.value());
        if (pages.remove(pageId.value()) != null) {
            invalidations++;
            noVictimKnown = false;
        }
    }

    synchronized void clear() {
        invalidations += pages.size();
        pages.clear();
        bypassedReadPages.clear();
        noVictimKnown = false;
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
                groupedForceBatches,
                groupedForcedPages,
                walBeforeFlushChecks,
                walBeforeFlushFailures,
                pinnedEvictionSkips,
                replacementScans,
                replacementDirtyProtectionSkips,
                replacementNoVictimCount,
                readAdmissionBypasses,
                secondTouchAdmissions,
                bypassedReadPages.size(),
                lastPageGeneration,
                replacementPolicy.name());
    }

    private CachedPage putUnlocked(DelosPage page, boolean dirty, boolean pinned) {
        long pageNumber = page.pageId().value();
        bypassedReadPages.remove(pageNumber);
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
        if (!cached.dirty && cached.pinCount == 0) {
            noVictimKnown = false;
        }
        lastPageGeneration = cached.generation;
        writes++;
        trimToMaxPagesUnlocked();
        return cached;
    }

    private boolean shouldBypassReadAdmissionUnlocked(long pageNumber) {
        if (pages.size() < maxPages) {
            return false;
        }
        if (pages.size() == maxPages && bypassedReadPages.remove(pageNumber) != null) {
            secondTouchAdmissions++;
            return false;
        }
        recordBypassedReadUnlocked(pageNumber);
        readAdmissionBypasses++;
        return true;
    }

    private void recordBypassedReadUnlocked(long pageNumber) {
        bypassedReadPages.remove(pageNumber);
        bypassedReadPages.put(pageNumber, Boolean.TRUE);
        while (bypassedReadPages.size() > ghostHistoryLimit) {
            Long eldest = bypassedReadPages.keySet().iterator().next();
            bypassedReadPages.remove(eldest);
        }
    }

    private void trimToMaxPagesUnlocked() {
        while (pages.size() > maxPages) {
            // Dirty and pinned pages cannot become evictable until an external
            // lifecycle event (flush, unpin, invalidate, or clear) occurs. Once
            // a full policy scan proves that every page is protected, avoid
            // repeating the same O(n) scan for each additional dirty page.
            if (noVictimKnown) {
                replacementNoVictimCount++;
                return;
            }
            MvccBufferReplacementPolicy.Decision decision = replacementPolicy.chooseVictim(pages);
            replacementScans += decision.scannedPages();
            pinnedEvictionSkips += decision.pinnedProtectedPages();
            replacementDirtyProtectionSkips += decision.dirtyProtectedPages();
            if (!decision.hasVictim()) {
                replacementNoVictimCount++;
                noVictimKnown = true;
                return;
            }
            if (pages.remove(decision.victimPageNumber()) != null) {
                evictions++;
                noVictimKnown = false;
            }
        }
    }

    private synchronized void unpin(long pageNumber, boolean cachedAtAcquire) {
        unpins++;
        if (!cachedAtAcquire) {
            return;
        }
        CachedPage cached = pages.get(pageNumber);
        if (cached == null) {
            return;
        }
        if (cached.pinCount <= 0) {
            throw new IllegalStateException("MVCC page cache unpin without matching pin: page " + pageNumber);
        }
        cached.pinCount--;
        if (cached.pinCount == 0) {
            noVictimKnown = false;
        }
        trimToMaxPagesUnlocked();
    }

    final class PinnedPage implements AutoCloseable {
        private final long pageNumber;
        private final DelosPage page;
        private final boolean cachedAtAcquire;
        private boolean closed;

        private PinnedPage(long pageNumber, DelosPage page, boolean cachedAtAcquire) {
            this.pageNumber = pageNumber;
            this.page = Objects.requireNonNull(page, "page");
            this.cachedAtAcquire = cachedAtAcquire;
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
                MvccPageCache.this.unpin(pageNumber, cachedAtAcquire);
            }
        }
    }

    private record FlushCandidate(CachedPage cachedPage, DelosPage page) {
        private FlushCandidate {
            Objects.requireNonNull(cachedPage, "cachedPage");
            Objects.requireNonNull(page, "page");
        }
    }

    private static final class CachedPage implements MvccBufferReplacementPolicy.PageState {
        private byte[] bytes;
        private boolean dirty;
        private int pinCount;
        private long generation;

        private CachedPage(byte[] bytes, boolean dirty, long generation) {
            this.bytes = Objects.requireNonNull(bytes, "bytes");
            this.dirty = dirty;
            this.generation = generation;
        }

        @Override
        public boolean dirty() {
            return dirty;
        }

        @Override
        public int pinCount() {
            return pinCount;
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
            long groupedForceBatches,
            long groupedForcedPages,
            long walBeforeFlushChecks,
            long walBeforeFlushFailures,
            long pinnedEvictionSkips,
            long replacementScans,
            long replacementDirtyProtectionSkips,
            long replacementNoVictimCount,
            long readAdmissionBypasses,
            long secondTouchAdmissions,
            long ghostHistoryPages,
            long lastPageGeneration,
            String replacementPolicyName) {
    }
}
