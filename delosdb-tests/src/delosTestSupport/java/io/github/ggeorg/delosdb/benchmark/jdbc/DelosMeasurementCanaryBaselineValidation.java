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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Validates current external-engine canaries against the frozen Phase 0C baseline. */
public final class DelosMeasurementCanaryBaselineValidation {
    private static final String PREFIX = "delosdb.benchmark.measurementValidity.";
    private static final int EXPECTED_CANARIES = 7;

    private DelosMeasurementCanaryBaselineValidation() {
    }

    public static void main(String[] args) throws Exception {
        Path baselineFile = Path.of(required(PREFIX + "baselineFile"));
        Path captureFile = Path.of(required(PREFIX + "captureFile"));
        Path reportDirectory = Path.of(required(PREFIX + "baselineValidationReportDirectory"));
        Files.createDirectories(reportDirectory);

        List<Row> baseline = readRows(baselineFile, false);
        List<Row> current = readRows(captureFile, true);
        validateCoverage(baseline, "baseline");
        validateCoverage(current, "current capture");
        validateFrozenBaselineQuality(baseline);

        Map<String, Row> currentByKey = index(current, "current capture");
        List<Decision> decisions = new ArrayList<>();
        for (Row frozen : baseline) {
            Row now = currentByKey.get(frozen.key());
            if (now == null) {
                throw new IllegalStateException("Missing current canary " + frozen.key());
            }
            decisions.add(compare(frozen, now));
        }

        DelosMeasurementValidityContract.Status finalStatus = decisions.stream()
                .map(Decision::status)
                .reduce(DelosMeasurementValidityContract.Status.VALID,
                        DelosMeasurementValidityContract.Status::worst);
        String baselineSha256 = sha256(baselineFile);
        writeReports(reportDirectory, baselineFile, captureFile, baselineSha256, decisions, finalStatus);

        if (finalStatus == DelosMeasurementValidityContract.Status.INVALID) {
            throw new IllegalStateException(
                    "Measurement validity gate is INVALID; inspect "
                            + reportDirectory.resolve("measurement-validity-gate.txt"));
        }
        System.out.println("DelosDB measurement validity gate completed with status " + finalStatus + ": "
                + reportDirectory.resolve("measurement-validity-gate.txt"));
    }

    private static Decision compare(Row frozen, Row now) {
        boolean identityMatch = frozen.shape().equals(now.shape())
                && frozen.metric().equals(now.metric())
                && frozen.product().equals(now.product())
                && frozen.productVersion().equals(now.productVersion())
                && frozen.driverVersion().equals(now.driverVersion());
        DelosMeasurementValidityContract.Status identityStatus = identityMatch
                ? DelosMeasurementValidityContract.Status.VALID
                : DelosMeasurementValidityContract.Status.INVALID;

        DelosMeasurementValidityContract.DispersionDecision dispersion =
                DelosMeasurementValidityContract.classifyCustom(now.iqrToMedian(), now.madToMedian());
        DelosMeasurementValidityContract.CanarySampleDecision sample =
                DelosMeasurementValidityContract.classifyCanarySample(
                        now.runs(), secondsToNanos(now.minElapsedSeconds()));
        DelosMeasurementValidityContract.CanaryDecision drift =
                DelosMeasurementValidityContract.classifyCanary(frozen.median(), now.median());
        DelosMeasurementValidityContract.Status status = DelosMeasurementValidityContract.combine(
                identityStatus, dispersion.status(), sample.status(), drift.status());
        return new Decision(frozen, now, identityMatch, drift.absoluteDriftRatio(), status);
    }

    private static void validateFrozenBaselineQuality(List<Row> baseline) {
        for (Row row : baseline) {
            DelosMeasurementValidityContract.Status status = DelosMeasurementValidityContract.combine(
                    DelosMeasurementValidityContract.classifyCustom(
                            row.iqrToMedian(), row.madToMedian()).status(),
                    DelosMeasurementValidityContract.classifyCanarySample(
                            row.runs(), secondsToNanos(row.minElapsedSeconds())).status());
            if (status != DelosMeasurementValidityContract.Status.VALID) {
                throw new IllegalStateException("Frozen baseline is not VALID: " + row.key());
            }
        }
    }

