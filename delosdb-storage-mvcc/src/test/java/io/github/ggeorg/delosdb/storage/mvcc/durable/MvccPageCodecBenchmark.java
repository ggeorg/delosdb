package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccPageRecordCodec;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

/**
 * Low-level page and record codec baseline for Phase 5.6.
 *
 * <p>The benchmark measures the real production codecs in batched intervals.
 * Fixture construction and encoded-input creation occur outside timing. Timing
 * results never act as correctness thresholds.</p>
 */
final class MvccPageCodecBenchmark {
    private static final Base64.Encoder RECOVERY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder RECOVERY_DECODER = Base64.getUrlDecoder();
    private static final String INDEX_NAME = "IDX_CODEC_BASELINE";

    private MvccPageCodecBenchmark() {
    }

    static List<MvccPageCodecMeasurement> run(Options options) throws Exception {
        Objects.requireNonNull(options, "options").validate();
        Files.createDirectories(options.reportDirectory());

        List<Case> cases = cases(options);
        List<MvccPageCodecMeasurement> measurements = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            List<Case> ordered = new ArrayList<>(cases);
            if ((run & 1) == 0) {
                Collections.reverse(ordered);
            }
            for (Case benchmarkCase : ordered) {
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    execute(benchmarkCase, options, run, false);
                }
                measurements.add(execute(benchmarkCase, options, run, true));
            }
        }
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static List<Case> cases(Options options) {
        List<Case> cases = new ArrayList<>();
        for (int payloadBytes : options.payloadSizes()) {
            for (MvccPageCodecMeasurement.Workload workload : options.workloads()) {
                cases.add(new Case(workload, payloadBytes));
            }
        }
        return List.copyOf(cases);
    }

    private static MvccPageCodecMeasurement execute(
            Case benchmarkCase,
            Options options,
            int run,
            boolean measured) {
        Fixture fixture = Fixture.create(benchmarkCase.payloadBytes());
        int operations = options.operationsFor(benchmarkCase.payloadBytes());
        AllocationMeter allocationMeter = AllocationMeter.currentThread();
        long allocationStart = allocationMeter.currentBytes();
        long checksum = 1L;
        long started = System.nanoTime();
        for (int operation = 0; operation < operations; operation++) {
            checksum = executeOne(benchmarkCase.workload(), fixture, checksum);
        }
        long elapsed = System.nanoTime() - started;
        long allocationEnd = allocationMeter.currentBytes();
        long allocatedBytes = allocationMeter.delta(allocationStart, allocationEnd);
        long encodedBytes = encodedBytes(benchmarkCase.workload(), fixture);

        require(elapsed > 0L, "codec benchmark elapsed time must be positive");
        require(encodedBytes > 0L, "codec benchmark encoded size must be positive");
        double throughput = operations * 1_000_000_000.0 / elapsed;
        double averageLatency = (double) elapsed / operations;
        double allocatedPerOperation = allocatedBytes < 0L ? -1.0 : (double) allocatedBytes / operations;
        return new MvccPageCodecMeasurement(
                benchmarkCase.workload(),
                benchmarkCase.payloadBytes(),
                operations,
                options.warmups(),
                options.iterations(),
                elapsed,
                throughput,
                averageLatency,
                encodedBytes,
                allocatedBytes,
                allocatedPerOperation,
                allocatedBytes >= 0L,
                checksum,
                run);
    }

    private static long executeOne(
            MvccPageCodecMeasurement.Workload workload,
            Fixture fixture,
            long checksum) {
        return switch (workload) {
            case ROW_PAYLOAD_ENCODE -> mixBytes(checksum, MvccRowPayloadCodec.encode(fixture.rowPayload()));
            case ROW_PAYLOAD_DECODE -> mix(checksum, MvccRowPayloadCodec.decode(fixture.rowPayloadBytes()).hashCode());
            case VERSION_RECORD_ENCODE -> mixBytes(checksum, MvccVersionRecordCodec.encode(fixture.versionRecord()));
            case VERSION_RECORD_DECODE -> mixVersion(
                    checksum,
                    MvccVersionRecordCodec.decode(fixture.versionRecordBytes()));
            case PAGE_RECORD_ENCODE -> mixBytes(
                    checksum,
                    MvccPageRecordCodec.encodeVersionRecord(fixture.versionRecord()));
            case PAGE_RECORD_DECODE -> mixPageRecord(
                    checksum,
                    MvccPageRecordCodec.decode(fixture.pageRecordBytes()));
            case INDEX_TUPLE_ENCODE -> mixBytes(checksum, MvccIndexTupleCodec.encode(fixture.indexTuple()));
            case INDEX_TUPLE_DECODE -> mix(checksum, MvccIndexTupleCodec.decode(fixture.indexTupleBytes()).hashCode());
            case OVERFLOW_CHUNK_ENCODE -> mixBytes(
                    checksum,
                    MvccOverflowPayloadCodec.encodeChunk(fixture.overflowChunk()));
            case OVERFLOW_CHUNK_DECODE -> mix(
                    checksum,
                    MvccOverflowPayloadCodec.decodeChunk(fixture.overflowChunkBytes()).hashCode());
            case RECOVERY_RECORD_ENCODE -> mix(
                    checksum,
                    encodeRecoveryRecord(fixture.versionRecord()).hashCode());
            case RECOVERY_RECORD_DECODE -> mixVersion(
                    checksum,
                    decodeRecoveryRecord(fixture.recoveryRecord()));
        };
    }

    private static String encodeRecoveryRecord(MvccVersionRecord record) {
        return RECOVERY_ENCODER.encodeToString(MvccVersionRecordCodec.encode(record));
    }

    private static MvccVersionRecord decodeRecoveryRecord(String encoded) {
        return MvccVersionRecordCodec.decode(RECOVERY_DECODER.decode(encoded));
    }

    private static long encodedBytes(MvccPageCodecMeasurement.Workload workload, Fixture fixture) {
        return switch (workload) {
            case ROW_PAYLOAD_ENCODE, ROW_PAYLOAD_DECODE -> fixture.rowPayloadBytes().length;
            case VERSION_RECORD_ENCODE, VERSION_RECORD_DECODE -> fixture.versionRecordBytes().length;
            case PAGE_RECORD_ENCODE, PAGE_RECORD_DECODE -> fixture.pageRecordBytes().length;
            case INDEX_TUPLE_ENCODE, INDEX_TUPLE_DECODE -> fixture.indexTupleBytes().length;
            case OVERFLOW_CHUNK_ENCODE, OVERFLOW_CHUNK_DECODE -> fixture.overflowChunkBytes().length;
            case RECOVERY_RECORD_ENCODE, RECOVERY_RECORD_DECODE ->
                    fixture.recoveryRecord().getBytes(StandardCharsets.US_ASCII).length;
        };
    }

    private static long mixBytes(long checksum, byte[] bytes) {
        long mixed = mix(checksum, bytes.length);
        mixed = mix(mixed, Byte.toUnsignedInt(bytes[0]));
        mixed = mix(mixed, Byte.toUnsignedInt(bytes[bytes.length / 2]));
        return mix(mixed, Byte.toUnsignedInt(bytes[bytes.length - 1]));
    }

    private static long mixVersion(long checksum, MvccVersionRecord record) {
        long mixed = mix(checksum, record.header().rowId().value());
        mixed = mix(mixed, record.header().versionId().value());
        mixed = mix(mixed, record.header().createdByTx().value());
        mixed = mix(mixed, record.header().commitSequence().value());
        return mix(mixed, record.encodedLength());
    }

    private static long mixPageRecord(long checksum, MvccPageRecordCodec.PageRecord record) {
        long mixed = mix(checksum, record.metadata().recordType());
        mixed = mix(mixed, record.metadata().bodyLength());
        mixed = mix(mixed, record.metadata().bodyChecksum());
        return mixVersion(mixed, record.versionRecord());
    }

    private static long mix(long checksum, long value) {
        return checksum * 31L + value;
    }

    private static byte[] payload(int payloadBytes) {
        byte[] payload = new byte[payloadBytes];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) ((index * 31 + payloadBytes) & 0xFF);
        }
        return payload;
    }

    private static MvccVersionRecord versionRecord(byte[] payload) {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(41L),
                        new MvccVersionId(73L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(17L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(29L),
                        0),
                payload);
    }

    private static void writeReports(
            Options options,
            List<MvccPageCodecMeasurement> measurements) throws IOException {
        writeSummary(options, measurements);
        writeCsv(options, measurements);
        writeJson(options, measurements);
    }

    private static void writeSummary(
            Options options,
            List<MvccPageCodecMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("DelosDB MVCC page/codec baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Payload sizes: ").append(options.payloadSizes()).append('\n')
                .append("Maximum operations per interval: ").append(options.maxOperations()).append('\n')
                .append("Payload byte budget per interval: ").append(options.byteBudget()).append('\n')
                .append("Adaptive operations: max(1, min(max operations, byte budget / payload bytes))\n")
                .append("Workloads: ").append(options.workloads()).append('\n')
                .append("Allocation measurement: ").append(AllocationMeter.currentThread().available()).append('\n')
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (MvccPageCodecMeasurement measurement : measurements) {
            String allocated = measurement.allocationMeasurementAvailable()
                    ? String.format(Locale.ROOT, "%12.3f", measurement.allocatedBytesPerOperation())
                    : "         n/a";
            output.append(String.format(Locale.ROOT,
                    "%-24s payload=%-7d encoded=%-7d ops=%-7d run=%d rate=%12.3f "
                            + "avg-ns=%12.3f alloc-B/op=%s checksum=%d%n",
                    measurement.workload(),
                    measurement.payloadBytes(),
                    measurement.encodedBytesPerOperation(),
                    measurement.measuredOperations(),
                    measurement.run(),
                    measurement.throughputPerSecond(),
                    measurement.averageLatencyNanos(),
                    allocated,
                    measurement.checksum()));
        }
        Files.writeString(
                options.reportDirectory().resolve("page-codec-summary.txt"),
                output,
                StandardCharsets.UTF_8);
    }

    private static void writeCsv(
            Options options,
            List<MvccPageCodecMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder();
        output.append("workload,payloadBytes,encodedBytesPerOperation,measuredOperations,warmups,iterations,")
                .append("elapsedNanos,throughputPerSecond,averageLatencyNanos,allocatedBytes,")
                .append("allocatedBytesPerOperation,allocationMeasurementAvailable,checksum,run\n");
        for (MvccPageCodecMeasurement measurement : measurements) {
            output.append(measurement.workload()).append(',')
                    .append(measurement.payloadBytes()).append(',')
                    .append(measurement.encodedBytesPerOperation()).append(',')
                    .append(measurement.measuredOperations()).append(',')
                    .append(measurement.warmups()).append(',')
                    .append(measurement.iterations()).append(',')
                    .append(measurement.elapsedNanos()).append(',')
                    .append(measurement.throughputPerSecond()).append(',')
                    .append(measurement.averageLatencyNanos()).append(',')
                    .append(measurement.allocatedBytes()).append(',')
                    .append(measurement.allocatedBytesPerOperation()).append(',')
                    .append(measurement.allocationMeasurementAvailable()).append(',')
                    .append(measurement.checksum()).append(',')
                    .append(measurement.run()).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("page-codec-results.csv"),
                output,
                StandardCharsets.UTF_8);
    }

    private static void writeJson(
            Options options,
            List<MvccPageCodecMeasurement> measurements) throws IOException {
        StringBuilder output = new StringBuilder("[\n");
        for (int index = 0; index < measurements.size(); index++) {
            MvccPageCodecMeasurement measurement = measurements.get(index);
            output.append("  {\n")
                    .append("    \"workload\": \"").append(measurement.workload()).append("\",\n")
                    .append("    \"payloadBytes\": ").append(measurement.payloadBytes()).append(",\n")
                    .append("    \"encodedBytesPerOperation\": ")
                    .append(measurement.encodedBytesPerOperation()).append(",\n")
                    .append("    \"measuredOperations\": ").append(measurement.measuredOperations()).append(",\n")
                    .append("    \"elapsedNanos\": ").append(measurement.elapsedNanos()).append(",\n")
                    .append("    \"throughputPerSecond\": ")
                    .append(measurement.throughputPerSecond()).append(",\n")
                    .append("    \"averageLatencyNanos\": ")
                    .append(measurement.averageLatencyNanos()).append(",\n")
                    .append("    \"allocatedBytes\": ").append(measurement.allocatedBytes()).append(",\n")
                    .append("    \"allocatedBytesPerOperation\": ")
                    .append(measurement.allocatedBytesPerOperation()).append(",\n")
                    .append("    \"allocationMeasurementAvailable\": ")
                    .append(measurement.allocationMeasurementAvailable()).append(",\n")
                    .append("    \"checksum\": ").append(measurement.checksum()).append(",\n")
                    .append("    \"run\": ").append(measurement.run()).append('\n')
                    .append("  }");
            if (index + 1 < measurements.size()) {
                output.append(',');
            }
            output.append('\n');
        }
        output.append("]\n");
        Files.writeString(
                options.reportDirectory().resolve("page-codec-results.json"),
                output,
                StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Options(
            Path reportDirectory,
            List<Integer> payloadSizes,
            int maxOperations,
            long byteBudget,
            int warmups,
            int iterations,
            int runs,
            EnumSet<MvccPageCodecMeasurement.Workload> workloads) {

        Options {
            payloadSizes = List.copyOf(Objects.requireNonNull(payloadSizes, "payloadSizes"));
            workloads = EnumSet.copyOf(Objects.requireNonNull(workloads, "workloads"));
        }

        void validate() {
            Objects.requireNonNull(reportDirectory, "reportDirectory");
            require(!payloadSizes.isEmpty(), "at least one payload size is required");
            for (int payloadSize : payloadSizes) {
                require(payloadSize > 0, "payload sizes must be positive: " + payloadSize);
            }
            require(maxOperations > 0, "maxOperations must be positive");
            require(byteBudget > 0L, "byteBudget must be positive");
            require(warmups >= 0, "warmups must not be negative");
            require(iterations == 1,
                    "iterations must currently be 1; each workload already measures one batched interval");
            require(runs > 0, "runs must be positive");
            require(!workloads.isEmpty(), "at least one workload is required");
        }

        int operationsFor(int payloadBytes) {
            long budgetOperations = Math.max(1L, byteBudget / payloadBytes);
            return (int) Math.max(1L, Math.min(maxOperations, budgetOperations));
        }
    }

    private record Case(MvccPageCodecMeasurement.Workload workload, int payloadBytes) {
    }

    private record Fixture(
            MvccRowPayload rowPayload,
            byte[] rowPayloadBytes,
            MvccVersionRecord versionRecord,
            byte[] versionRecordBytes,
            byte[] pageRecordBytes,
            MvccIndexTuple indexTuple,
            byte[] indexTupleBytes,
            MvccOverflowPayloadChunk overflowChunk,
            byte[] overflowChunkBytes,
            String recoveryRecord) {

        private Fixture {
            rowPayloadBytes = rowPayloadBytes.clone();
            versionRecordBytes = versionRecordBytes.clone();
            pageRecordBytes = pageRecordBytes.clone();
            indexTupleBytes = indexTupleBytes.clone();
            overflowChunkBytes = overflowChunkBytes.clone();
        }

        static Fixture create(int payloadBytes) {
            byte[] payload = payload(payloadBytes);
            MvccRowPayload rowPayload = new MvccRowPayload("row-" + payloadBytes, payload);
            byte[] rowPayloadBytes = MvccRowPayloadCodec.encode(rowPayload);
            MvccVersionRecord versionRecord = MvccPageCodecBenchmark.versionRecord(payload);
            byte[] versionRecordBytes = MvccVersionRecordCodec.encode(versionRecord);
            byte[] pageRecordBytes = MvccPageRecordCodec.encodeVersionRecord(versionRecord);
            MvccVersionLocator locator = new MvccVersionLocator(new DelosPageId(11L), 3);
            MvccIndexTuple indexTuple = MvccIndexTuple.active(
                    INDEX_NAME,
                    payload,
                    versionRecord.header().rowId(),
                    versionRecord.header().versionId(),
                    locator);
            byte[] indexTupleBytes = MvccIndexTupleCodec.encode(indexTuple);
            MvccOverflowPayloadChunk overflowChunk = new MvccOverflowPayloadChunk(
                    0,
                    1,
                    payload.length,
                    payload,
                    Optional.empty());
            byte[] overflowChunkBytes = MvccOverflowPayloadCodec.encodeChunk(overflowChunk);
            String recoveryRecord = encodeRecoveryRecord(versionRecord);
            require(rowPayload.equals(MvccRowPayloadCodec.decode(rowPayloadBytes)),
                    "row payload codec round trip changed the payload");
            require(versionRecord.equals(MvccVersionRecordCodec.decode(versionRecordBytes)),
                    "version record codec round trip changed the record");
            require(versionRecord.equals(MvccPageRecordCodec.decode(pageRecordBytes).versionRecord()),
                    "page record codec round trip changed the version record");
            require(indexTuple.equals(MvccIndexTupleCodec.decode(indexTupleBytes)),
                    "index tuple codec round trip changed the tuple");
            require(overflowChunk.equals(MvccOverflowPayloadCodec.decodeChunk(overflowChunkBytes)),
                    "overflow chunk codec round trip changed the chunk");
            require(versionRecord.equals(decodeRecoveryRecord(recoveryRecord)),
                    "recovery record codec composition changed the version record");
            return new Fixture(
                    rowPayload,
                    rowPayloadBytes,
                    versionRecord,
                    versionRecordBytes,
                    pageRecordBytes,
                    indexTuple,
                    indexTupleBytes,
                    overflowChunk,
                    overflowChunkBytes,
                    recoveryRecord);
        }
    }

    private static final class AllocationMeter {
        private final Object bean;
        private final Method getThreadAllocatedBytes;
        private final boolean available;

        private AllocationMeter(Object bean, Method getThreadAllocatedBytes, boolean available) {
            this.bean = bean;
            this.getThreadAllocatedBytes = getThreadAllocatedBytes;
            this.available = available;
        }

        static AllocationMeter currentThread() {
            try {
                Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
                Object bean = managementFactory.getMethod("getThreadMXBean").invoke(null);
                Class<?> allocationBeanType = Class.forName("com.sun.management.ThreadMXBean");
                if (!allocationBeanType.isInstance(bean)) {
                    return unavailable();
                }
                Method supported = allocationBeanType.getMethod("isThreadAllocatedMemorySupported");
                if (!Boolean.TRUE.equals(supported.invoke(bean))) {
                    return unavailable();
                }
                Method enabled = allocationBeanType.getMethod("isThreadAllocatedMemoryEnabled");
                if (!Boolean.TRUE.equals(enabled.invoke(bean))) {
                    Method setEnabled = allocationBeanType.getMethod(
                            "setThreadAllocatedMemoryEnabled", boolean.class);
                    setEnabled.invoke(bean, true);
                }
                Method getBytes = allocationBeanType.getMethod("getThreadAllocatedBytes", long.class);
                return new AllocationMeter(bean, getBytes, true);
            } catch (ClassNotFoundException | IllegalAccessException | InvocationTargetException
                    | NoSuchMethodException | SecurityException e) {
                return unavailable();
            }
        }

        private static AllocationMeter unavailable() {
            return new AllocationMeter(null, null, false);
        }

        boolean available() {
            return available;
        }

        long currentBytes() {
            if (!available) {
                return -1L;
            }
            try {
                return ((Long) getThreadAllocatedBytes.invoke(bean, Thread.currentThread().threadId())).longValue();
            } catch (IllegalAccessException | InvocationTargetException e) {
                return -1L;
            }
        }

        long delta(long start, long end) {
            if (start < 0L || end < start) {
                return -1L;
            }
            return end - start;
        }
    }
}
