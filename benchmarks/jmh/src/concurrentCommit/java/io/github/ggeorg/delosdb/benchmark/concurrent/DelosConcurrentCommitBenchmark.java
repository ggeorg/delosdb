/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Config;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Scenario;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentWorkload.RoundResult;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentWorkload.SemanticDigest;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Public-JDBC heap/MVCC concurrent reader-writer benchmark with JFR contention
 * and file-I/O evidence.
 *
 * <p>This is an external measurement lane. It changes no database setting and
 * imports no Derby or DelosDB implementation API.</p>
 */
public final class DelosConcurrentCommitBenchmark {
    private static final String FILE_WRITE_EVENT = "jdk.FileWrite";
    private static final String MONITOR_ENTER_EVENT = "jdk.JavaMonitorEnter";
    private static final String THREAD_PARK_EVENT = "jdk.ThreadPark";
    private static final String GC_PAUSE_EVENT = "jdk.GCPhasePause";

    private DelosConcurrentCommitBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromSystemProperties();
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        Files.createDirectories(config.outputDirectory());
        Files.createDirectories(config.databaseRoot());

        List<Scenario> scenarios = config.scenarios();
        List<Result> results = new ArrayList<>(scenarios.size());
        for (Scenario scenario : scenarios) {
            Result result = runScenario(config, scenario);
            results.add(result);
            System.out.println(result.humanLine());
        }
        if (results.size() != scenarios.size()) {
            throw new IllegalStateException("scenario result count mismatch: expected="
                    + scenarios.size() + ", actual=" + results.size());
        }

