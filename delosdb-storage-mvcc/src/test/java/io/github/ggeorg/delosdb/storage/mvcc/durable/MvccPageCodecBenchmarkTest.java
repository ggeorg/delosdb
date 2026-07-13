package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class MvccPageCodecBenchmarkTest {
    @Test
    void recordsConfiguredPageCodecBaseline() throws Exception {
        MvccPageCodecBenchmark.Options options = new MvccPageCodecBenchmark.Options(
                Path.of(requiredProperty("delosdb.benchmark.pageCodec.reportDirectory")),
                payloadSizesProperty(),
                intProperty("delosdb.benchmark.pageCodec.maxOperations", 10_000),
                longProperty("delosdb.benchmark.pageCodec.byteBudget", 4L * 1024L * 1024L),
                intProperty("delosdb.benchmark.pageCodec.warmups", 1),
                intProperty("delosdb.benchmark.pageCodec.iterations", 1),
                intProperty("delosdb.benchmark.pageCodec.runs", 2),
                workloadsProperty());

        List<MvccPageCodecMeasurement> measurements = MvccPageCodecBenchmark.run(options);

        assertFalse(measurements.isEmpty());
        assertEquals(
                options.payloadSizes().size() * options.workloads().size() * options.runs(),
                measurements.size(),
                "one measurement is required for every payload, workload, and run");
        Map<String, Long> checksumByCase = new HashMap<>();
        for (MvccPageCodecMeasurement measurement : measurements) {
            assertTrue(measurement.measuredOperations() > 0);
            assertTrue(measurement.elapsedNanos() > 0L);
            assertTrue(measurement.encodedBytesPerOperation() > 0L);
            String caseKey = measurement.workload() + ":" + measurement.payloadBytes();
            Long priorChecksum = checksumByCase.putIfAbsent(caseKey, measurement.checksum());
            if (priorChecksum != null) {
                assertEquals(priorChecksum.longValue(), measurement.checksum(),
                        "codec checksum must remain deterministic across runs");
            }
            if (measurement.allocationMeasurementAvailable()) {
                assertTrue(measurement.allocatedBytes() >= 0L);
                assertTrue(measurement.allocatedBytesPerOperation() >= 0.0);
            } else {
                assertEquals(-1L, measurement.allocatedBytes());
                assertEquals(-1.0, measurement.allocatedBytesPerOperation());
            }
        }
    }

    private static List<Integer> payloadSizesProperty() {
        String configured = System.getProperty(
                "delosdb.benchmark.pageCodec.payloadSizes",
                "16,128,1024,8192,65536");
        List<Integer> sizes = new ArrayList<>();
        for (String value : configured.split(",")) {
            sizes.add(Integer.valueOf(value.trim()));
        }
        return List.copyOf(sizes);
    }

    private static EnumSet<MvccPageCodecMeasurement.Workload> workloadsProperty() {
        String configured = System.getProperty("delosdb.benchmark.pageCodec.workloads", "").trim();
        if (configured.isEmpty()) {
            return EnumSet.allOf(MvccPageCodecMeasurement.Workload.class);
        }
        EnumSet<MvccPageCodecMeasurement.Workload> workloads =
                EnumSet.noneOf(MvccPageCodecMeasurement.Workload.class);
        for (String value : configured.split(",")) {
            workloads.add(MvccPageCodecMeasurement.Workload.valueOf(value.trim()));
        }
        return workloads;
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private static long longProperty(String name, long defaultValue) {
        return Long.parseLong(System.getProperty(name, Long.toString(defaultValue)));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property: " + name);
        }
        return value;
    }
}
