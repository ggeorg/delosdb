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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Phase-1 executable bridge for the nine architecture-fitness sentinels that
 * already have production-quality measurement surfaces.
 *
 * <p>The bridge does not implement another benchmark engine. It freezes the
 * nine REUSE_NOW cases, maps them to the existing cross-engine concurrency
 * runner, requires Phase-0A SQL-authoritative oracle evidence, and emits one
 * common result schema across embedded and server lanes.</p>
 */
public final class DelosArchitectureFitnessReuseNowSuite {
    private static final String PREFIX = "delosdb.benchmark.fitnessReuseNow.";
    private static final int ROW_COUNT = 10_000;
    private static final Set<Integer> CLIENTS = Set.of(1, 8);
    private static final List<String> EMBEDDED_TARGETS = List.of(
            "delos_heap", "delos_mvcc", "upstream_derby", "h2", "sqlite");
    private static final List<String> SERVER_TARGETS = List.of(
            "delos_heap_drda", "delos_mvcc_drda", "upstream_derby_drda",
            "h2_server", "postgresql", "mariadb");

    private DelosArchitectureFitnessReuseNowSuite() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("contract".equals(args[0]) || "report".equals(args[0]))) {
            throw new IllegalArgumentException("Expected exactly one argument: contract or report");
        }
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Path matrixTsv = Path.of(required(PREFIX + "matrixTsv"));
        Files.createDirectories(reportDirectory);
        validateFrozenMatrix(matrixTsv);
        writePlan(reportDirectory.resolve("architecture-fitness-reuse-now-plan.tsv"));
        writeContract(reportDirectory.resolve("architecture-fitness-reuse-now-contract.txt"));
        if ("contract".equals(args[0])) {
            System.out.println("DelosDB Phase-1 REUSE_NOW fitness contract passed: " + reportDirectory);
            return;
        }

        Path sourceRoot = Path.of(required(PREFIX + "sourceRoot"));
        Path measurementGate = Path.of(required(PREFIX + "measurementGate"));
        GateStatus environment = readMeasurementGate(measurementGate);
        List<FitnessRow> rows = new ArrayList<>();
        for (CaseSpec spec : cases()) {
            rows.addAll(readCase(sourceRoot, spec, environment));
        }
        validateCoverage(rows);
        writeResults(reportDirectory.resolve("architecture-fitness-reuse-now.tsv"), rows);
        GateStatus suite = writeSummary(
                reportDirectory.resolve("architecture-fitness-reuse-now.txt"), rows, environment);
        writeManifest(
                reportDirectory.resolve("architecture-fitness-reuse-now-manifest.properties"),
                rows, environment, suite, measurementGate);
        if (suite == GateStatus.INVALID) {
            throw new IllegalStateException(
                    "Phase-1 REUSE_NOW fitness run is INVALID; inspect "
                            + reportDirectory.resolve("architecture-fitness-reuse-now.txt"));
        }
        System.out.println("DelosDB Phase-1 REUSE_NOW fitness report complete: "
                + reportDirectory + " status=" + suite);
    }

    private static List<CaseSpec> cases() {
        return List.of(
                new CaseSpec("F01-PK-HOT-1", 1, "Simple indexed reads", "f01-hot",
                        "PRIMARY_KEY_READ_HOT", 1, "POINT_ROUND_TRIP"),
                new CaseSpec("F01-PK-DISJOINT-1", 1, "Simple indexed reads", "f01-disjoint",
                        "PRIMARY_KEY_READ_DISJOINT", 1, "POINT_ROUND_TRIP"),
                new CaseSpec("F01-PK-DISJOINT-10", 1, "Simple indexed reads", "f01-disjoint",
                        "PRIMARY_KEY_READ_DISJOINT", 10, "TRANSACTION_AMORTIZATION"),
                new CaseSpec("F02-RANGE-100", 2, "Range/index scans", "f02-range",
                        "RANGE_SCAN_100", 1, "RESULT_FETCH"),
                new CaseSpec("F02-RANGE-1000", 2, "Range/index scans", "f02-range",
                        "RANGE_SCAN_1000", 1, "RESULT_FETCH"),
                new CaseSpec("F02-FULL-10000", 2, "Range/index scans", "f02-range",
                        "RANGE_SCAN_FULL", 1, "RESULT_FETCH"),
                new CaseSpec("F02-INDEX-ONLY-1000", 2, "Range/index scans", "f02-range",
                        "RANGE_SCAN_INDEX_ONLY_1000", 1, "RESULT_FETCH"),
                new CaseSpec("F09-UPDATE-DISJOINT", 9, "Indexed UPDATE", "f09-update",
                        "DISJOINT_INDEXED_UPDATE", 1, "WRITE_COMMIT"),
                new CaseSpec("F09-UPDATE-CONTENDED", 9, "Indexed UPDATE", "f09-update",
                        "CONTENDED_INDEXED_UPDATE", 1, "WRITE_COMMIT"));
    }

    private static void validateFrozenMatrix(Path matrixTsv) throws IOException {
        List<String> lines = Files.readAllLines(matrixTsv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().startsWith("caseId\tfamilyId\t")) {
            throw new IllegalStateException("Unexpected sentinel matrix TSV: " + matrixTsv);
        }
        Map<String, String[]> matrix = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                String[] fields = lines.get(index).split("\\t", -1);
                matrix.put(fields[0], fields);
            }
        }
        Set<String> expectedReuseNow = new LinkedHashSet<>();
        for (String[] fields : matrix.values()) {
            if (fields.length >= 5 && "REUSE_NOW".equals(fields[4])) {
                expectedReuseNow.add(fields[0]);
            }
        }
        Set<String> actual = new LinkedHashSet<>();
        for (CaseSpec spec : cases()) {
            actual.add(spec.caseId());
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel matrix row: " + spec.caseId());
            }
            if (!"REUSE_NOW".equals(fields[4]) || !"BOTH".equals(fields[3])) {
                throw new IllegalStateException("Unexpected frozen readiness/lane for " + spec.caseId());
            }
            if (Integer.parseInt(fields[1]) != spec.familyId()) {
                throw new IllegalStateException("Frozen family mismatch for " + spec.caseId());
            }
        }
        if (!actual.equals(expectedReuseNow)) {
            throw new IllegalStateException(
                    "Phase-1 REUSE_NOW case set drift: matrix=" + expectedReuseNow + ", suite=" + actual);
        }
    }

    private static void writePlan(Path output) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("caseId\tfamilyId\tworkloadFamily\treportGroup\tworkload\toperationsPerTransaction\t")
                .append("clients\trows\trequiredLanes\tsemanticAuthority\tserverProtocolClass\n");
        for (CaseSpec spec : cases()) {
            text.append(spec.caseId()).append('\t')
                    .append(spec.familyId()).append('\t')
                    .append(spec.family()).append('\t')
                    .append(spec.reportGroup()).append('\t')
                    .append(spec.workload()).append('\t')
                    .append(spec.operationsPerTransaction()).append('\t')
                    .append("1,8\t10000\tEMBEDDED,SERVER\tPHASE_0A_SQL_ORACLE\t")
                    .append(spec.protocolClass()).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static void writeContract(Path output) throws IOException {
        String text = """
                DelosDB Phase-1 REUSE_NOW architecture-fitness contract
                =======================================================

                Frozen cases: 9
                Families represented: 1 Simple indexed reads, 2 Range/index scans, 9 Indexed UPDATE
                Lanes: embedded and server
                Rows: 10000
                Clients: 1 and 8
                Semantic authority: Phase-0A DelosSqlSemanticOracle over JDBC SQL-visible state.
                Performance substrate: existing DelosJdbcCrossEngineConcurrency; no second benchmark engine.
                Measurement validity: consume Phase-0C VALID/NOISY/INVALID gate; INVALID forbids conclusions.
                Dispersion: <=5% VALID, >5%-15% NOISY, >15% INVALID using max(IQR/median, MAD/median).
                Server protocol: protocol class is mandatory in the common schema; wire-level round-trip/fetch/byte
                instrumentation remains a separate Phase-1 infrastructure tranche and is not fabricated here.
                Optimization freeze: this suite changes no production performance behavior.
                """;
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static List<FitnessRow> readCase(
            Path sourceRoot,
            CaseSpec spec,
            GateStatus environment) throws IOException {
        List<FitnessRow> rows = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            Path leaf = sourceRoot.resolve(lane.directory()).resolve(spec.reportGroup());
            Path dispersion = leaf.resolve("cross-engine-concurrency-dispersion.csv");
            Path oracle = leaf.resolve("sql-semantic-oracle.csv");
            Map<Shape, String> oracleFingerprints = readOracle(oracle);
            List<String> lines = Files.readAllLines(dispersion, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith(
                    "rowCount,workload,clients,operationsPerTransaction,target,runs,")) {
                throw new IllegalStateException("Unexpected dispersion CSV: " + dispersion);
            }
            Set<String> expectedTargets = new LinkedHashSet<>(lane == Lane.EMBEDDED
                    ? EMBEDDED_TARGETS : SERVER_TARGETS);
            Set<String> seenTargets = new LinkedHashSet<>();
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) {
                    continue;
                }
                String[] fields = lines.get(index).split(",", -1);
                if (fields.length < 23) {
                    throw new IllegalStateException("Unexpected dispersion row: " + lines.get(index));
                }
                int rowCount = Integer.parseInt(fields[0]);
                String workload = fields[1];
                int clients = Integer.parseInt(fields[2]);
                int operations = Integer.parseInt(fields[3]);
                String target = fields[4];
                if (rowCount != ROW_COUNT || !workload.equals(spec.workload())
                        || operations != spec.operationsPerTransaction() || !CLIENTS.contains(clients)) {
                    continue;
                }
                if (!expectedTargets.contains(target)) {
                    throw new IllegalStateException("Unexpected target " + target + " in " + dispersion);
                }
                seenTargets.add(target);
                Shape shape = new Shape(rowCount, workload, clients, operations);
                String fingerprint = oracleFingerprints.get(shape);
                if (fingerprint == null) {
                    throw new IllegalStateException(
                            "Missing Phase-0A SQL oracle evidence for " + shape + " in " + oracle);
                }
                double iqr = Double.parseDouble(fields[20]);
                double mad = Double.parseDouble(fields[21]);
                double governing = Math.max(iqr, mad);
                GateStatus caseStatus = GateStatus.fromDispersion(governing);
                GateStatus finalStatus = GateStatus.worst(environment, caseStatus);
                rows.add(new FitnessRow(
                        spec, lane, target, clients, Integer.parseInt(fields[5]),
                        Double.parseDouble(fields[13]), iqr, mad, governing,
                        caseStatus, environment, finalStatus, fingerprint,
                        lane == Lane.SERVER ? "CLASSIFIED_NOT_WIRE_INSTRUMENTED" : "NOT_APPLICABLE"));
            }
            if (!seenTargets.equals(expectedTargets)) {
                throw new IllegalStateException(
                        "Target coverage mismatch for " + spec.caseId() + " " + lane
                                + ": expected=" + expectedTargets + ", actual=" + seenTargets);
            }
        }
        return List.copyOf(rows);
    }

    private static Map<Shape, String> readOracle(Path oracle) throws IOException {
        List<String> lines = Files.readAllLines(oracle, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(
                "target,workload,clients,operationsPerTransaction,rowCount,kind,count,fingerprint,run")) {
            throw new IllegalStateException("Unexpected Phase-0A oracle CSV: " + oracle);
        }
        Map<Shape, String> values = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            String[] fields = lines.get(index).split(",", -1);
            Shape shape = new Shape(
                    Integer.parseInt(fields[4]), fields[1], Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]));
            String prior = values.putIfAbsent(shape, fields[7]);
            if (prior != null && !prior.equals(fields[7])) {
                throw new IllegalStateException("Phase-0A oracle drift for " + shape);
            }
        }
        return Map.copyOf(values);
    }

    private static GateStatus readMeasurementGate(Path gate) throws IOException {
        for (String line : Files.readAllLines(gate, StandardCharsets.UTF_8)) {
            String prefix = "Final measurement-validity status: ";
            if (line.startsWith(prefix)) {
                return GateStatus.valueOf(line.substring(prefix.length()).trim());
            }
        }
        throw new IllegalStateException("Measurement-validity status not found: " + gate);
    }

    private static void validateCoverage(List<FitnessRow> rows) {
        int expectedPerCase = CLIENTS.size() * (EMBEDDED_TARGETS.size() + SERVER_TARGETS.size());
        int expected = cases().size() * expectedPerCase;
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "REUSE_NOW fitness row count mismatch: expected=" + expected + ", actual=" + rows.size());
        }
        Map<String, Integer> counts = new HashMap<>();
        for (FitnessRow row : rows) {
            counts.merge(row.spec().caseId(), 1, Integer::sum);
        }
        for (CaseSpec spec : cases()) {
            if (!Objects.equals(counts.get(spec.caseId()), expectedPerCase)) {
                throw new IllegalStateException(
                        "Incomplete coverage for " + spec.caseId() + ": " + counts.get(spec.caseId()));
            }
        }
    }

    private static void writeResults(Path output, List<FitnessRow> rows) throws IOException {
        List<FitnessRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((FitnessRow row) -> row.spec().caseId())
                .thenComparing(row -> row.lane().name())
                .thenComparingInt(FitnessRow::clients)
                .thenComparing(FitnessRow::target));
        StringBuilder text = new StringBuilder();
        text.append("caseId\tfamilyId\tworkloadFamily\tlane\ttarget\tclients\toperationsPerTransaction\t")
                .append("medianOperationsPerSecond\tiqrToMedian\tmadToMedian\tgoverningDispersion\t")
                .append("caseStatus\tenvironmentStatus\tfinalStatus\tsqlOracleFingerprint\t")
                .append("serverProtocolClass\tprotocolEvidenceStatus\n");
        for (FitnessRow row : ordered) {
            text.append(row.spec().caseId()).append('\t')
                    .append(row.spec().familyId()).append('\t')
                    .append(row.spec().family()).append('\t')
                    .append(row.lane()).append('\t')
                    .append(row.target()).append('\t')
                    .append(row.clients()).append('\t')
                    .append(row.spec().operationsPerTransaction()).append('\t')
                    .append(format(row.medianOps())).append('\t')
                    .append(format(row.iqr())).append('\t')
                    .append(format(row.mad())).append('\t')
                    .append(format(row.governing())).append('\t')
                    .append(row.caseStatus()).append('\t')
                    .append(row.environmentStatus()).append('\t')
                    .append(row.finalStatus()).append('\t')
                    .append(row.oracleFingerprint()).append('\t')
                    .append(row.spec().protocolClass()).append('\t')
                    .append(row.protocolEvidenceStatus()).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static GateStatus writeSummary(
            Path output,
            List<FitnessRow> rows,
            GateStatus environment) throws IOException {
        GateStatus suite = environment;
        Map<GateStatus, Integer> counts = new LinkedHashMap<>();
        for (GateStatus status : GateStatus.values()) {
            counts.put(status, 0);
        }
        for (FitnessRow row : rows) {
            suite = GateStatus.worst(suite, row.finalStatus());
            counts.merge(row.finalStatus(), 1, Integer::sum);
        }
        StringBuilder text = new StringBuilder();
        text.append("DelosDB Phase-1 REUSE_NOW architecture fitness\n")
                .append("=============================================\n\n")
                .append("Cases: 9\n")
                .append("Families: 1, 2, 9\n")
                .append("Lanes: embedded + server\n")
                .append("Result rows: ").append(rows.size()).append('\n')
                .append("Semantic authority: Phase-0A JDBC SQL oracle; all cross-engine/run fingerprints matched.\n")
                .append("Measurement environment: ").append(environment).append('\n')
                .append("Rows VALID: ").append(counts.get(GateStatus.VALID)).append('\n')
                .append("Rows NOISY: ").append(counts.get(GateStatus.NOISY)).append('\n')
                .append("Rows INVALID: ").append(counts.get(GateStatus.INVALID)).append('\n')
                .append("Overall suite status: ").append(suite).append("\n\n")
                .append("Interpretation:\n")
                .append("- VALID: ordinary architecture-performance comparisons allowed.\n")
                .append("- NOISY: only effects materially larger than observed noise are decision-quality.\n")
                .append("- INVALID: no architecture-performance conclusion is allowed.\n")
                .append("- Server protocol classes are present in the schema; wire round-trip/fetch/byte instrumentation\n")
                .append("  is intentionally not fabricated and remains the next Phase-1 infrastructure tranche.\n");
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
        return suite;
    }

    private static void writeManifest(
            Path output,
            List<FitnessRow> rows,
            GateStatus environment,
            GateStatus suite,
            Path measurementGate) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("generated", Instant.now().toString());
        properties.setProperty("phase", "1");
        properties.setProperty("step", "6");
        properties.setProperty("case.count", "9");
        properties.setProperty("result.row.count", Integer.toString(rows.size()));
        properties.setProperty("rows", Integer.toString(ROW_COUNT));
        properties.setProperty("clients", "1,8");
        properties.setProperty("semantic.authority", "PHASE_0A_SQL_ORACLE");
        properties.setProperty("measurement.validity.file", measurementGate.toAbsolutePath().normalize().toString());
        properties.setProperty("measurement.validity.status", environment.name());
        properties.setProperty("suite.status", suite.name());
        properties.setProperty("server.protocol.schema", "CLASSIFIED_NOT_WIRE_INSTRUMENTED");
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "DelosDB Phase-1 REUSE_NOW fitness manifest");
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing required system property " + property);
        }
        return value;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private enum Lane {
        EMBEDDED("embedded"),
        SERVER("server");

        private final String directory;

        Lane(String directory) {
            this.directory = directory;
        }

        private String directory() {
            return directory;
        }
    }

    private enum GateStatus {
        VALID,
        NOISY,
        INVALID;

        private static GateStatus fromDispersion(double value) {
            if (!Double.isFinite(value) || value > 0.15) {
                return INVALID;
            }
            return value > 0.05 ? NOISY : VALID;
        }

        private static GateStatus worst(GateStatus left, GateStatus right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    private record CaseSpec(
            String caseId,
            int familyId,
            String family,
            String reportGroup,
            String workload,
            int operationsPerTransaction,
            String protocolClass) {
    }

    private record Shape(
            int rowCount,
            String workload,
            int clients,
            int operationsPerTransaction) {
    }

    private record FitnessRow(
            CaseSpec spec,
            Lane lane,
            String target,
            int clients,
            int runs,
            double medianOps,
            double iqr,
            double mad,
            double governing,
            GateStatus caseStatus,
            GateStatus environmentStatus,
            GateStatus finalStatus,
            String oracleFingerprint,
            String protocolEvidenceStatus) {
    }
}
