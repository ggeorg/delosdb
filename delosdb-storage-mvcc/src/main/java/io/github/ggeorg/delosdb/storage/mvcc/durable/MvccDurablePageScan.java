package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

/**
 * Shared scanner for MVCC durable page images.
 *
 * <p>The page-backed MVCC store needs the same physical page walk for reopen
 * reconciliation, consistency checking, diagnostics, and slot accounting. Keep
 * that walk in one place so future page-layout changes only have one durable
 * scan boundary to update.</p>
 */
final class MvccDurablePageScan {
    private final long pageCount;
    private final List<SlotRecord> slotRecords;
    private final NavigableSet<Long> emptyPageIds;
    private final NavigableMap<Long, Integer> freeBytesByPageId;

    private MvccDurablePageScan(
            long pageCount,
            List<SlotRecord> slotRecords,
            NavigableSet<Long> emptyPageIds,
            NavigableMap<Long, Integer> freeBytesByPageId) {
        if (pageCount < 0L) {
            throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
        }
        this.pageCount = pageCount;
        this.slotRecords = List.copyOf(Objects.requireNonNull(slotRecords, "slotRecords"));
        this.emptyPageIds = new TreeSet<>(Objects.requireNonNull(emptyPageIds, "emptyPageIds"));
        this.freeBytesByPageId = new TreeMap<>(Objects.requireNonNull(freeBytesByPageId, "freeBytesByPageId"));
    }

    static MvccDurablePageScan scan(PageSource pages) throws IOException {
        Objects.requireNonNull(pages, "pages");
        long pageCount = pages.pageCount();
        if (pageCount < 0L) {
            throw new IllegalStateException("MVCC page source returned negative page count: " + pageCount);
        }

        List<SlotRecord> slotRecords = new ArrayList<>();
        NavigableSet<Long> emptyPageIds = new TreeSet<>();
        NavigableMap<Long, Integer> freeBytesByPageId = new TreeMap<>();
        for (long pageNumber = 0L; pageNumber < pageCount; pageNumber++) {
            DelosPageId pageId = new DelosPageId(pageNumber);
            DelosPage page = Objects.requireNonNull(pages.readPage(pageId), "page");
            freeBytesByPageId.put(pageNumber, page.freeBytes());
            if (page.slotCount() == 0) {
                emptyPageIds.add(pageNumber);
                continue;
            }
            for (int slot = 0; slot < page.slotCount(); slot++) {
                slotRecords.add(new SlotRecord(page.pageId(), slot, page.readRecord(slot)));
            }
        }
        return new MvccDurablePageScan(pageCount, slotRecords, emptyPageIds, freeBytesByPageId);
    }

    long pageCount() {
        return pageCount;
    }

    List<SlotRecord> slotRecords() {
        return slotRecords;
    }

    NavigableSet<Long> emptyPageIds() {
        return new TreeSet<>(emptyPageIds);
    }

    NavigableMap<Long, Integer> freeBytesByPageId() {
        return new TreeMap<>(freeBytesByPageId);
    }

    interface PageSource {
        long pageCount() throws IOException;

        DelosPage readPage(DelosPageId pageId) throws IOException;
    }

    record SlotRecord(DelosPageId pageId, int slotId, byte[] payload) {
        SlotRecord {
            pageId = Objects.requireNonNull(pageId, "pageId");
            payload = Objects.requireNonNull(payload, "payload");
            if (slotId < 0) {
                throw new IllegalArgumentException("slotId must not be negative: " + slotId);
            }
            payload = payload.clone();
        }

        MvccVersionLocator locator() {
            return new MvccVersionLocator(pageId, slotId);
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
