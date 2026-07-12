/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Executable provider-neutral JDBC baseline runner. */
public final class DelosJdbcBenchmarkBaseline {
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcBenchmarkBaseline() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Files.createDirectories(options.reportDirectory());
        deleteRecursively(options.databaseRoot());

        List<DelosBenchmarkMeasurement> measurements = new ArrayList<>();
        Map<SemanticKey, DelosBenchmarkResult> firstRun = new java.util.HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : options.rowCounts()) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows, options.payloadSize(), SEED, Math.min(options.commitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : DelosBenchmarkProvider.values()) {
                    measurements.addAll(runProvider(options, config, provider, run, firstRun));
                }
            }
        }

        measurements.sort(Comparator
                .comparingInt(DelosBenchmarkMeasurement::rowCount)
                .thenComparing(DelosBenchmarkMeasurement::provider)
                .thenComparing(DelosBenchmarkMeasurement::operation)
                .thenComparingInt(DelosBenchmarkMeasurement::run));
        writeReports(options, measurements);
        System.out.println("DelosDB JDBC baseline complete: " + measurements.size()
                + " measurements in " + options.reportDirectory());
    }

    private static List<DelosBenchmarkMeasurement> runProvider(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, DelosBenchmarkResult> firstRun) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id() + "-" + config.rowCount() + "-run" + run);
        deleteRecursively(database);
        List<DelosBenchmarkMeasurement> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database + ";create=true")) {
            try {
                DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(connection, provider, config);
                scenario.prepare();
                for (DelosBenchmarkOperation operation : DelosBenchmarkOperation.values()) {
                    for (int warmup = 0; warmup < options.warmups(); warmup++) {
                        scenario.execute(operation);
                    }
                    long started = System.nanoTime();
                    DelosBenchmarkResult semantic = null;
                    for (int iteration = 0; iteration < options.iterations(); iteration++) {
                        semantic = scenario.execute(operation);
                    }
                    long elapsed = System.nanoTime() - started;
                    SemanticKey key = new SemanticKey(provider, operation, config.rowCount());
                    DelosBenchmarkResult prior = firstRun.putIfAbsent(key, semantic);
                    if (prior != null && !prior.equals(semantic)) {
                        throw new IllegalStateException("Non-reproducible benchmark semantics for " + key
                                + ": first=" + prior + ", run" + run + '=' + semantic);
                    }
                    result.add(new DelosBenchmarkMeasurement(
                            provider,
                            operation,
                            config.rowCount(),
                            config.payloadSize(),
                            config.commitBatchSize(),
                            options.warmups(),
                            options.iterations(),
                            elapsed,
                            options.iterations() * 1_000_000_000.0 / elapsed,
                            (double) elapsed / options.iterations(),
                            semantic.rowCount(),
                            semantic.checksum(),
                            run));
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        } finally {
            deleteRecursively(database);
        }
        return result;
    }

    private static void writeReports(Options options, List<DelosBenchmarkMeasurement> measurements)
            throws IOException {
        Files.writeString(options.reportDirectory().resolve("benchmark-results.csv"), csv(measurements), StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("benchmark-results.json"), json(measurements), StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("benchmark-summary.txt"), summary(options, measurements), StandardCharsets.UTF_8);
    }

    private static String csv(List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder("provider,operation,rowCount,payloadSize,commitBatchSize,warmups,iterations,elapsedNanos,operationsPerSecond,averageLatencyNanos,semanticRowCount,checksum,run\n");
        for (DelosBenchmarkMeasurement value : values) {
            out.append(value.provider().id()).append(',').append(value.operation()).append(',')
                    .append(value.rowCount()).append(',').append(value.payloadSize()).append(',')
                    .append(value.commitBatchSize()).append(',').append(value.warmups()).append(',')
                    .append(value.iterations()).append(',').append(value.elapsedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.operationsPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos())).append(',')
                    .append(value.semanticRowCount()).append(',').append(value.checksum()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < values.size(); i++) {
            DelosBenchmarkMeasurement value = values.get(i);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"operation\":\"").append(value.operation())
                    .append("\",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"commitBatchSize\":").append(value.commitBatchSize())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"elapsedNanos\":").append(value.elapsedNanos())
                    .append(",\"operationsPerSecond\":").append(String.format(Locale.ROOT, "%.6f", value.operationsPerSecond()))
                    .append(",\"averageLatencyNanos\":").append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos()))
                    .append(",\"semanticRowCount\":").append(value.semanticRowCount())
                    .append(",\"checksum\":").append(value.checksum())
                    .append(",\"run\":").append(value.run()).append('}');
            if (i + 1 < values.size()) out.append(',');
            out.append('\n');
        }
        return out.append("]\n").toString();
    }

    private static String summary(Options options, List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC performance baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Rows: ").append(options.rowCounts()).append('\n')
                .append("Payload: ").append(options.payloadSize()).append('\n')
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (DelosBenchmarkMeasurement value : values) {
            out.append(String.format(Locale.ROOT,
                    "%7d %-4s %-28s run=%d ops/s=%12.3f avg-ns=%12.3f rows=%d checksum=%d%n",
                    value.rowCount(), value.provider().id(), value.operation(), value.run(),
                    value.operationsPerSecond(), value.averageLatencyNanos(),
                    value.semanticRowCount(), value.checksum()));
        }
        return out.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private record SemanticKey(DelosBenchmarkProvider provider, DelosBenchmarkOperation operation, int rowCount) {
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            int payloadSize,
            int commitBatchSize,
            int warmups,
            int iterations,
            int runs) {
        static Options parse(String[] args) {
            Map<String, String> values = new java.util.HashMap<>();
            for (String arg : args) {
                int separator = arg.indexOf('=');
                if (!arg.startsWith("--") || separator < 3) {
                    throw new IllegalArgumentException("Expected --name=value but got " + arg);
                }
                values.put(arg.substring(2, separator), arg.substring(separator + 1));
            }
            String rowsValue = values.getOrDefault("rows", "100,1000,10000,100000");
            List<Integer> rows = java.util.Arrays.stream(rowsValue.split(","))
                    .map(String::trim).filter(value -> !value.isEmpty()).map(Integer::parseInt).toList();
            if (rows.isEmpty()) throw new IllegalArgumentException("At least one row count is required");
            return new Options(
                    Path.of(values.getOrDefault("databaseRoot", "build/tmp/delos-jdbc-baseline")),
                    Path.of(values.getOrDefault("reportDirectory", "build/reports/delosdb/benchmarks")),
                    rows,
                    Integer.parseInt(values.getOrDefault("payload", "128")),
                    Integer.parseInt(values.getOrDefault("batch", "100")),
                    Integer.parseInt(values.getOrDefault("warmups", "2")),
                    Integer.parseInt(values.getOrDefault("iterations", "5")),
                    Integer.parseInt(values.getOrDefault("runs", "2")));
        }
    }
}