    private static long secondsToNanos(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0.0d) {
            return -1L;
        }
        double nanos = seconds * 1_000_000_000.0d;
        if (nanos >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.round(nanos);
    }

    private static void validateCoverage(List<Row> rows, String label) {
        if (rows.size() != EXPECTED_CANARIES) {
            throw new IllegalStateException(
                    "Expected " + EXPECTED_CANARIES + " canaries in " + label + ", found " + rows.size());
        }
        long embedded = rows.stream().filter(row -> row.lane().equals("embedded")).count();
        long server = rows.stream().filter(row -> row.lane().equals("server")).count();
        if (embedded != 3 || server != 4) {
            throw new IllegalStateException(
                    "Unexpected lane coverage in " + label + ": embedded=" + embedded + ", server=" + server);
        }
        index(rows, label);
    }

    private static Map<String, Row> index(List<Row> rows, String label) {
        Map<String, Row> result = new LinkedHashMap<>();
        for (Row row : rows) {
            Row previous = result.put(row.key(), row);
            if (previous != null) {
                throw new IllegalStateException("Duplicate canary key in " + label + ": " + row.key());
            }
        }
        return Map.copyOf(result);
    }

    private static void writeReports(
            Path reportDirectory,
            Path baselineFile,
            Path captureFile,
            String baselineSha256,
            List<Decision> decisions,
            DelosMeasurementValidityContract.Status finalStatus) throws IOException {
        StringBuilder tsv = new StringBuilder(
                "lane\ttarget\tbaselineMedian\tcurrentMedian\tabsoluteDriftRatio\tidentityMatch\t"
                        + "currentIqrToMedian\tcurrentMadToMedian\truns\tminElapsedSeconds\tstatus\n");
        for (Decision decision : decisions) {
            tsv.append(decision.frozen().lane()).append('\t')
                    .append(decision.frozen().target()).append('\t')
                    .append(format(decision.frozen().median())).append('\t')
                    .append(format(decision.current().median())).append('\t')
                    .append(format(decision.absoluteDriftRatio())).append('\t')
                    .append(decision.identityMatch()).append('\t')
                    .append(format(decision.current().iqrToMedian())).append('\t')
                    .append(format(decision.current().madToMedian())).append('\t')
                    .append(decision.current().runs()).append('\t')
                    .append(format(decision.current().minElapsedSeconds())).append('\t')
                    .append(decision.status()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("measurement-validity-gate.tsv"),
                tsv.toString(), StandardCharsets.UTF_8);

        StringBuilder text = new StringBuilder()
                .append("DelosDB v1 measurement-validity gate\n")
                .append("===================================\n\n")
                .append("Frozen baseline: ").append(baselineFile).append('\n')
                .append("Frozen baseline SHA-256: ").append(baselineSha256).append('\n')
                .append("Current capture: ").append(captureFile).append('\n')
                .append("Policy: exact product/driver identity, current dispersion/sample classification, ")
                .append("and <= 20% absolute median drift.\n")
                .append("VALID permits normal conclusions; NOISY permits only effects materially larger than observed noise; ")
                .append("INVALID forbids architecture-performance conclusions.\n")
                .append("Generated: ").append(Instant.now()).append("\n\n")
                .append("Canaries:\n");
        for (Decision decision : decisions) {
            text.append("- ").append(decision.frozen().key())
                    .append(" baseline=").append(format(decision.frozen().median()))
                    .append(" current=").append(format(decision.current().median()))
                    .append(" drift=").append(format(decision.absoluteDriftRatio()))
                    .append(" identity=").append(decision.identityMatch() ? "MATCH" : "MISMATCH")
                    .append(" dispersion=")
                    .append(format(Math.max(decision.current().iqrToMedian(), decision.current().madToMedian())))
                    .append(" runs=").append(decision.current().runs())
                    .append(" minElapsedSeconds=").append(format(decision.current().minElapsedSeconds()))
                    .append(" status=").append(decision.status()).append('\n');
        }
        text.append("\nFinal measurement-validity status: ").append(finalStatus).append('\n')
                .append(switch (finalStatus) {
                    case VALID -> "Decision: environment is valid for architecture-performance conclusions.\n";
                    case NOISY -> "Decision: environment is noisy; only effects materially larger than observed noise "
                            + "may support architecture-performance conclusions.\n";
                    case INVALID -> "Decision: do not draw architecture-performance conclusions from this environment.\n";
                });
        Files.writeString(reportDirectory.resolve("measurement-validity-gate.txt"),
                text.toString(), StandardCharsets.UTF_8);

        Properties manifest = new Properties();
        manifest.setProperty("generated", Instant.now().toString());
        manifest.setProperty("baseline.sha256", baselineSha256);
        manifest.setProperty("baseline.file", baselineFile.toString());
        manifest.setProperty("capture.file", captureFile.toString());
        manifest.setProperty("validity.status", finalStatus.name());
        manifest.setProperty("jdk.runtime.version", System.getProperty("java.runtime.version", ""));
        manifest.setProperty("os.name", System.getProperty("os.name", ""));
        manifest.setProperty("os.version", System.getProperty("os.version", ""));
        manifest.setProperty("os.arch", System.getProperty("os.arch", ""));
        manifest.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
        for (Decision decision : decisions) {
            String prefix = "canary." + decision.frozen().lane() + "." + decision.frozen().target() + ".";
            manifest.setProperty(prefix + "product", decision.current().product());
            manifest.setProperty(prefix + "productVersion", decision.current().productVersion());
            manifest.setProperty(prefix + "driverVersion", decision.current().driverVersion());
            manifest.setProperty(prefix + "baselineMedian", format(decision.frozen().median()));
            manifest.setProperty(prefix + "currentMedian", format(decision.current().median()));
            manifest.setProperty(prefix + "absoluteDriftRatio", format(decision.absoluteDriftRatio()));
            manifest.setProperty(prefix + "status", decision.status().name());
        }
        try (var writer = Files.newBufferedWriter(
                reportDirectory.resolve("measurement-experiment-manifest.properties"), StandardCharsets.UTF_8)) {
            manifest.store(writer, "DelosDB Phase 0C measurement validity manifest");
        }
    }

    private static List<Row> readRows(Path path, boolean allowStatusColumn) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing canary TSV: " + path);
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String headerLine = lines.stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing TSV header: " + path));
        String[] headers = headerLine.split("\\t", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < headers.length; index++) {
            columns.put(headers[index], index);
        }
        for (String required : List.of(
                "lane", "target", "shape", "metric", "product", "productVersion", "driverVersion",
                "median", "iqrToMedian", "madToMedian", "runs", "minElapsedSeconds")) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException("Missing column " + required + " in " + path);
            }
        }
        List<Row> rows = new ArrayList<>();
        boolean afterHeader = false;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (!afterHeader) {
                afterHeader = line.equals(headerLine);
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != headers.length) {
                throw new IllegalArgumentException("TSV field count mismatch in " + path + ": " + line);
            }
            if (allowStatusColumn && columns.containsKey("status")) {
                String capturedStatus = fields[columns.get("status")];
                try {
                    DelosMeasurementValidityContract.Status.valueOf(capturedStatus);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalStateException("Current capture contains unknown status: " + line, exception);
                }
            }
            rows.add(new Row(
                    field(fields, columns, "lane"),
                    field(fields, columns, "target"),
                    field(fields, columns, "shape"),
                    field(fields, columns, "metric"),
                    field(fields, columns, "product"),
                    field(fields, columns, "productVersion"),
                    field(fields, columns, "driverVersion"),
                    Double.parseDouble(field(fields, columns, "median")),
                    Double.parseDouble(field(fields, columns, "iqrToMedian")),
                    Double.parseDouble(field(fields, columns, "madToMedian")),
                    Integer.parseInt(field(fields, columns, "runs")),
                    Double.parseDouble(field(fields, columns, "minElapsedSeconds"))));
        }
        return List.copyOf(rows);
    }

    private static String field(String[] fields, Map<String, Integer> columns, String name) {
        return fields[columns.get(name)];
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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

    private record Row(
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
            double minElapsedSeconds) {
        String key() {
            return lane + "/" + target;
        }
    }

    private record Decision(
            Row frozen,
            Row current,
            boolean identityMatch,
            double absoluteDriftRatio,
            DelosMeasurementValidityContract.Status status) {
    }
}
