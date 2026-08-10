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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Coordinates isolated engine JVMs and produces one semantic cross-engine report. */
public final class DelosJdbcCrossEngineCoordinator {
    private static final String PREFIX = "delosdb.benchmark.crossEngine.";
    private static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,outcome,operationsPerTransaction,"
                    + "transactionsPerInterval,rowCount,payloadSize,fixtureCommitBatchSize,warmups,"
                    + "iterations,measuredTransactions,measuredOperations,elapsedNanos,"
                    + "transactionsPerSecond,averageTransactionLatencyNanos,semanticFingerprint,run";

    private DelosJdbcCrossEngineCoordinator() {
    }

    public static void main(String[] args) throws Exception {
        if (!"false".equals(System.getProperty(PREFIX + "sane"))) {
            throw new IllegalStateException(
                    "Cross-engine performance comparison requires a release build; "
                            + "rerun with -Pdelosdb.sane=false");
        }
        Options options = Options.fromSystemProperties();
        options.validate();
        prepareOutput(options);

        for (int run = 1; run <= options.runs(); run++) {
            List<Target> targets = new ArrayList<>(List.of(Target.values()));
            if ((run & 1) == 0) {
                Collections.reverse(targets);
            }
            for (Target target : targets) {
                launch(options, target, run);
            }
        }

        List<Row> rows = loadRows(options);
        validate(options, rows);
        writeMergedCsv(options, rows);
        writeRatioCsv(options, rows);
        writeDispersionCsv(options, rows);
        writeSummary(options, rows);
    }

    private static void prepareOutput(Options options) throws IOException {
        deleteRecursively(options.reportDirectory());
        deleteRecursively(options.databaseRoot());
        Files.createDirectories(options.reportDirectory().resolve("workers"));
        Files.createDirectories(options.reportDirectory().resolve("logs"));
        Files.createDirectories(options.databaseRoot());
    }

    private static void launch(Options options, Target target, int run) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-Xms" + options.childHeap());
        command.add("-Xmx" + options.childHeap());
        command.add("-XX:+AlwaysPreTouch");
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        command.add("-D" + PREFIX + "target=" + target.id());
        command.add("-D" + PREFIX + "run=" + run);
        command.add("-D" + PREFIX + "databaseRoot=" + options.databaseRoot());
        command.add("-D" + PREFIX + "reportDirectory=" + options.reportDirectory().resolve("workers"));
        command.add("-D" + PREFIX + "rows=" + options.rows());
        command.add("-D" + PREFIX + "readWidths=" + options.readWidths());
        command.add("-D" + PREFIX + "writeWidths=" + options.writeWidths());
        command.add("-D" + PREFIX + "cycles=" + options.cycles());
        command.add("-D" + PREFIX + "payload=" + options.payload());
        command.add("-D" + PREFIX + "fixtureBatch=" + options.fixtureBatch());
        command.add("-D" + PREFIX + "warmups=" + options.warmups());
        command.add("-D" + PREFIX + "iterations=" + options.iterations());
        command.add(DelosJdbcCrossEngineWorker.class.getName());

        Path log = options.reportDirectory().resolve("logs")
                .resolve(String.format(Locale.ROOT, "%02d-%s.log", run, target.id()));
        Process process = new ProcessBuilder(command)
                .directory(options.projectDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        int status = process.waitFor();
        if (status != 0) {
            throw new IllegalStateException("Cross-engine worker failed: target=" + target.id()
                    + ", run=" + run + ", exit=" + status + ", log=" + log);
        }
    }

