/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

/**
 * Deterministic MVCC buffer-workload invariant proof.
 *
 * <p>This is deliberately not a wall-clock benchmark. It verifies storage-operation
 * counters and WAL/flush/cache invariants that complement, but are not replaced by,
 * the standalone JDBC JMH lane.</p>
 */
final class MvccBufferWorkloadInvariantTest {
    @Test
    void twoSidedWorkloadRecordsWriteBatchAndReadPathCosts() throws Exception {
        int pageCount = 128;
        MvccPageCache cache = new MvccPageCache(pageCount + 8);
        CountingPageVolume volume = new CountingPageVolume();
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();

        for (int pageId = 0; pageId < pageCount; pageId++) {
            cache.putDirty(dataPage(pageId, pageId + 1L, (byte) pageId));
        }
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(pageCount));

        assertEquals(pageCount, cache.flushAll(volume, coordinator));
        assertEquals(pageCount, volume.writeCount);
        assertEquals(1L, volume.forceCount);
        assertEquals(1L, coordinator.snapshot().groupCommitBatches());
        assertEquals(pageCount, coordinator.snapshot().groupedPageFlushes());

        for (int pass = 0; pass < 2; pass++) {
            for (int pageId = 0; pageId < pageCount; pageId++) {
                DelosPage page = cache.read(volume, new DelosPageId(pageId));
                assertEquals(pageId, page.pageId().value());
            }
        }

        MvccPageCache.Snapshot snapshot = cache.snapshot();
        assertEquals(0L, snapshot.dirtyPages());
        assertTrue(snapshot.hits() >= pageCount * 2L,
                "warm read path should be satisfied from the MVCC page cache");
        assertEquals(0L, snapshot.walBeforeFlushFailures());
        assertEquals(pageCount, snapshot.groupedForcedPages());
    }

    private static DelosPage dataPage(long pageId, long pageLsn, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page.withPageLsn(pageLsn);
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();
        private long writeCount;
        private long forceCount;

        @Override
        public DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing validation page " + id.value());
            }
            return page;
        }

        @Override
        public void writePage(DelosPage page) {
            pages.put(page.pageId().value(), page);
            writeCount++;
        }

        @Override
        public DelosPage allocatePage(int pageType) {
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.put(page.pageId().value(), page);
            return page;
        }

        @Override
        public long pageCount() {
            return pages.size();
        }

        @Override
        public void force() {
            forceCount++;
        }

        @Override
        public SyncPolicy syncPolicy() {
            return SyncPolicy.NONE;
        }

        @Override
        public void close() throws IOException {
            pages.clear();
        }
    }
}
