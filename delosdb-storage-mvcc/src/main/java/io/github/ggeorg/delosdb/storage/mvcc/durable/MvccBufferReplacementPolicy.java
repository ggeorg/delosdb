package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Map;
import java.util.Objects;

/**
 * Deterministic replacement policy for the MVCC decoded-page cache.
 *
 * <p>The {@link MvccPageCache} stores entries in access order, so the first
 * clean and unpinned page in the map is the least-recently-used evictable
 * page. Dirty and pinned pages are protected here; flushing and unpinning are
 * separate lifecycle operations and must happen before replacement can reclaim
 * those entries.</p>
 */
final class MvccBufferReplacementPolicy implements MvccBufferReplacementStrategy {
    @Override
    public String name() {
        return "ACCESS_ORDER_LRU";
    }

    @Override
    public Decision chooseVictim(Map<Long, ? extends PageState> pages) {
        Objects.requireNonNull(pages, "pages");
        long scannedPages = 0L;
        long pinnedProtectedPages = 0L;
        long dirtyProtectedPages = 0L;
        for (Map.Entry<Long, ? extends PageState> entry : pages.entrySet()) {
            scannedPages++;
            PageState page = entry.getValue();
            if (page.pinCount() > 0) {
                pinnedProtectedPages++;
                continue;
            }
            if (page.dirty()) {
                dirtyProtectedPages++;
                continue;
            }
            return Decision.victim(
                    entry.getKey(),
                    scannedPages,
                    pinnedProtectedPages,
                    dirtyProtectedPages);
        }
        return Decision.noVictim(scannedPages, pinnedProtectedPages, dirtyProtectedPages);
    }

    interface PageState {
        boolean dirty();
        int pinCount();
    }

    record Decision(
            Long victimPageNumber,
            long scannedPages,
            long pinnedProtectedPages,
            long dirtyProtectedPages) {
        static Decision victim(
                long victimPageNumber,
                long scannedPages,
                long pinnedProtectedPages,
                long dirtyProtectedPages) {
            return new Decision(victimPageNumber, scannedPages, pinnedProtectedPages, dirtyProtectedPages);
        }

        static Decision noVictim(
                long scannedPages,
                long pinnedProtectedPages,
                long dirtyProtectedPages) {
            return new Decision(null, scannedPages, pinnedProtectedPages, dirtyProtectedPages);
        }

        boolean hasVictim() {
            return victimPageNumber != null;
        }
    }
}