    private static List<Row> loadRows(Options options) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (Target target : Target.values()) {
                Path csv = options.reportDirectory().resolve("workers")
                        .resolve(target.id() + "-run-" + run + ".csv");
                List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !CSV_HEADER.equals(lines.getFirst())) {
                    throw new IllegalStateException("Unexpected cross-engine CSV header: " + csv);
                }
                for (int index = 1; index < lines.size(); index++) {
                    if (!lines.get(index).isBlank()) {
                        rows.add(Row.parse(lines.get(index)));
                    }
                }
            }
        }
        rows.sort(Comparator
                .comparingInt(Row::rowCount)
                .thenComparing(Row::workload)
                .thenComparing(Row::outcome)
                .thenComparingInt(Row::operationsPerTransaction)
                .thenComparing(Row::target)
                .thenComparingInt(Row::run));
        return List.copyOf(rows);
    }

    private static void validate(Options options, List<Row> rows) {
        int specs = 2
                + 2 * parseIntegerList(options.readWidths()).size()
                + 4 * parseIntegerList(options.writeWidths()).size()
                + 2;
        int expected = Target.values().length
                * options.runs()
                * parseIntegerList(options.rows()).size()
                * specs;
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "Cross-engine measurement count mismatch: expected=" + expected + ", actual=" + rows.size());
        }

        Map<SemanticKey, Long> semantics = new HashMap<>();
        for (Row row : rows) {
            SemanticKey key = new SemanticKey(
                    row.rowCount(),
                    row.workload(),
                    row.outcome(),
                    row.operationsPerTransaction());
            Long prior = semantics.putIfAbsent(key, row.semanticFingerprint());
            if (prior != null && prior.longValue() != row.semanticFingerprint()) {
                throw new IllegalStateException("Cross-engine semantic mismatch for " + key
                        + ": expected=" + prior + ", actual=" + row.semanticFingerprint()
                        + ", target=" + row.target() + ", run=" + row.run());
            }
        }
    }

    private static void writeMergedCsv(Options options, List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Row row : rows) {
            out.append(row.csv()).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("cross-engine-results.csv"),
                out.toString(),
                StandardCharsets.UTF_8);
    }

    private static void writeRatioCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medians(rows);
        StringBuilder out = new StringBuilder(
                "rowCount,workload,outcome,operationsPerTransaction,delosHeapMedianNanos,"
                        + "delosMvccMedianNanos,upstreamDerbyMedianNanos,h2MedianNanos,"
                        + "delosHeapToDerby,delosMvccToDerby,delosHeapToH2,delosMvccToH2\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            EnumMap<Target, Double> values = entry.getValue();
            double heap = require(values, Target.DELOS_HEAP, key);
            double mvcc = require(values, Target.DELOS_MVCC, key);
            double derby = require(values, Target.UPSTREAM_DERBY, key);
            double h2 = require(values, Target.H2, key);
            out.append(key.rowCount()).append(',')
                    .append(key.workload()).append(',')
                    .append(key.outcome()).append(',')
                    .append(key.operationsPerTransaction()).append(',')
                    .append(format(heap)).append(',')
                    .append(format(mvcc)).append(',')
                    .append(format(derby)).append(',')
                    .append(format(h2)).append(',')
                    .append(format(heap / derby)).append(',')
                    .append(format(mvcc / derby)).append(',')
                    .append(format(heap / h2)).append(',')
                    .append(format(mvcc / h2)).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("cross-engine-ratios.csv"),
                out.toString(),
                StandardCharsets.UTF_8);
    }

    private static void writeDispersionCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeTargetKey, Distribution> distributions = distributions(rows);
        StringBuilder out = new StringBuilder(
                "rowCount,workload,outcome,operationsPerTransaction,target,runs,medianNanos,"
                        + "q1Nanos,q3Nanos,iqrNanos,madNanos,minNanos,maxNanos,"
                        + "iqrToMedian,madToMedian,maxToMin\n");
        for (Map.Entry<ShapeTargetKey, Distribution> entry : distributions.entrySet()) {
            ShapeTargetKey key = entry.getKey();
            Distribution value = entry.getValue();
            out.append(key.shape().rowCount()).append(',')
                    .append(key.shape().workload()).append(',')
                    .append(key.shape().outcome()).append(',')
                    .append(key.shape().operationsPerTransaction()).append(',')
                    .append(key.target().id()).append(',')
                    .append(value.count()).append(',')
                    .append(format(value.median())).append(',')
                    .append(format(value.q1())).append(',')
                    .append(format(value.q3())).append(',')
                    .append(format(value.iqr())).append(',')
                    .append(format(value.mad())).append(',')
                    .append(format(value.min())).append(',')
                    .append(format(value.max())).append(',')
                    .append(format(ratio(value.iqr(), value.median()))).append(',')
                    .append(format(ratio(value.mad(), value.median()))).append(',')
                    .append(format(ratio(value.max(), value.min()))).append('\n');
        }
        Files.writeString(
                options.reportDirectory().resolve("cross-engine-dispersion.csv"),
                out.toString(),
                StandardCharsets.UTF_8);
    }

    private static void writeSummary(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medians(rows);
        Map<Target, Product> products = new EnumMap<>(Target.class);
        for (Row row : rows) {
            products.putIfAbsent(Target.parse(row.target()),
                    new Product(row.product(), row.productVersion(), row.driverVersion()));
        }

        StringBuilder out = new StringBuilder();
        out.append("DelosDB cross-engine JDBC transaction comparison\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Child JVM heap: ").append(options.childHeap()).append('\n')
                .append("DelosDB sane build: ").append(System.getProperty(PREFIX + "sane")).append('\n')
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Read widths: ").append(options.readWidths()).append('\n')
                .append("Write widths: ").append(options.writeWidths()).append('\n')
                .append("Transactions per interval: ").append(options.cycles()).append('\n')
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append('\n')
                .append("One isolated JVM per engine and run: true\n")
                .append("Engine order alternates by run: true\n")
                .append("Row-count order alternates by run: true\n")
                .append("Workload order alternates by run: true\n")
                .append("Even run count required for balanced order exposure: true\n")
                .append("Dispersion report: median, Q1, Q3, IQR, MAD, min, max, IQR/median, MAD/median, max/min\n")
                .append("Transaction timing includes commit or rollback: true\n")
                .append("Fixture setup and semantic restoration outside timing: true\n")
                .append("H2 durability setting: WRITE_DELAY=0\n")
                .append("External dependencies: upstream Derby ")
                .append(options.upstreamDerbyVersion()).append(", H2 ")
                .append(options.h2Version()).append("\n\n")
                .append("Observed products\n-----------------\n");
        for (Target target : Target.values()) {
            Product product = products.get(target);
            out.append(String.format(Locale.ROOT, "%-17s %s %s (driver %s)%n",
                    target.id(), product.name(), product.version(), product.driverVersion()));
        }

        out.append("\nMedian complete-transaction latency (microseconds)\n")
                .append("--------------------------------------------------\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            EnumMap<Target, Double> values = entry.getValue();
            double heap = require(values, Target.DELOS_HEAP, key);
            double mvcc = require(values, Target.DELOS_MVCC, key);
            double derby = require(values, Target.UPSTREAM_DERBY, key);
            double h2 = require(values, Target.H2, key);
            out.append(String.format(Locale.ROOT,
                    "rows=%-7d %-17s %-8s width=%-3d "
                            + "heap=%10.3f mvcc=%10.3f derby=%10.3f h2=%10.3f "
                            + "heap/derby=%7.3f mvcc/derby=%7.3f heap/h2=%7.3f mvcc/h2=%7.3f%n",
                    key.rowCount(),
                    key.workload(),
                    key.outcome(),
                    key.operationsPerTransaction(),
                    heap / 1_000.0,
                    mvcc / 1_000.0,
                    derby / 1_000.0,
                    h2 / 1_000.0,
                    heap / derby,
                    mvcc / derby,
                    heap / h2,
                    mvcc / h2));
        }
        Files.writeString(
                options.reportDirectory().resolve("cross-engine-summary.txt"),
                out.toString(),
                StandardCharsets.UTF_8);
    }

    private static Map<ShapeKey, EnumMap<Target, Double>> medians(List<Row> rows) {
        Map<ShapeKey, EnumMap<Target, Double>> medians = new java.util.LinkedHashMap<>();
        for (Map.Entry<ShapeTargetKey, Distribution> entry : distributions(rows).entrySet()) {
            ShapeTargetKey key = entry.getKey();
            medians.computeIfAbsent(key.shape(), ignored -> new EnumMap<>(Target.class))
                    .put(key.target(), entry.getValue().median());
        }
        return medians;
    }

    private static Map<ShapeTargetKey, Distribution> distributions(List<Row> rows) {
        Map<ShapeTargetKey, List<Double>> grouped = new HashMap<>();
        for (Row row : rows) {
            ShapeKey shape = new ShapeKey(
                    row.rowCount(), row.workload(), row.outcome(), row.operationsPerTransaction());
            grouped.computeIfAbsent(
                            new ShapeTargetKey(shape, Target.parse(row.target())),
                            ignored -> new ArrayList<>())
                    .add(row.averageTransactionLatencyNanos());
        }

        List<ShapeTargetKey> ordered = new ArrayList<>(grouped.keySet());
        ordered.sort(Comparator
                .comparing((ShapeTargetKey key) -> key.shape().rowCount())
                .thenComparing(key -> key.shape().workload())
                .thenComparing(key -> key.shape().outcome())
                .thenComparingInt(key -> key.shape().operationsPerTransaction())
                .thenComparing(ShapeTargetKey::target));

        Map<ShapeTargetKey, Distribution> result = new java.util.LinkedHashMap<>();
        for (ShapeTargetKey key : ordered) {
            result.put(key, distribution(grouped.get(key)));
        }
        return result;
    }

    private static Distribution distribution(List<Double> values) {
        List<Double> ordered = new ArrayList<>(values);
        ordered.sort(Double::compareTo);
        double median = median(ordered, 0, ordered.size());
        int middle = ordered.size() / 2;
        double q1 = median(ordered, 0, middle);
        double q3 = median(ordered, (ordered.size() + 1) / 2, ordered.size());
        List<Double> deviations = new ArrayList<>(ordered.size());
        for (double value : ordered) {
            deviations.add(Math.abs(value - median));
        }
        deviations.sort(Double::compareTo);
        return new Distribution(
                ordered.size(),
                median,
                q1,
                q3,
                q3 - q1,
                median(deviations, 0, deviations.size()),
                ordered.getFirst(),
                ordered.getLast());
    }

    private static double median(List<Double> ordered, int from, int to) {
        int size = to - from;
        if (size < 1) {
            throw new IllegalArgumentException("Median requires at least one value");
        }
        int middle = from + size / 2;
        if ((size & 1) == 1) {
            return ordered.get(middle);
        }
        return (ordered.get(middle - 1) + ordered.get(middle)) / 2.0;
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0.0 ? Double.NaN : numerator / denominator;
    }

    private static double require(EnumMap<Target, Double> values, Target target, ShapeKey key) {
        Double value = values.get(target);
        if (value == null) {
            throw new IllegalStateException("Missing " + target.id() + " measurement for " + key);
        }
        return value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static List<Integer> parseIntegerList(String raw) {
        List<Integer> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            values.add(Integer.parseInt(token.trim()));
        }
        return List.copyOf(values);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    enum Target {
        DELOS_HEAP("delos_heap"),
        DELOS_MVCC("delos_mvcc"),
        UPSTREAM_DERBY("upstream_derby"),
        H2("h2");

        private final String id;

        Target(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }

        static Target parse(String value) {
            for (Target target : values()) {
                if (target.id.equalsIgnoreCase(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException("Unknown target: " + value);
        }
    }

    private record Product(String name, String version, String driverVersion) {
    }

    private record SemanticKey(
            int rowCount,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
    }

    private record ShapeKey(
            int rowCount,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
    }

    private record ShapeTargetKey(ShapeKey shape, Target target) {
    }

    private record Distribution(
            int count,
            double median,
            double q1,
            double q3,
            double iqr,
            double mad,
            double min,
            double max) {
    }

    private record Row(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction,
            int transactionsPerInterval,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long elapsedNanos,
            double transactionsPerSecond,
            double averageTransactionLatencyNanos,
            long semanticFingerprint,
            int run) {
        static Row parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 20) {
                throw new IllegalArgumentException(
                        "Expected 20 cross-engine CSV fields, found " + fields.length + ": " + line);
            }
            return new Row(
                    fields[0],
                    fields[1],
                    fields[2],
                    fields[3],
                    DelosBenchmarkTransactionWorkload.valueOf(fields[4]),
                    DelosBenchmarkTransactionOutcome.valueOf(fields[5]),
                    Integer.parseInt(fields[6]),
                    Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]),
                    Integer.parseInt(fields[9]),
                    Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]),
                    Integer.parseInt(fields[12]),
                    Long.parseLong(fields[13]),
                    Long.parseLong(fields[14]),
                    Long.parseLong(fields[15]),
                    Double.parseDouble(fields[16]),
                    Double.parseDouble(fields[17]),
                    Long.parseLong(fields[18]),
                    Integer.parseInt(fields[19]));
        }

        String csv() {
            return String.join(",",
                    target,
                    product,
                    productVersion,
                    driverVersion,
                    workload.name(),
                    outcome.name(),
                    Integer.toString(operationsPerTransaction),
                    Integer.toString(transactionsPerInterval),
                    Integer.toString(rowCount),
                    Integer.toString(payloadSize),
                    Integer.toString(fixtureCommitBatchSize),
                    Integer.toString(warmups),
                    Integer.toString(iterations),
                    Long.toString(measuredTransactions),
                    Long.toString(measuredOperations),
                    Long.toString(elapsedNanos),
                    format(transactionsPerSecond),
                    format(averageTransactionLatencyNanos),
                    Long.toString(semanticFingerprint),
                    Integer.toString(run));
        }
    }

    private record Options(
            Path projectDirectory,
            Path javaExecutable,
            String benchmarkClasses,
            String delosClasspath,
            String upstreamDerbyClasspath,
            String h2Classpath,
            Path databaseRoot,
            Path reportDirectory,
            String rows,
            String readWidths,
            String writeWidths,
            int cycles,
            int payload,
            int fixtureBatch,
            int warmups,
            int iterations,
            int runs,
            String childHeap,
            String upstreamDerbyVersion,
            String h2Version) {
        static Options fromSystemProperties() {
            return new Options(
                    Path.of(required(PREFIX + "projectDirectory")),
                    Path.of(required(PREFIX + "javaExecutable")),
                    required(PREFIX + "benchmarkClasses"),
                    required(PREFIX + "delosClasspath"),
                    required(PREFIX + "upstreamDerbyClasspath"),
                    required(PREFIX + "h2Classpath"),
                    Path.of(required(PREFIX + "databaseRoot")),
                    Path.of(required(PREFIX + "reportDirectory")),
                    System.getProperty(PREFIX + "rows", "1000"),
                    System.getProperty(PREFIX + "readWidths", "1,10"),
                    System.getProperty(PREFIX + "writeWidths", "1,10"),
                    Integer.parseInt(System.getProperty(PREFIX + "cycles", "10")),
                    Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                    Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                    Integer.parseInt(System.getProperty(PREFIX + "warmups", "1")),
                    Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")),
                    Integer.parseInt(System.getProperty(PREFIX + "runs", "4")),
                    System.getProperty(PREFIX + "childHeap", "1g"),
                    required(PREFIX + "upstreamDerbyVersion"),
                    required(PREFIX + "h2Version"));
        }

        void validate() {
            Objects.requireNonNull(projectDirectory, "projectDirectory");
            Objects.requireNonNull(javaExecutable, "javaExecutable");
            Objects.requireNonNull(databaseRoot, "databaseRoot");
            Objects.requireNonNull(reportDirectory, "reportDirectory");
            if (!Files.isRegularFile(javaExecutable)) {
                throw new IllegalArgumentException("Java executable does not exist: " + javaExecutable);
            }
            if (benchmarkClasses.isBlank() || delosClasspath.isBlank()
                    || upstreamDerbyClasspath.isBlank() || h2Classpath.isBlank()) {
                throw new IllegalArgumentException("All cross-engine classpaths are required");
            }
            parsePositive(rows, "rows", 100);
            parsePositive(readWidths, "readWidths", 1);
            parsePositive(writeWidths, "writeWidths", 1);
            if (cycles < 1 || payload < 16 || fixtureBatch < 1
                    || warmups < 0 || iterations < 1) {
                throw new IllegalArgumentException("Invalid cross-engine benchmark numeric option");
            }
            if (runs < 2 || (runs & 1) != 0) {
                throw new IllegalArgumentException(
                        "runs must be an even number of at least 2 for balanced benchmark order");
            }
            if (childHeap.isBlank()) {
                throw new IllegalArgumentException("childHeap is required");
            }
        }

        String classpath(Target target) {
            return switch (target) {
                case DELOS_HEAP, DELOS_MVCC -> delosClasspath;
                case UPSTREAM_DERBY -> upstreamDerbyClasspath;
                case H2 -> h2Classpath;
            };
        }

        private static String required(String name) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required system property " + name);
            }
            return value;
        }

        private static void parsePositive(String raw, String name, int minimum) {
            for (int value : parseIntegerList(raw)) {
                if (value < minimum) {
                    throw new IllegalArgumentException(
                            name + " values must be at least " + minimum + ": " + value);
                }
            }
        }
    }
}
