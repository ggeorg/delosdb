package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;

/** JMH adapter benchmark for the MVCC page-cache replacement seam. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 250, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@State(Scope.Thread)
public class DelosMvccBufferCacheBenchmark {
    @Param({"8", "32", "128"})
    public int cachePages;

    @Param({"64", "512"})
    public int workingSetPages;

    private CountingPageVolume volume;
    private MvccPageCache cache;
    private int cursor;

    @Setup
    public void setup() {
        volume = new CountingPageVolume();
        for (int pageId = 0; pageId < workingSetPages; pageId++) {
            volume.writePage(dataPage(pageId, (byte) pageId));
        }
        cache = new MvccPageCache(cachePages);
        cursor = 0;
    }

    @Benchmark
    public DelosPage readSequential() throws Exception {
        DelosPage page = cache.read(volume, new DelosPageId(cursor));
        cursor = (cursor + 1) % workingSetPages;
        return page;
    }

    @Benchmark
    public void pinAndUnpinSequential(Blackhole blackhole) throws Exception {
        try (MvccPageCache.PinnedPage pinned = cache.readPinned(volume, new DelosPageId(cursor))) {
            blackhole.consume(pinned.page());
        }
        cursor = (cursor + 1) % workingSetPages;
    }

    @Benchmark
    public MvccPageCache.Snapshot dirtyWriteAndSnapshot() {
        long pageId = cursor;
        cache.putDirty(dataPage(pageId, (byte) (pageId + 1)));
        cursor = (cursor + 1) % workingSetPages;
        return cache.snapshot();
    }

    private static DelosPage dataPage(long pageId, byte payload) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        page.appendRecord(new byte[] {payload});
        return page;
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();

        @Override
        public synchronized DelosPage readPage(DelosPageId id) {
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing JMH page " + id.value());
            }
            return page;
        }

        @Override
        public synchronized void writePage(DelosPage page) {
            pages.put(page.pageId().value(), page);
        }

        @Override
        public synchronized DelosPage allocatePage(int pageType) {
            DelosPage page = DelosPage.empty(new DelosPageId(pages.size()), pageType);
            pages.put(page.pageId().value(), page);
            return page;
        }

        @Override
        public synchronized long pageCount() {
            return pages.size();
        }

        @Override
        public void force() {
        }

        @Override
        public SyncPolicy syncPolicy() {
            return SyncPolicy.NONE;
        }

        @Override
        public synchronized void close() throws IOException {
            pages.clear();
        }
    }
}
