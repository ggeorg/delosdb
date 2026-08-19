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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Extracts stable external-engine canaries from fixed-shape concurrency benchmark reports. */
public final class DelosMeasurementCanaryCapture {
    private static final String PREFIX = "delosdb.benchmark.measurementValidity.";
    private static final String SHAPE =
            "PRIMARY_KEY_READ_DISJOINT/clients=1/width=1/rows=10000";

    private DelosMeasurementCanaryCapture() {
    }

    public static void main(String[] args) throws Exception {
        Path embeddedDirectory = Path.of(required(PREFIX + "embeddedReportDirectory"));
        Path serverDirectory = Path.of(required(PREFIX + "serverReportDirectory"));
        Path outputDirectory = Path.of(required(PREFIX + "captureReportDirectory"));
        Files.createDirectories(outputDirectory);

        List<Canary> canaries = new ArrayList<>();
        canaries.addAll(readConcurrencyLane(
                "embedded", embeddedDirectory, List.of("upstream_derby", "h2", "sqlite")));
        canaries.addAll(readConcurrencyLane(
                "server", serverDirectory,
                List.of("upstream_derby_drda", "h2_server", "postgresql", "mariadb")));
        validateCoverage(canaries);
        writeCapture(outputDirectory, canaries, embeddedDirectory, serverDirectory);

        boolean baselineQuality = canaries.stream()
                .allMatch(canary -> canary.status() == DelosMeasurementValidityContract.Status.VALID);
        if (!baselineQuality) {
            throw new IllegalStateException(
                    "Reference-engine canary capture is not baseline quality; inspect "
                            + outputDirectory.resolve("measurement-canary-capture.txt"));
        }
        System.out.println("DelosDB measurement canary capture passed: "
                + outputDirectory.resolve("measurement-canary-capture.txt"));
    }

    private static List<Canary> readConcurrencyLane(
            String lane,
            Path directory,
            List<String> targets) throws IOException {
        Path dispersionCsv = directory.resolve("cross-engine-concurrency-dispersion.csv");
        Path resultsCsv = directory.resolve("cross-engine-concurrency-results.csv");
        List<Map<String, String>> dispersion = readCsv(dispersionCsv);
        List<Map<String, String>> results = readCsv(resultsCsv);
        List<Canary> canaries = new ArrayList<>();
        for (String target : targets) {
            Map<String, String> key = Map.of(
                    "rowCount", "10000",
                    "workload", "PRIMARY_KEY_READ_DISJOINT",
                    "clients", "1",
                    "operationsPerTransaction", "1",
                    "target", target);
            Map<String, String> row = unique(dispersion, key, dispersionCsv);
            List<Map<String, String>> samples = matches(results, key);
            if (samples.isEmpty()) {
                throw new IllegalStateException(
                        "Missing canary samples for " + key + " in " + resultsCsv);
            }
            canaries.add(canary(
                    lane,
                    target,
                    SHAPE,
                    "medianOpsPerSecond",
                    row,
                    samples,
                    "medianOpsPerSecond",
                    dispersionCsv));
        }
        return canaries;
    }

    private static Canary canary(
            String lane,
            String target,
            String shape,
            String metric,
            Map<String, String> row,
            List<Map<String, String>> samples,
            String medianColumn,
            Path source) {
        Map<String, String> identity = samples.get(0);
        double median = Double.parseDouble(row.get(medianColumn));
        double iqr = Double.parseDouble(row.get("iqrToMedian"));
        double mad = Double.parseDouble(row.get("madToMedian"));
        DelosMeasurementValidityContract.DispersionDecision dispersionDecision =
                DelosMeasurementValidityContract.classifyCustom(iqr, mad);

        long minElapsedNanos = samples.stream()
                .mapToLong(sample -> Long.parseLong(sample.get("elapsedNanos")))
                .min()
                .orElseThrow();
        DelosMeasurementValidityContract.CanarySampleDecision sampleDecision =
                DelosMeasurementValidityContract.classifyCanarySample(samples.size(), minElapsedNanos);
        DelosMeasurementValidityContract.Status status = DelosMeasurementValidityContract.combine(
                dispersionDecision.status(), sampleDecision.status());

        return new Canary(
                lane,
                target,
                shape,
                metric,
                identity.getOrDefault("product", ""),
                identity.getOrDefault("productVersion", ""),
                identity.getOrDefault("driverVersion", ""),
                median,
                iqr,
                mad,
                samples.size(),
                minElapsedNanos,
                status,
                source.toString());
    }

    private static void validateCoverage(List<Canary> values) {
        if (values.size() != 7) {
            throw new IllegalStateException("Expected 7 frozen-engine canaries, found " + values.size());
        }
        long embedded = values.stream().filter(value -> value.lane().equals("embedded")).count();
        long server = values.stream().filter(value -> value.lane().equals("server")).count();
        if (embedded != 3 || server != 4) {
            throw new IllegalStateException(
                    "Unexpected canary lane coverage: embedded=" + embedded + ", server=" + server);
        }
        for (Canary value : values) {
            if (value.product().isBlank() || value.productVersion().isBlank() || value.driverVersion().isBlank()) {
                throw new IllegalStateException("Incomplete canary product identity: " + value);
            }
        }
    }