        writeCsv(config.outputDirectory().resolve("results.csv"), results);
        writeJson(config.outputDirectory().resolve("results.json"), config, results);
        writeHuman(config.outputDirectory().resolve("human.txt"), config, results);
    }

    private static Result runScenario(Config config, Scenario scenario) throws Exception {
        Path scenarioRoot = DelosConcurrentWorkload.createScenarioRoot(config.databaseRoot(), scenario);
        try (DelosConcurrentScenarioEnvironment environment =
                DelosConcurrentScenarioEnvironment.create(scenarioRoot, scenario)) {
            if (config.warmupTransactionsPerWriter() > 0 || config.warmupReadsPerReader() > 0) {
                DelosConcurrentWorkload.runRound(
                        environment,
                        scenario,
                        config.warmupTransactionsPerWriter(),
                        config.warmupReadsPerReader(),
                        DelosConcurrentWorkload.warmupInsertBase());
            }

            Path recordingFile = config.outputDirectory().resolve(scenario.fileStem() + ".jfr");
            Files.deleteIfExists(recordingFile);
            RoundResult round;
            try (Recording recording = new Recording()) {
                enableCurrentJfrEvents(recording);
                recording.start();
                round = DelosConcurrentWorkload.runRound(
                        environment,
                        scenario,
                        config.transactionsPerWriter(),
                        config.readsPerReader(),
                        DelosConcurrentWorkload.measurementInsertBase());
                recording.stop();
                recording.dump(recordingFile);
            }

            SemanticDigest digest = environment.verify(
                    config.warmupTransactionsPerWriter(),
                    config.transactionsPerWriter());
            JfrMetrics jfr = readJfrMetrics(recordingFile);
            if (!config.keepJfr()) {
                Files.deleteIfExists(recordingFile);
            }
            return Result.from(scenario, round, digest, jfr);
        } finally {
            DelosConcurrentWorkload.deleteScenarioRoot(scenarioRoot);
        }
    }

    private static void enableCurrentJfrEvents(Recording recording) {
        recording.enable(FILE_WRITE_EVENT).withThreshold(Duration.ZERO);
        recording.enable(MONITOR_ENTER_EVENT).withThreshold(Duration.ZERO);
        recording.enable(THREAD_PARK_EVENT).withThreshold(Duration.ZERO);
        recording.enable(GC_PAUSE_EVENT).withThreshold(Duration.ZERO);
    }

    private static JfrMetrics readJfrMetrics(Path recordingFile) throws IOException {
        long fileWriteCount = 0L;
        long fileWriteBytes = 0L;
        long fileWriteNanos = 0L;
        long monitorEnterCount = 0L;
        long monitorEnterNanos = 0L;
        long threadParkCount = 0L;
        long threadParkNanos = 0L;
        long gcPauseCount = 0L;
        long gcPauseNanos = 0L;
        try (RecordingFile recording = new RecordingFile(recordingFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String eventName = event.getEventType().getName();
                switch (eventName) {
                    case FILE_WRITE_EVENT -> {
                        fileWriteCount++;
                        fileWriteNanos += event.getDuration().toNanos();
                        if (event.hasField("bytesWritten")) {
                            fileWriteBytes += event.getLong("bytesWritten");
                        }
                    }
                    case MONITOR_ENTER_EVENT -> {
                        monitorEnterCount++;
                        monitorEnterNanos += event.getDuration().toNanos();
                    }
                    case THREAD_PARK_EVENT -> {
                        threadParkCount++;
                        threadParkNanos += event.getDuration().toNanos();
                    }
                    case GC_PAUSE_EVENT -> {
                        gcPauseCount++;
                        gcPauseNanos += event.getDuration().toNanos();
                    }
                    default -> {
                        // Only explicitly enabled events are summarized.
                    }
                }
            }
        }
        return new JfrMetrics(
                fileWriteCount,
                fileWriteBytes,
                fileWriteNanos,
                monitorEnterCount,
                monitorEnterNanos,
                threadParkCount,
                threadParkNanos,
                gcPauseCount,
                gcPauseNanos);
    }

    private static void writeCsv(Path path, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println(Result.csvHeader());
            for (Result result : results) {
                writer.println(result.csvLine());
            }
        }
    }

    private static void writeJson(Path path, Config config, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println("{");
            writer.println("  \"generatedAt\": \"" + jsonEscape(Instant.now().toString()) + "\",");
            writer.println("  \"javaVendor\": \""
                    + jsonEscape(System.getProperty("java.vendor")) + "\",");
            writer.println("  \"javaVersion\": \""
                    + jsonEscape(System.getProperty("java.version")) + "\",");
            writer.println("  \"osName\": \"" + jsonEscape(System.getProperty("os.name")) + "\",");
            writer.println("  \"osArch\": \"" + jsonEscape(System.getProperty("os.arch")) + "\",");
            writer.println("  \"availableProcessors\": "
                    + Runtime.getRuntime().availableProcessors() + ',');
            writer.println("  \"maxMemoryBytes\": " + Runtime.getRuntime().maxMemory() + ',');
            writer.println("  \"scenarioCount\": " + results.size() + ',');
            writer.println("  \"transactionsPerWriter\": " + config.transactionsPerWriter() + ',');
            writer.println("  \"warmupTransactionsPerWriter\": "
                    + config.warmupTransactionsPerWriter() + ',');
            writer.println("  \"readsPerReader\": " + config.readsPerReader() + ',');
            writer.println("  \"warmupReadsPerReader\": " + config.warmupReadsPerReader() + ',');
            writer.println("  \"databaseRoot\": \""
                    + jsonEscape(config.databaseRoot().toString()) + "\",");
            writer.println("  \"results\": [");
            for (int index = 0; index < results.size(); index++) {
                writer.print(results.get(index).json("    "));
                writer.println(index + 1 == results.size() ? "" : ",");
            }
            writer.println("  ]");
            writer.println("}");
        }
    }

    private static void writeHuman(Path path, Config config, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println("DelosDB concurrent reader-writer benchmark");
            writer.println("Java: " + System.getProperty("java.vendor") + ' '
                    + System.getProperty("java.version"));
            writer.println("OS: " + System.getProperty("os.name") + ' '
                    + System.getProperty("os.arch"));
            writer.println("Available processors: " + Runtime.getRuntime().availableProcessors());
            writer.println("Max memory bytes: " + Runtime.getRuntime().maxMemory());
            writer.println("Scenarios: " + results.size());
            writer.println("Transactions per writer: " + config.transactionsPerWriter());
            writer.println("Warmup transactions per writer: "
                    + config.warmupTransactionsPerWriter());
            writer.println("Reads per reader: " + config.readsPerReader());
            writer.println("Warmup reads per reader: " + config.warmupReadsPerReader());
            writer.println("Database root: " + config.databaseRoot());
            writer.println();
            for (Result result : results) {
                writer.println(result.humanLine());
            }
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    record JfrMetrics(
            long fileWriteCount,
            long fileWriteBytes,
            long fileWriteNanos,
            long monitorEnterCount,
            long monitorEnterNanos,
            long threadParkCount,
            long threadParkNanos,
            long gcPauseCount,
            long gcPauseNanos) {
    }

    record LatencyStats(
            long operations,
            long averageNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos) {
        static LatencyStats from(long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            long total = 0L;
            for (long value : sorted) {
                total += value;
            }
            return new LatencyStats(
                    sorted.length,
                    sorted.length == 0 ? 0L : total / sorted.length,
                    percentile(sorted, 0.50d),
                    percentile(sorted, 0.95d),
                    percentile(sorted, 0.99d),
                    sorted.length == 0 ? 0L : sorted[sorted.length - 1]);
        }

        private static long percentile(long[] sorted, double percentile) {
            if (sorted.length == 0) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }
    }

    record Result(
            Scenario scenario,
            double writerOperationsPerSecond,
            double readerOperationsPerSecond,
            long roundElapsedNanos,
            long writerElapsedNanos,
            long readerElapsedNanos,
            long overlapNanos,
            LatencyStats writerTransactions,
            LatencyStats commits,
            LatencyStats reads,
            SemanticDigest digest,
            JfrMetrics jfr) {
        static Result from(
                Scenario scenario,
                RoundResult round,
                SemanticDigest digest,
                JfrMetrics jfr) {
            LatencyStats writerTransactions = LatencyStats.from(
                    round.writerTransactionLatenciesNanos());
            LatencyStats commits = LatencyStats.from(round.commitLatenciesNanos());
            LatencyStats reads = LatencyStats.from(round.readLatenciesNanos());
            return new Result(
                    scenario,
                    throughput(writerTransactions.operations(), round.writerElapsedNanos()),
                    throughput(reads.operations(), round.readerElapsedNanos()),
                    round.elapsedNanos(),
                    round.writerElapsedNanos(),
                    round.readerElapsedNanos(),
                    overlap(round.writerElapsedNanos(), round.readerElapsedNanos()),
                    writerTransactions,
                    commits,
                    reads,
                    digest,
                    jfr);
        }

        static String csvHeader() {
            return "provider,topology,operation,writers,readerWorkload,readers,rowsPerTransaction,"
                    + "writerTransactions,writerOperationsPerSecond,roundMillis,writerElapsedMillis,"
                    + "readerElapsedMillis,overlapMillis,avgWriterTransactionMicros,"
                    + "p50WriterTransactionMicros,p95WriterTransactionMicros,"
                    + "p99WriterTransactionMicros,maxWriterTransactionMicros,"
                    + "avgCommitMicros,p50CommitMicros,p95CommitMicros,p99CommitMicros,maxCommitMicros,"
                    + "reads,readerOperationsPerSecond,avgReadMicros,p50ReadMicros,"
                    + "p95ReadMicros,p99ReadMicros,maxReadMicros,semanticRows,semanticChecksum,"
                    + "fileWriteEvents,fileWriteBytes,fileWriteMicros,monitorEnterEvents,"
                    + "monitorEnterMicros,threadParkEvents,threadParkMicros,gcPauseEvents,gcPauseMicros";
        }

        String csvLine() {
            return String.join(",",
                    scenario.provider().propertyValue(),
                    scenario.topology().propertyValue(),
                    scenario.operation().propertyValue(),
                    Integer.toString(scenario.writers()),
                    scenario.readerWorkload().propertyValue(),
                    Integer.toString(scenario.readers()),
                    Integer.toString(scenario.rowsPerTransaction()),
                    Long.toString(writerTransactions.operations()),
                    decimal(writerOperationsPerSecond),
                    decimal(millis(roundElapsedNanos)),
                    decimal(millis(writerElapsedNanos)),
                    decimal(millis(readerElapsedNanos)),
                    decimal(millis(overlapNanos)),
                    decimal(micros(writerTransactions.averageNanos())),
                    decimal(micros(writerTransactions.p50Nanos())),
                    decimal(micros(writerTransactions.p95Nanos())),
                    decimal(micros(writerTransactions.p99Nanos())),
                    decimal(micros(writerTransactions.maxNanos())),
                    decimal(micros(commits.averageNanos())),
                    decimal(micros(commits.p50Nanos())),
                    decimal(micros(commits.p95Nanos())),
                    decimal(micros(commits.p99Nanos())),
                    decimal(micros(commits.maxNanos())),
                    Long.toString(reads.operations()),
                    decimal(readerOperationsPerSecond),
                    decimal(micros(reads.averageNanos())),
                    decimal(micros(reads.p50Nanos())),
                    decimal(micros(reads.p95Nanos())),
                    decimal(micros(reads.p99Nanos())),
                    decimal(micros(reads.maxNanos())),
                    Long.toString(digest.rowCount()),
                    digest.checksumHex(),
                    Long.toString(jfr.fileWriteCount()),
                    Long.toString(jfr.fileWriteBytes()),
                    decimal(micros(jfr.fileWriteNanos())),
                    Long.toString(jfr.monitorEnterCount()),
                    decimal(micros(jfr.monitorEnterNanos())),
                    Long.toString(jfr.threadParkCount()),
                    decimal(micros(jfr.threadParkNanos())),
                    Long.toString(jfr.gcPauseCount()),
                    decimal(micros(jfr.gcPauseNanos())));
        }

        String humanLine() {
            return scenario.fileStem()
                    + " writes/s=" + decimal(writerOperationsPerSecond)
                    + " write-p95=" + decimal(micros(writerTransactions.p95Nanos())) + "us"
                    + " commit-p95=" + decimal(micros(commits.p95Nanos())) + "us"
                    + " reads/s=" + decimal(readerOperationsPerSecond)
                    + " read-p95=" + decimal(micros(reads.p95Nanos())) + "us"
                    + " overlap=" + decimal(millis(overlapNanos)) + "ms"
                    + " rows=" + digest.rowCount()
                    + " checksum=" + digest.checksumHex()
                    + " fileWrites=" + jfr.fileWriteCount()
                    + " fileBytes=" + jfr.fileWriteBytes()
                    + " monitorWait=" + decimal(micros(jfr.monitorEnterNanos())) + "us"
                    + " park=" + decimal(micros(jfr.threadParkNanos())) + "us"
                    + " gcPause=" + decimal(micros(jfr.gcPauseNanos())) + "us";
        }

        String json(String indent) {
            String next = indent + "  ";
            return indent + "{\n"
                    + next + "\"provider\": \"" + scenario.provider().propertyValue() + "\",\n"
                    + next + "\"topology\": \"" + scenario.topology().propertyValue() + "\",\n"
                    + next + "\"operation\": \"" + scenario.operation().propertyValue() + "\",\n"
                    + next + "\"writers\": " + scenario.writers() + ",\n"
                    + next + "\"readerWorkload\": \""
                    + scenario.readerWorkload().propertyValue() + "\",\n"
                    + next + "\"readers\": " + scenario.readers() + ",\n"
                    + next + "\"rowsPerTransaction\": " + scenario.rowsPerTransaction() + ",\n"
                    + next + "\"writerTransactions\": "
                    + writerTransactions.operations() + ",\n"
                    + next + "\"writerOperationsPerSecond\": "
                    + decimal(writerOperationsPerSecond) + ",\n"
                    + next + "\"roundElapsedNanos\": " + roundElapsedNanos + ",\n"
                    + next + "\"writerElapsedNanos\": " + writerElapsedNanos + ",\n"
                    + next + "\"readerElapsedNanos\": " + readerElapsedNanos + ",\n"
                    + next + "\"overlapNanos\": " + overlapNanos + ",\n"
                    + latencyJson(next, "writerTransaction", writerTransactions) + ",\n"
                    + latencyJson(next, "commit", commits) + ",\n"
                    + next + "\"reads\": " + reads.operations() + ",\n"
                    + next + "\"readerOperationsPerSecond\": "
                    + decimal(readerOperationsPerSecond) + ",\n"
                    + latencyJson(next, "read", reads) + ",\n"
                    + next + "\"semanticRows\": " + digest.rowCount() + ",\n"
                    + next + "\"semanticChecksum\": \"" + digest.checksumHex() + "\",\n"
                    + next + "\"fileWriteEvents\": " + jfr.fileWriteCount() + ",\n"
                    + next + "\"fileWriteBytes\": " + jfr.fileWriteBytes() + ",\n"
                    + next + "\"fileWriteNanos\": " + jfr.fileWriteNanos() + ",\n"
                    + next + "\"monitorEnterEvents\": " + jfr.monitorEnterCount() + ",\n"
                    + next + "\"monitorEnterNanos\": " + jfr.monitorEnterNanos() + ",\n"
                    + next + "\"threadParkEvents\": " + jfr.threadParkCount() + ",\n"
                    + next + "\"threadParkNanos\": " + jfr.threadParkNanos() + ",\n"
                    + next + "\"gcPauseEvents\": " + jfr.gcPauseCount() + ",\n"
                    + next + "\"gcPauseNanos\": " + jfr.gcPauseNanos() + "\n"
                    + indent + "}";
        }

        private static String latencyJson(String indent, String prefix, LatencyStats stats) {
            return indent + "\"average" + capitalize(prefix) + "Nanos\": " + stats.averageNanos() + ",\n"
                    + indent + "\"p50" + capitalize(prefix) + "Nanos\": " + stats.p50Nanos() + ",\n"
                    + indent + "\"p95" + capitalize(prefix) + "Nanos\": " + stats.p95Nanos() + ",\n"
                    + indent + "\"p99" + capitalize(prefix) + "Nanos\": " + stats.p99Nanos() + ",\n"
                    + indent + "\"max" + capitalize(prefix) + "Nanos\": " + stats.maxNanos();
        }

        private static String capitalize(String value) {
            return Character.toUpperCase(value.charAt(0)) + value.substring(1);
        }

        private static long overlap(long writerElapsedNanos, long readerElapsedNanos) {
            return writerElapsedNanos == 0L || readerElapsedNanos == 0L
                    ? 0L
                    : Math.min(writerElapsedNanos, readerElapsedNanos);
        }

        private static double throughput(long operations, long elapsedNanos) {
            return operations == 0L || elapsedNanos == 0L
                    ? 0.0d
                    : operations / (elapsedNanos / 1_000_000_000.0d);
        }

        private static double micros(double nanos) {
            return nanos / 1_000.0d;
        }

        private static double millis(double nanos) {
            return nanos / 1_000_000.0d;
        }

        private static String decimal(double value) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
    }
}
