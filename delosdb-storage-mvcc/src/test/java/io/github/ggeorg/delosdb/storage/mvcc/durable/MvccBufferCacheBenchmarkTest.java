package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;

final class MvccBufferCacheBenchmarkTest {
    @Test
    void recordsConfiguredBufferCacheBaseline() throws Exception {
        MvccBufferCacheBenchmark.Options options = new MvccBufferCacheBenchmark.Options(
                Path.of(MvccBenchmarkTestProperties.required(
                        "delosdb.benchmark.bufferCache.reportDirectory")),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.capacity", 128),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.pages", 768),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.operations", 20_000),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.warmups", 1),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.iterations", 1),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.bufferCache.runs", 2),
                workloadsProperty());

        List<MvccBufferCacheMeasurement> measurements = MvccBufferCacheBenchmark.run(options);

        assertFalse(measurements.isEmpty());
        assertEquals(
                options.workloads().size() * options.runs(),
                measurements.size(),
                "one measurement is required for every workload and run");
        for (MvccBufferCacheMeasurement measurement : measurements) {
            assertEquals(
                    "ACCESS_ORDER_LRU_SECOND_TOUCH_ADMISSION",
                    measurement.replacementPolicy());
            assertEquals(0L, measurement.walBeforeFlushFailures());
            assertEquals(0L, measurement.pinnedPages());
            if (measurement.workload() == MvccBufferCacheMeasurement.Workload.HOT_SET_AFTER_SCAN) {
                assertEquals(1.0, measurement.hitRate());
                assertEquals(0L, measurement.volumeReads());
            }
            if (measurement.workload() == MvccBufferCacheMeasurement.Workload.DIRTY_PRESSURE) {
                assertTrue(
                        measurement.replacementScans() <= measurement.cacheCapacity() + 1L,
                        "known all-dirty pressure must not repeat full replacement scans");
            }
        }
    }

    private static EnumSet<MvccBufferCacheMeasurement.Workload> workloadsProperty() {
        return MvccBenchmarkTestProperties.enumSet(
                "delosdb.benchmark.bufferCache.workloads",
                MvccBufferCacheMeasurement.Workload.class,
                EnumSet.allOf(MvccBufferCacheMeasurement.Workload.class));
    }

}
