package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

/**
 * Low-level MVCC buffer/cache benchmark used to select the Phase 5.5
 * implementation change from measured behavior rather than policy theory.
 */
final class MvccBufferCacheBenchmark {
    private static final long RANDOM_SEED = 0x5EEDB00FL;
    private static final int RECORDS_PER_PAGE = 12;
    private static final int PAYLOAD_BYTES = 48;

    private MvccBufferCacheBenchmark() {
    }

    static List<MvccBufferCacheMeasurement> run(Options options) throws Exception {
        Objects.requireNonNull(options, "options").validate();
        Files.createDirectories(options.reportDirectory());

        List<MvccBufferCacheMeasurement> measurements = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            List<MvccBufferCacheMeasurement.Workload> workloads = new ArrayList<>(options.workloads());
            if ((run & 1) == 0) {
                Collections.reverse(workloads);
            }
            for (MvccBufferCacheMeasurement.Workload workload : workloads) {
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    execute(workload, options, run, false);
                }
                measurements.add(execute(workload, options, run, true));
            }
        }
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static MvccBufferCacheMeasurement execute(
            MvccBufferCacheMeasurement.Workload workload,
            Options options,
            int run,
            boolean measured) throws Exception {
        return switch (workload) {
            case COLD_MISS -> coldMiss(options, run, measured);
            case WARM_HIT -> warmHit(options, run, measured);
            case SEQUENTIAL_WITHIN_CAPACITY -> sequentialWithinCapacity(options, run, measured);
            case SEQUENTIAL_OVER_CAPACITY -> sequentialOverCapacity(options, run, measured);
            case RANDOM_HOT_SET -> randomHotSet(options, run, measured);
            case HOT_SET_AFTER_SCAN -> hotSetAfterScan(options, run, measured);
            case PIN_UNPIN -> pinUnpin(options, run, measured);
            case DIRTY_PRESSURE -> dirtyPressure(options, run, measured);
            case WAL_GROUPED_FLUSH -> walGroupedFlush(options, run, measured);
        };
    }

    private static MvccBufferCacheMeasurement coldMiss(Options options, int run, boolean measured)
            throws Exception {
        int operations = options.pageCount();
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int page = 0; page < operations; page++) {
            checksum = mix(checksum, cache.read(volume, new DelosPageId(page)));
        }
        long elapsed = System.nanoTime() - started;
        return measurement(
                MvccBufferCacheMeasurement.Workload.COLD_MISS,
                options,
                run,
                measured,
                operations,
                elapsed,
                checksum,
                before,
                State.capture(cache, volume),
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement warmHit(Options options, int run, boolean measured)
            throws Exception {
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        DelosPageId pageId = new DelosPageId(0L);
        cache.read(volume, pageId);
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int operation = 0; operation < options.operations(); operation++) {
            checksum = mix(checksum, cache.read(volume, pageId));
        }
        long elapsed = System.nanoTime() - started;
        State after = State.capture(cache, volume);
        require(after.volumeReads() == before.volumeReads(), "warm cache hit reread the page volume");
        return measurement(
                MvccBufferCacheMeasurement.Workload.WARM_HIT,
                options,
                run,
                measured,
                options.operations(),
                elapsed,
                checksum,
                before,
                after,
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement sequentialWithinCapacity(
            Options options,
            int run,
            boolean measured) throws Exception {
        int workingSet = Math.max(1, Math.min(options.pageCount(), options.cacheCapacity() / 2));
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        for (int page = 0; page < workingSet; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int operation = 0; operation < options.operations(); operation++) {
            checksum = mix(checksum, cache.read(volume, new DelosPageId(operation % workingSet)));
        }
        long elapsed = System.nanoTime() - started;
        State after = State.capture(cache, volume);
        require(after.volumeReads() == before.volumeReads(),
                "within-capacity sequential walk unexpectedly missed the cache");
        return measurement(
                MvccBufferCacheMeasurement.Workload.SEQUENTIAL_WITHIN_CAPACITY,
                options,
                run,
                measured,
                options.operations(),
                elapsed,
                checksum,
                before,
                after,
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement sequentialOverCapacity(
            Options options,
            int run,
            boolean measured) throws Exception {
        int workingSet = Math.min(options.pageCount(), Math.multiplyExact(options.cacheCapacity(), 4));
        int operations = Math.multiplyExact(workingSet, 2);
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long maxSize = 0L;
        long started = System.nanoTime();
        for (int operation = 0; operation < operations; operation++) {
            checksum = mix(checksum, cache.read(volume, new DelosPageId(operation % workingSet)));
            maxSize = Math.max(maxSize, cache.snapshot().size());
        }
        long elapsed = System.nanoTime() - started;
        return measurement(
                MvccBufferCacheMeasurement.Workload.SEQUENTIAL_OVER_CAPACITY,
                options,
                run,
                measured,
                operations,
                elapsed,
                checksum,
                before,
                State.capture(cache, volume),
                maxSize);
    }

    private static MvccBufferCacheMeasurement randomHotSet(Options options, int run, boolean measured)
            throws Exception {
        int hotSet = Math.max(1, Math.min(options.pageCount(), options.cacheCapacity() / 2));
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        for (int page = 0; page < hotSet; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        State before = State.capture(cache, volume);
        Random random = new Random(RANDOM_SEED + run);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int operation = 0; operation < options.operations(); operation++) {
            checksum = mix(checksum, cache.read(volume, new DelosPageId(random.nextInt(hotSet))));
        }
        long elapsed = System.nanoTime() - started;
        State after = State.capture(cache, volume);
        require(after.volumeReads() == before.volumeReads(), "random hot-set workload missed a resident page");
        return measurement(
                MvccBufferCacheMeasurement.Workload.RANDOM_HOT_SET,
                options,
                run,
                measured,
                options.operations(),
                elapsed,
                checksum,
                before,
                after,
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement hotSetAfterScan(Options options, int run, boolean measured)
            throws Exception {
        int hotSet = Math.max(1, Math.min(options.cacheCapacity() / 4, options.pageCount() / 8));
        int scanStart = hotSet;
        int scanLength = Math.min(options.pageCount() - scanStart, Math.multiplyExact(options.cacheCapacity(), 4));
        require(scanLength > options.cacheCapacity(),
                "pageCount must leave an over-capacity cold scan after the hot set");
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        for (int page = 0; page < hotSet; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        for (int page = scanStart; page < scanStart + scanLength; page++) {
            cache.read(volume, new DelosPageId(page));
        }
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int page = 0; page < hotSet; page++) {
            checksum = mix(checksum, cache.read(volume, new DelosPageId(page)));
        }
        long elapsed = System.nanoTime() - started;
        return measurement(
                MvccBufferCacheMeasurement.Workload.HOT_SET_AFTER_SCAN,
                options,
                run,
                measured,
                hotSet,
                elapsed,
                checksum,
                before,
                State.capture(cache, volume),
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement pinUnpin(Options options, int run, boolean measured)
            throws Exception {
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        DelosPageId pageId = new DelosPageId(0L);
        cache.read(volume, pageId);
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long started = System.nanoTime();
        for (int operation = 0; operation < options.operations(); operation++) {
            try (MvccPageCache.PinnedPage pinned = cache.readPinned(volume, pageId)) {
                checksum = mix(checksum, pinned.page());
            }
        }
        long elapsed = System.nanoTime() - started;
        State after = State.capture(cache, volume);
        Delta delta = Delta.between(before, after);
        require(delta.pins() == options.operations(), "pin count does not match measured operations");
        require(delta.pins() == delta.unpins(), "pin/unpin counts are not balanced");
        require(after.cache().pinnedPages() == 0L, "pin/unpin workload leaked a pin");
        return measurement(
                MvccBufferCacheMeasurement.Workload.PIN_UNPIN,
                options,
                run,
                measured,
                options.operations(),
                elapsed,
                checksum,
                before,
                after,
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement dirtyPressure(Options options, int run, boolean measured)
            throws Exception {
        int dirtyPages = Math.min(options.pageCount(), Math.multiplyExact(options.cacheCapacity(), 2));
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        State before = State.capture(cache, volume);
        long checksum = 1L;
        long maxSize = 0L;
        long started = System.nanoTime();
        for (int page = 0; page < dirtyPages; page++) {
            DelosPage dirty = dataPage(page, page + 1L);
            cache.putDirty(dirty);
            checksum = mix(checksum, dirty);
            maxSize = Math.max(maxSize, cache.snapshot().size());
        }
        long elapsed = System.nanoTime() - started;
        State pressured = State.capture(cache, volume);
        require(pressured.cache().dirtyPages() == dirtyPages, "dirty pressure lost dirty pages");
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(dirtyPages));
        cache.flushAll(volume, coordinator);
        State afterFlush = State.capture(cache, volume);
        require(afterFlush.cache().dirtyPages() == 0L, "dirty pressure cleanup left dirty pages");
        require(afterFlush.cache().size() <= options.cacheCapacity(),
                "dirty pressure cleanup did not restore the configured capacity");
        return measurement(
                MvccBufferCacheMeasurement.Workload.DIRTY_PRESSURE,
                options,
                run,
                measured,
                dirtyPages,
                elapsed,
                checksum,
                before,
                pressured,
                maxSize);
    }

    private static MvccBufferCacheMeasurement walGroupedFlush(Options options, int run, boolean measured)
            throws Exception {
        int dirtyPages = Math.min(options.pageCount(), options.cacheCapacity());
        CountingPageVolume volume = populatedVolume(options.pageCount());
        MvccPageCache cache = new MvccPageCache(options.cacheCapacity());
        for (int page = 0; page < dirtyPages; page++) {
            cache.putDirty(dataPage(page, page + 1L));
        }
        MvccBufferFlushCoordinator coordinator = new MvccBufferFlushCoordinator();
        coordinator.recordLogForcedThrough(new DelosLogSequenceNumber(dirtyPages));
        State before = State.capture(cache, volume);
        long started = System.nanoTime();
        long flushed = cache.flushAll(volume, coordinator);
        long elapsed = System.nanoTime() - started;
        State after = State.capture(cache, volume);
        Delta delta = Delta.between(before, after);
        require(flushed == dirtyPages, "grouped flush did not flush every dirty page");
        require(delta.volumeWrites() == dirtyPages, "grouped flush page-write count mismatch");
        require(delta.volumeForces() == 1L, "grouped flush must use one page-volume force");
        require(delta.walBeforeFlushChecks() == dirtyPages, "WAL-before-flush check count mismatch");
        require(delta.walBeforeFlushFailures() == 0L, "grouped flush reported a WAL failure");
        return measurement(
                MvccBufferCacheMeasurement.Workload.WAL_GROUPED_FLUSH,
                options,
                run,
                measured,
                dirtyPages,
                elapsed,
                flushed,
                before,
                after,
                cache.snapshot().size());
    }

    private static MvccBufferCacheMeasurement measurement(
            MvccBufferCacheMeasurement.Workload workload,
            Options options,
            int run,
            boolean measured,
            int measuredOperations,
            long elapsedNanos,
            long checksum,
            State before,
            State after,
            long maxObservedCacheSize) {
        Delta delta = Delta.between(before, after);
        long requests = delta.hits() + delta.misses();
        double hitRate = requests == 0L ? 0.0 : (double) delta.hits() / requests;
        if (!measured) {
            return null;
        }
        return new MvccBufferCacheMeasurement(
                workload,
                after.cache().replacementPolicyName(),
                options.cacheCapacity(),
                options.pageCount(),
                measuredOperations,
                options.warmups(),
                options.iterations(),
                elapsedNanos,
                measuredOperations * 1_000_000_000.0 / elapsedNanos,
                (double) elapsedNanos / measuredOperations,
                checksum,
                delta.hits(),
                delta.misses(),
                hitRate,
                delta.cacheWrites(),
                delta.evictions(),
                delta.invalidations(),
                delta.pins(),
                delta.unpins(),
                after.cache().pinnedPages(),
                after.cache().dirtyPages(),
                delta.flushes(),
                delta.groupedForceBatches(),
                delta.groupedForcedPages(),
                delta.walBeforeFlushChecks(),
                delta.walBeforeFlushFailures(),
                delta.pinnedEvictionSkips(),
                delta.replacementScans(),
                delta.dirtyProtectionSkips(),
                delta.noVictimCount(),
                delta.readAdmissionBypasses(),
                delta.secondTouchAdmissions(),
                after.cache().ghostHistoryPages(),
                after.cache().size(),
                maxObservedCacheSize,
                delta.volumeReads(),
                delta.volumeWrites(),
                delta.volumeForces(),
                run);
    }

    private static CountingPageVolume populatedVolume(int pageCount) {
        CountingPageVolume volume = new CountingPageVolume();
        for (int page = 0; page < pageCount; page++) {
            volume.install(dataPage(page, 0L));
        }
        volume.resetCounters();
        return volume;
    }

    private static DelosPage dataPage(long pageId, long pageLsn) {
        DelosPage page = DelosPage.empty(new DelosPageId(pageId), DelosPage.DATA_PAGE_TYPE);
        for (int record = 0; record < RECORDS_PER_PAGE; record++) {
            byte[] payload = new byte[PAYLOAD_BYTES];
            payload[0] = (byte) pageId;
            payload[1] = (byte) record;
            payload[payload.length - 1] = (byte) (pageId ^ record);
            page.appendRecord(payload);
        }
        return page.withPageLsn(pageLsn);
    }

    private static long mix(long checksum, DelosPage page) {
        long mixed = checksum * 31L + page.pageId().value();
        mixed = mixed * 31L + page.pageLsn();
        mixed = mixed * 31L + page.slotCount();
        return mixed * 31L + page.freeBytes();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void writeReports(
            Options options,
            List<MvccBufferCacheMeasurement> measurements) throws IOException {
        writeSummary(options, measurements);
        writeCsv(options, measurements);
        writeJson(options, measurements);
    }

    private static void writeSummary(
            Options options,
            List<MvccBufferCacheMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("DelosDB MVCC buffer/cache baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Cache capacity: ").append(options.cacheCapacity()).append('\n')
                .append("Page count: ").append(options.pageCount()).append('\n')
                .append("Repeated operations: ").append(options.operations()).append('\n')
                .append("Workloads: ").append(options.workloads()).append('\n')
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (MvccBufferCacheMeasurement measurement : measurements) {
            output.append(String.format(Locale.ROOT,
                    "%-26s policy=%-39s ops=%-7d run=%d rate=%12.3f avg-ns=%12.3f "
                            + "hits=%-7d misses=%-7d hit-rate=%6.2f%% evict=%-6d scan=%-8d "
                            + "no-victim=%-5d dirty-skip=%-7d bypass=%-7d admit2=%-5d ghost=%-5d "
                            + "max-size=%-5d reads=%-7d writes=%-7d force=%-3d checksum=%d%n",
                    measurement.workload(),
                    measurement.replacementPolicy(),
                    measurement.measuredOperations(),
                    measurement.run(),
                    measurement.throughputPerSecond(),
                    measurement.averageLatencyNanos(),
                    measurement.hits(),
                    measurement.misses(),
                    measurement.hitRate() * 100.0,
                    measurement.evictions(),
                    measurement.replacementScans(),
                    measurement.noVictimCount(),
                    measurement.dirtyProtectionSkips(),
                    measurement.readAdmissionBypasses(),
                    measurement.secondTouchAdmissions(),
                    measurement.ghostHistoryPages(),
                    measurement.maxObservedCacheSize(),
                    measurement.volumeReads(),
                    measurement.volumeWrites(),
                    measurement.volumeForces(),
                    measurement.checksum()));
        }
        Files.writeString(
                options.reportDirectory().resolve("buffer-cache-summary.txt"),
                output,
                StandardCharsets.UTF_8);
    }

    private static void writeCsv(
            Options options,
            List<MvccBufferCacheMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("workload,replacementPolicy,cacheCapacity,pageCount,measuredOperations,warmups,iterations,")
                .append("elapsedNanos,throughputPerSecond,averageLatencyNanos,checksum,hits,misses,hitRate,")
                .append("cacheWrites,evictions,invalidations,pins,unpins,pinnedPages,dirtyPages,flushes,")
                .append("groupedForceBatches,groupedForcedPages,walBeforeFlushChecks,walBeforeFlushFailures,")
                .append("pinnedEvictionSkips,replacementScans,dirtyProtectionSkips,noVictimCount,")
                .append("readAdmissionBypasses,secondTouchAdmissions,ghostHistoryPages,")
                .append("finalCacheSize,maxObservedCacheSize,volumeReads,volumeWrites,volumeForces,run\n");
        for (MvccBufferCacheMeasurement m : measurements) {
            output.append(m.workload()).append(',')
                    .append(m.replacementPolicy()).append(',')
                    .append(m.cacheCapacity()).append(',')
                    .append(m.pageCount()).append(',')
                    .append(m.measuredOperations()).append(',')
                    .append(m.warmups()).append(',')
                    .append(m.iterations()).append(',')
                    .append(m.elapsedNanos()).append(',')
                    .append(m.throughputPerSecond()).append(',')
                    .append(m.averageLatencyNanos()).append(',')
                    .append(m.checksum()).append(',')
                    .append(m.hits()).append(',')
                    .append(m.misses()).append(',')
                    .append(m.hitRate()).append(',')
                    .append(m.cacheWrites()).append(',')
                    .append(m.evictions()).append(',')
                    .append(m.invalidations()).append(',')
                    .append(m.pins()).append(',')
                    .append(m.unpins()).append(',')
                    .append(m.pinnedPages()).append(',')
                    .append(m.dirtyPages()).append(',')
                    .append(m.flushes()).append(',')
                    .append(m.groupedForceBatches()).append(',')
                    .append(m.groupedForcedPages()).append(',')
                    .append(m.walBeforeFlushChecks()).append(',')
                    .append(m.walBeforeFlushFailures()).append(',')
                    .append(m.pinnedEvictionSkips()).append(',')
                    .append(m.replacementScans()).append(',')
                    .append(m.dirtyProtectionSkips()).append(',')
                    .append(m.noVictimCount()).append(',')
                    .append(m.readAdmissionBypasses()).append(',')
                    .append(m.secondTouchAdmissions()).append(',')
                    .append(m.ghostHistoryPages()).append(',')
                    .append(m.finalCacheSize()).append(',')
                    .append(m.maxObservedCacheSize()).append(',')
                    .append(m.volumeReads()).append(',')
                    .append(m.volumeWrites()).append(',')
                    .append(m.volumeForces()).append(',')
                    .append(m.run()).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("buffer-cache-results.csv"),
                output,
                StandardCharsets.UTF_8);
    }

    private static void writeJson(
            Options options,
            List<MvccBufferCacheMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder("[\n");
        for (int index = 0; index < measurements.size(); index++) {
            MvccBufferCacheMeasurement m = measurements.get(index);
            output.append("  {\n")
                    .append("    \"workload\": \"").append(m.workload()).append("\",\n")
                    .append("    \"replacementPolicy\": \"").append(m.replacementPolicy()).append("\",\n")
                    .append("    \"cacheCapacity\": ").append(m.cacheCapacity()).append(",\n")
                    .append("    \"pageCount\": ").append(m.pageCount()).append(",\n")
                    .append("    \"measuredOperations\": ").append(m.measuredOperations()).append(",\n")
                    .append("    \"elapsedNanos\": ").append(m.elapsedNanos()).append(",\n")
                    .append("    \"throughputPerSecond\": ").append(m.throughputPerSecond()).append(",\n")
                    .append("    \"averageLatencyNanos\": ").append(m.averageLatencyNanos()).append(",\n")
                    .append("    \"hits\": ").append(m.hits()).append(",\n")
                    .append("    \"misses\": ").append(m.misses()).append(",\n")
                    .append("    \"hitRate\": ").append(m.hitRate()).append(",\n")
                    .append("    \"evictions\": ").append(m.evictions()).append(",\n")
                    .append("    \"replacementScans\": ").append(m.replacementScans()).append(",\n")
                    .append("    \"dirtyProtectionSkips\": ").append(m.dirtyProtectionSkips()).append(",\n")
                    .append("    \"noVictimCount\": ").append(m.noVictimCount()).append(",\n")
                    .append("    \"readAdmissionBypasses\": ").append(m.readAdmissionBypasses()).append(",\n")
                    .append("    \"secondTouchAdmissions\": ").append(m.secondTouchAdmissions()).append(",\n")
                    .append("    \"ghostHistoryPages\": ").append(m.ghostHistoryPages()).append(",\n")
                    .append("    \"maxObservedCacheSize\": ").append(m.maxObservedCacheSize()).append(",\n")
                    .append("    \"volumeReads\": ").append(m.volumeReads()).append(",\n")
                    .append("    \"volumeWrites\": ").append(m.volumeWrites()).append(",\n")
                    .append("    \"volumeForces\": ").append(m.volumeForces()).append(",\n")
                    .append("    \"checksum\": ").append(m.checksum()).append(",\n")
                    .append("    \"run\": ").append(m.run()).append('\n')
                    .append("  }");
            if (index + 1 < measurements.size()) {
                output.append(',');
            }
            output.append('\n');
        }
        output.append("]\n");
        Files.writeString(
                options.reportDirectory().resolve("buffer-cache-results.json"),
                output,
                StandardCharsets.UTF_8);
    }

    record Options(
            Path reportDirectory,
            int cacheCapacity,
            int pageCount,
            int operations,
            int warmups,
            int iterations,
            int runs,
            EnumSet<MvccBufferCacheMeasurement.Workload> workloads) {

        void validate() {
            Objects.requireNonNull(reportDirectory, "reportDirectory");
            Objects.requireNonNull(workloads, "workloads");
            require(cacheCapacity >= 4, "cacheCapacity must be at least 4");
            require(pageCount > cacheCapacity * 5,
                    "pageCount must be greater than five times cacheCapacity for scan-pressure workloads");
            require(operations > 0, "operations must be positive");
            require(warmups >= 0, "warmups must not be negative");
            require(iterations == 1,
                    "iterations must currently be 1; each workload already measures one batched interval");
            require(runs > 0, "runs must be positive");
            require(!workloads.isEmpty(), "at least one workload is required");
        }
    }

    private record State(
            MvccPageCache.Snapshot cache,
            long volumeReads,
            long volumeWrites,
            long volumeForces) {
        static State capture(MvccPageCache cache, CountingPageVolume volume) {
            return new State(cache.snapshot(), volume.readCount, volume.writeCount, volume.forceCount);
        }
    }

    private record Delta(
            long hits,
            long misses,
            long cacheWrites,
            long evictions,
            long invalidations,
            long pins,
            long unpins,
            long flushes,
            long groupedForceBatches,
            long groupedForcedPages,
            long walBeforeFlushChecks,
            long walBeforeFlushFailures,
            long pinnedEvictionSkips,
            long replacementScans,
            long dirtyProtectionSkips,
            long noVictimCount,
            long readAdmissionBypasses,
            long secondTouchAdmissions,
            long volumeReads,
            long volumeWrites,
            long volumeForces) {
        static Delta between(State before, State after) {
            MvccPageCache.Snapshot left = before.cache();
            MvccPageCache.Snapshot right = after.cache();
            return new Delta(
                    right.hits() - left.hits(),
                    right.misses() - left.misses(),
                    right.writes() - left.writes(),
                    right.evictions() - left.evictions(),
                    right.invalidations() - left.invalidations(),
                    right.pins() - left.pins(),
                    right.unpins() - left.unpins(),
                    right.flushes() - left.flushes(),
                    right.groupedForceBatches() - left.groupedForceBatches(),
                    right.groupedForcedPages() - left.groupedForcedPages(),
                    right.walBeforeFlushChecks() - left.walBeforeFlushChecks(),
                    right.walBeforeFlushFailures() - left.walBeforeFlushFailures(),
                    right.pinnedEvictionSkips() - left.pinnedEvictionSkips(),
                    right.replacementScans() - left.replacementScans(),
                    right.replacementDirtyProtectionSkips() - left.replacementDirtyProtectionSkips(),
                    right.replacementNoVictimCount() - left.replacementNoVictimCount(),
                    right.readAdmissionBypasses() - left.readAdmissionBypasses(),
                    right.secondTouchAdmissions() - left.secondTouchAdmissions(),
                    after.volumeReads() - before.volumeReads(),
                    after.volumeWrites() - before.volumeWrites(),
                    after.volumeForces() - before.volumeForces());
        }
    }

    private static final class CountingPageVolume implements DelosPageVolume {
        private final Map<Long, DelosPage> pages = new LinkedHashMap<>();
        private long readCount;
        private long writeCount;
        private long forceCount;

        void install(DelosPage page) {
            pages.put(page.pageId().value(), page);
        }

        void resetCounters() {
            readCount = 0L;
            writeCount = 0L;
            forceCount = 0L;
        }

        @Override
        public DelosPage readPage(DelosPageId id) {
            readCount++;
            DelosPage page = pages.get(id.value());
            if (page == null) {
                throw new IllegalStateException("missing benchmark page " + id.value());
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
