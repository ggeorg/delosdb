package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

final class MvccPageCodecBenchmarkTest {
    @Test
    void recordsConfiguredPageCodecBaseline() throws Exception {
        MvccPageCodecBenchmark.Options options = new MvccPageCodecBenchmark.Options(
                Path.of(MvccBenchmarkTestProperties.required(
                        "delosdb.benchmark.pageCodec.reportDirectory")),
                payloadSizesProperty(),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.pageCodec.maxOperations", 10_000),
                MvccBenchmarkTestProperties.longValue(
                        "delosdb.benchmark.pageCodec.byteBudget", 4L * 1024L * 1024L),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.pageCodec.warmups", 1),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.pageCodec.iterations", 1),
                MvccBenchmarkTestProperties.integer("delosdb.benchmark.pageCodec.runs", 2),
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
        return MvccBenchmarkTestProperties.integerList(
                "delosdb.benchmark.pageCodec.payloadSizes",
                "16,128,1024,8192,65536");
    }

    private static EnumSet<MvccPageCodecMeasurement.Workload> workloadsProperty() {
        return MvccBenchmarkTestProperties.enumSet(
                "delosdb.benchmark.pageCodec.workloads",
                MvccPageCodecMeasurement.Workload.class,
                EnumSet.allOf(MvccPageCodecMeasurement.Workload.class));
    }

}