    private static void writeCapture(
            Path outputDirectory,
            List<Canary> values,
            Path embeddedDirectory,
            Path serverDirectory) throws IOException {
        StringBuilder tsv = new StringBuilder(
                "lane\ttarget\tshape\tmetric\tproduct\tproductVersion\tdriverVersion\tmedian\t"
                        + "iqrToMedian\tmadToMedian\truns\tminElapsedSeconds\tstatus\tsource\n");
        for (Canary value : values) {
            tsv.append(value.lane()).append('\t')
                    .append(value.target()).append('\t')
                    .append(value.shape()).append('\t')
                    .append(value.metric()).append('\t')
                    .append(sanitize(value.product())).append('\t')
                    .append(sanitize(value.productVersion())).append('\t')
                    .append(sanitize(value.driverVersion())).append('\t')
                    .append(format(value.median())).append('\t')
                    .append(format(value.iqrToMedian())).append('\t')
                    .append(format(value.madToMedian())).append('\t')
                    .append(value.runs()).append('\t')
                    .append(format(value.minElapsedNanos() / 1_000_000_000.0d)).append('\t')
                    .append(value.status()).append('\t')
                    .append(value.source()).append('\n');
        }
        Files.writeString(outputDirectory.resolve("measurement-canary-capture.tsv"),
                tsv.toString(), StandardCharsets.UTF_8);

        StringBuilder text = new StringBuilder()
                .append("DelosDB v1 measurement-validity canary capture\n")
                .append("==============================================\n\n")
                .append("Purpose: capture candidate frozen reference-engine baselines; this file is not the frozen baseline yet.\n")
                .append("Baseline eligibility requires every canary to be VALID (<= 5% governing dispersion).\n")
                .append("Baseline eligibility also requires at least ")
                .append(DelosMeasurementValidityContract.MINIMUM_BASELINE_CANARY_RUNS)
                .append(" independent runs and at least ")
                .append(format(DelosMeasurementValidityContract.MINIMUM_BASELINE_CANARY_ELAPSED_NANOS
                        / 1_000_000_000.0d))
                .append(" measured seconds in every run.\n")
                .append("Product/driver identity is recorded and must remain compatible with the frozen baseline.\n")
                .append("Embedded source: ").append(embeddedDirectory).append('\n')
                .append("Server source: ").append(serverDirectory).append('\n')
                .append("Captured: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.runtime.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Processors: ").append(Runtime.getRuntime().availableProcessors()).append("\n\n")
                .append("Canaries:\n");
        for (Canary value : values) {
            text.append("- ").append(value.lane()).append('/').append(value.target())
                    .append(" [").append(value.product()).append(' ').append(value.productVersion()).append(']')
                    .append(" metric=").append(value.metric())
                    .append(" median=").append(format(value.median()))
                    .append(" iqr/median=").append(format(value.iqrToMedian()))
                    .append(" mad/median=").append(format(value.madToMedian()))
                    .append(" runs=").append(value.runs())
                    .append(" minElapsedSeconds=")
                    .append(format(value.minElapsedNanos() / 1_000_000_000.0d))
                    .append(" status=").append(value.status()).append('\n');
        }
        boolean baselineQuality = values.stream()
                .allMatch(value -> value.status() == DelosMeasurementValidityContract.Status.VALID);
        text.append("\nBaseline-quality capture: ").append(baselineQuality ? "YES" : "NO").append('\n')
                .append("Decision: ")
                .append(baselineQuality
                        ? "UPLOAD/FREEZE candidate values only after review."
                        : "DO NOT FREEZE; inspect noise/sample adequacy before rerunning.")
                .append('\n');
        Files.writeString(outputDirectory.resolve("measurement-canary-capture.txt"),
                text.toString(), StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing benchmark report: " + path);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() < 2) {
            throw new IllegalArgumentException("Empty benchmark report: " + path);
        }
        String[] headers = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int line = 1; line < lines.size(); line++) {
            if (lines.get(line).isBlank()) {
                continue;
            }
            String[] fields = lines.get(line).split(",", -1);
            if (fields.length != headers.length) {
                throw new IllegalArgumentException(
                        "CSV field count mismatch in " + path + " line " + (line + 1));
            }
            Map<String, String> row = new HashMap<>();
            for (int index = 0; index < headers.length; index++) {
                row.put(headers[index], fields[index]);
            }
            rows.add(Map.copyOf(row));
        }
        return List.copyOf(rows);
    }

    private static Map<String, String> unique(
            List<Map<String, String>> rows,
            Map<String, String> required,
            Path source) {
        List<Map<String, String>> matches = matches(rows, required);
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one canary row for " + required + " in " + source
                            + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private static List<Map<String, String>> matches(
            List<Map<String, String>> rows,
            Map<String, String> required) {
        return rows.stream()
                .filter(row -> required.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(row.get(entry.getKey()))))
                .toList();
    }

    private static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property " + name);
        }
        return value;
    }

    private record Canary(
            String lane,
            String target,
            String shape,
            String metric,
            String product,
            String productVersion,
            String driverVersion,
            double median,
            double iqrToMedian,
            double madToMedian,
            int runs,
            long minElapsedNanos,
            DelosMeasurementValidityContract.Status status,
            String source) {
    }
}
