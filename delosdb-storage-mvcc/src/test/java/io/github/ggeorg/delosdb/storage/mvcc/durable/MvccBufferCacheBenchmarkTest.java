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
                Path.of(requiredProperty("delosdb.benchmark.bufferCache.reportDirectory")),
                intProperty("delosdb.benchmark.bufferCache.capacity", 128),
                intProperty("delosdb.benchmark.bufferCache.pages", 768),
                intProperty("delosdb.benchmark.bufferCache.operations", 20_000),
                intProperty("delosdb.benchmark.bufferCache.warmups", 1),
                intProperty("delosdb.benchmark.bufferCache.iterations", 1),
                intProperty("delosdb.benchmark.bufferCache.runs", 2),
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
        String configured = System.getProperty("delosdb.benchmark.bufferCache.workloads", "").trim();
        if (configured.isEmpty()) {
            return EnumSet.allOf(MvccBufferCacheMeasurement.Workload.class);
        }
        EnumSet<MvccBufferCacheMeasurement.Workload> workloads =
                EnumSet.noneOf(MvccBufferCacheMeasurement.Workload.class);
        for (String value : configured.split(",")) {
            workloads.add(MvccBufferCacheMeasurement.Workload.valueOf(value.trim()));
        }
        return workloads;
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }
}
