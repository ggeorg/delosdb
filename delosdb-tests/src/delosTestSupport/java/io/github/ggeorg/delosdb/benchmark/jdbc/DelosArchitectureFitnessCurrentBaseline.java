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
 * Phase-1 current-engine baseline over the complete 30-sentinel fitness matrix.
 *
 * <p>This class does not run another benchmark engine. It validates the frozen
 * 30-case mapping and merges Delos Heap/MVCC embedded and DRDA evidence emitted
 * by {@link DelosJdbcCrossEngineConcurrency} into one decision-quality schema.</p>
 */
public final class DelosArchitectureFitnessCurrentBaseline {
    private static final String PREFIX = "delosdb.benchmark.fitnessCurrentBaseline.";
    private static final int ROW_COUNT = 10_000;
    private static final List<String> EMBEDDED_TARGETS = List.of("delos_heap", "delos_mvcc");
    private static final List<String> SERVER_TARGETS = List.of("delos_heap_drda", "delos_mvcc_drda");

    private DelosArchitectureFitnessCurrentBaseline() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("contract".equals(args[0]) || "report".equals(args[0]))) {
            throw new IllegalArgumentException("Expected exactly one argument: contract or report");
        }
        Path reportDirectory = Path.of(required(PREFIX + "reportDirectory"));
        Path matrixTsv = Path.of(required(PREFIX + "matrixTsv"));
        Files.createDirectories(reportDirectory);
        validateFrozenMatrix(matrixTsv);
        writePlan(reportDirectory.resolve("architecture-fitness-current-baseline-plan.tsv"));
        writeContract(reportDirectory.resolve("architecture-fitness-current-baseline-contract.txt"));
        if ("contract".equals(args[0])) {
            System.out.println("DelosDB Phase-1 current baseline contract passed: " + reportDirectory);
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
        writeRows(reportDirectory.resolve("architecture-fitness-current-baseline.tsv"), rows);
        List<ComparisonRow> comparisons = comparisons(rows);
        writeComparisons(reportDirectory.resolve("architecture-fitness-current-baseline-comparisons.tsv"), comparisons);
        GateStatus suite = writeSummary(
                reportDirectory.resolve("architecture-fitness-current-baseline.txt"), rows, comparisons, environment);
        writeManifest(
                reportDirectory.resolve("architecture-fitness-current-baseline-manifest.properties"),
                rows, comparisons, environment, suite, measurementGate);
        System.out.println("DelosDB Phase-1 current baseline complete: " + reportDirectory
                + " status=" + suite + " rows=" + rows.size());
    }

    private static List<CaseSpec> cases() {
        return List.of(
                spec("F01-PK-HOT-1", 1, "Simple indexed reads", "reuse-now", "f01-hot",
                        "PRIMARY_KEY_READ_HOT", 1, clients(1, 8), "POINT_ROUND_TRIP"),
                spec("F01-PK-DISJOINT-1", 1, "Simple indexed reads", "reuse-now", "f01-disjoint",
                        "PRIMARY_KEY_READ_DISJOINT", 1, clients(1, 8), "POINT_ROUND_TRIP"),
                spec("F01-PK-DISJOINT-10", 1, "Simple indexed reads", "reuse-now", "f01-disjoint",
                        "PRIMARY_KEY_READ_DISJOINT", 10, clients(1, 8), "TRANSACTION_AMORTIZATION"),
                spec("F02-RANGE-100", 2, "Range/index scans", "reuse-now", "f02-range",
                        "RANGE_SCAN_100", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F02-RANGE-1000", 2, "Range/index scans", "reuse-now", "f02-range",
                        "RANGE_SCAN_1000", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F02-FULL-10000", 2, "Range/index scans", "reuse-now", "f02-range",
                        "RANGE_SCAN_FULL", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F02-INDEX-ONLY-1000", 2, "Range/index scans", "reuse-now", "f02-range",
                        "RANGE_SCAN_INDEX_ONLY_1000", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F03-PROJECT-COVERED", 3, "Projection/materialization", "promote-existing-a", "f03-projection",
                        "PROJECTION_COVERED", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F03-PROJECT-TWO-COLUMN", 3, "Projection/materialization", "promote-existing-a", "f03-projection",
                        "PROJECTION_TWO_COLUMN", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F03-PROJECT-FULL-ROW", 3, "Projection/materialization", "promote-existing-a", "f03-projection",
                        "PROJECTION_FULL_ROW", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F04-JOIN-INDEXED-1TO1", 4, "Simple joins", "adapt-reference-a", "f04-f06-relational-reference",
                        "JOIN_INDEXED_1TO1", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F04-JOIN-INDEXED-FANOUT", 4, "Simple joins", "build-new-a", "f04-f05-joins",
                        "JOIN_INDEXED_FANOUT", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F05-JOIN-3WAY-SELECTIVE", 5, "Multi-way joins", "build-new-a", "f04-f05-joins",
                        "JOIN_3WAY_SELECTIVE", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F05-JOIN-4WAY-FANOUT", 5, "Multi-way joins", "build-new-a", "f04-f05-joins",
                        "JOIN_4WAY_FANOUT", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F06-GROUP-LOW-CARD", 6, "Aggregation / GROUP BY", "promote-existing-a", "f06-f07-relational",
                        "GROUP_LOW_CARD", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F06-GROUP-HIGH-CARD", 6, "Aggregation / GROUP BY", "adapt-reference-a", "f04-f06-relational-reference",
                        "GROUP_HIGH_CARD", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F07-ORDER-SATISFIED", 7, "Sort / ORDER BY", "promote-existing-a", "f06-f07-relational",
                        "RANGE_SCAN_1000", 1, clients(1, 8), "RESULT_FETCH"),
                spec("F07-SORT-FULL", 7, "Sort / ORDER BY", "promote-existing-a", "f06-f07-relational",
                        "SORT_FULL", 1, clients(1, 8), "RELATIONAL_RESULT_FETCH"),
                spec("F08-INSERT-1", 8, "INSERT", "adapt-reference-a", "f08-insert-1",
                        "INSERT_1", 1, clients(1, 8), "WRITE_COMMIT"),
                spec("F08-INSERT-100", 8, "INSERT", "adapt-reference-a", "f08-insert-100",
                        "INSERT_100", 100, clients(1, 8), "WRITE_COMMIT"),
                spec("F09-UPDATE-DISJOINT", 9, "Indexed UPDATE", "reuse-now", "f09-update",
                        "DISJOINT_INDEXED_UPDATE", 1, clients(1, 8), "WRITE_COMMIT"),
                spec("F09-UPDATE-CONTENDED", 9, "Indexed UPDATE", "reuse-now", "f09-update",
                        "CONTENDED_INDEXED_UPDATE", 1, clients(1, 8), "WRITE_COMMIT"),
                spec("F10-DELETE-REINSERT-1", 10, "DELETE / reinsert", "promote-existing-a", "f10-delete-reinsert",
                        "DELETE_REINSERT", 1, clients(1, 8), "WRITE_COMMIT"),
                spec("F10-DELETE-REINSERT-10", 10, "DELETE / reinsert", "promote-existing-a", "f10-delete-reinsert",
                        "DELETE_REINSERT", 10, clients(1, 8), "WRITE_COMMIT"),
                spec("F11-MIXED-80R20W", 11, "Mixed readers + writers", "build-new-b", "f11-mixed",
                        "MIXED_80R20W", 1, clients(8), "MIXED_TRANSACTION"),
                spec("F11-MIXED-50R50W-HOT", 11, "Mixed readers + writers", "build-new-b", "f11-mixed",
                        "MIXED_50R50W_HOT", 1, clients(8), "MIXED_TRANSACTION"),
                spec("F12-LONG-READER-DISJOINT-WRITER", 12, "Long reader + writers", "promote-existing-b", "f12-long-reader-writers",
                        "LONG_READER_DISJOINT_WRITER", 1, clients(4), "LONG_READER_WRITER"),
                spec("F12-LONG-READER-HOT-WRITER", 12, "Long reader + writers", "promote-existing-b", "f12-long-reader-writers",
                        "LONG_READER_HOT_WRITER", 1, clients(4), "LONG_READER_WRITER"),
                spec("F13-BANK-TRANSACTION", 13, "Realistic transactional workload", "adapt-reference-b", "f13-realistic-transactions",
                        "BANK_TRANSACTION", 1, clients(1, 8), "REALISTIC_TRANSACTION"),
                spec("F13-ORDER-ENTRY-MIX", 13, "Realistic transactional workload", "adapt-reference-b", "f13-realistic-transactions",
                        "ORDER_ENTRY_MIX", 1, clients(1, 8), "REALISTIC_TRANSACTION"));
    }

    private static CaseSpec spec(
            String id,
            int familyId,
            String family,
            String tranche,
            String reportGroup,
            String workload,
            int operations,
            Set<Integer> clients,
            String protocolClass) {
        return new CaseSpec(id, familyId, family, tranche, reportGroup, workload, operations, clients, protocolClass);
    }

    private static Set<Integer> clients(int... values) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        for (int value : values) {
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static void validateFrozenMatrix(Path matrixTsv) throws IOException {
        List<String> lines = Files.readAllLines(matrixTsv, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().startsWith("caseId\tfamilyId\t")) {
            throw new IllegalStateException("Unexpected sentinel matrix TSV: " + matrixTsv);
        }
        Map<String, String[]> matrix = new LinkedHashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                String[] fields = lines.get(index).split("\\t", -1);
                matrix.put(fields[0], fields);
            }
        }
        Set<String> expected = new LinkedHashSet<>(matrix.keySet());
        Set<String> actual = new LinkedHashSet<>();
        for (CaseSpec spec : cases()) {
            actual.add(spec.caseId());
            String[] fields = matrix.get(spec.caseId());
            if (fields == null || fields.length < 10) {
                throw new IllegalStateException("Missing frozen sentinel row: " + spec.caseId());
            }
            if (Integer.parseInt(fields[1]) != spec.familyId() || !"BOTH".equals(fields[3])) {
                throw new IllegalStateException("Frozen matrix mismatch for " + spec.caseId());
            }
        }
        if (matrix.size() != 30 || !actual.equals(expected)) {
            throw new IllegalStateException(
                    "Current baseline must cover the exact frozen 30-sentinel matrix: matrix="
                            + expected + ", baseline=" + actual);
        }
    }

    private static void writePlan(Path output) throws IOException {
        StringBuilder text = new StringBuilder(
                "caseId\tfamilyId\tworkloadFamily\ttranche\treportGroup\tworkload\toperationsPerTransaction\tclients\tlanes\n");
        for (CaseSpec spec : cases()) {
            text.append(spec.caseId()).append('\t')
                    .append(spec.familyId()).append('\t')
                    .append(spec.family()).append('\t')
                    .append(spec.tranche()).append('\t')
                    .append(spec.reportGroup()).append('\t')
                    .append(spec.workload()).append('\t')
                    .append(spec.operationsPerTransaction()).append('\t')
                    .append(spec.clients()).append('\t')
                    .append("EMBEDDED,SERVER\n");
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static void writeContract(Path output) throws IOException {
        String text = """
                DelosDB Phase-1 current-engine baseline contract
                =================================================

                Frozen sentinels: exactly 30 across all thirteen workload families.
                Current targets only: Delos Heap + MVCC embedded; Delos Heap + MVCC DRDA server.
                Production algorithms: untouched. Phase-1 optimization freeze remains in force.
                Semantic authority: Phase-0A SQL-visible fingerprints/post-state.
                Measurement environment: fresh Phase-0C canaries plus host preflight/ready gates.
                Sampling: 8 independent runs, >=2.0s adaptive warmup, >=2.0s measured work,
                >=2 warmup intervals and >=3 measured intervals per target/cell.
                Dispersion: <=5% VALID; >5%-15% NOISY; >15% INVALID.
                INVALID evidence is preserved and blocks performance conclusions for that observation.
                Purpose: freeze the untouched-current Heap/MVCC architecture baseline before Phase 2 decomposition.
                """;
        Files.writeString(output, text, StandardCharsets.UTF_8);
    }

    private static List<FitnessRow> readCase(Path root, CaseSpec spec, GateStatus environment) throws IOException {
        List<FitnessRow> rows = new ArrayList<>();
        for (Lane lane : Lane.values()) {
            Path leaf = root.resolve(spec.tranche()).resolve(lane.directory()).resolve(spec.reportGroup());
            Path dispersion = leaf.resolve("cross-engine-concurrency-dispersion.csv");
            Path results = leaf.resolve("cross-engine-concurrency-results.csv");
            Path oracle = leaf.resolve("sql-semantic-oracle.csv");
            validateSamplingProfile(leaf.resolve("cross-engine-concurrency-summary.txt"));
            Map<Shape, String> oracleFingerprints = readOracle(oracle);
            Map<SampleKey, SampleEvidence> samples = readSamples(results);
            List<String> lines = Files.readAllLines(dispersion, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().startsWith(
                    "rowCount,workload,clients,operationsPerTransaction,target,runs,")) {
                throw new IllegalStateException("Unexpected dispersion CSV: " + dispersion);
            }
            Set<String> expectedTargets = new LinkedHashSet<>(lane == Lane.EMBEDDED
                    ? EMBEDDED_TARGETS : SERVER_TARGETS);
            Map<Integer, Set<String>> seen = new HashMap<>();
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) {
                    continue;
                }
                String[] fields = lines.get(index).split(",", -1);
                if (fields.length != 23) {
                    throw new IllegalStateException("Unexpected dispersion row: " + lines.get(index));
                }
                int rowCount = Integer.parseInt(fields[0]);
                String workload = fields[1];
                int clients = Integer.parseInt(fields[2]);
                int operations = Integer.parseInt(fields[3]);
                String target = fields[4];
                if (rowCount != ROW_COUNT || !workload.equals(spec.workload())
                        || operations != spec.operationsPerTransaction() || !spec.clients().contains(clients)) {
                    continue;
                }
                if (!expectedTargets.contains(target)) {
                    throw new IllegalStateException("Unexpected target " + target + " in " + dispersion);
                }
                seen.computeIfAbsent(clients, ignored -> new LinkedHashSet<>()).add(target);
                Shape shape = new Shape(rowCount, workload, clients, operations);
                String fingerprint = oracleFingerprints.get(shape);
                if (fingerprint == null) {
                    throw new IllegalStateException("Missing SQL oracle for " + shape + " in " + oracle);
                }
                SampleEvidence sample = samples.get(new SampleKey(shape, target));
                if (sample == null) {
                    throw new IllegalStateException("Missing sample evidence for " + shape + " target=" + target);
                }
                double iqr = Double.parseDouble(fields[20]);
                double mad = Double.parseDouble(fields[21]);
                double governing = Math.max(iqr, mad);
                GateStatus sampleStatus = sample.status();
                GateStatus dispersionStatus = GateStatus.fromDispersion(governing);
                GateStatus caseStatus = GateStatus.worst(sampleStatus, dispersionStatus);
                GateStatus finalStatus = GateStatus.worst(environment, caseStatus);
                rows.add(new FitnessRow(
                        spec, lane, target, clients, Integer.parseInt(fields[5]),
                        Double.parseDouble(fields[13]), iqr, mad, governing,
                        sample.runs(), sample.minElapsedSeconds(), sample.minWarmupElapsedSeconds(),
                        sample.minWarmups(), sample.minIterations(), sampleStatus, dispersionStatus,
                        caseStatus, environment, finalStatus, fingerprint));
            }
            for (int clients : spec.clients()) {
                Set<String> actual = seen.getOrDefault(clients, Set.of());
                if (!actual.equals(expectedTargets)) {
                    throw new IllegalStateException("Target coverage mismatch for " + spec.caseId()
                            + " lane=" + lane + " clients=" + clients
                            + ": expected=" + expectedTargets + ", actual=" + actual);
                }
            }
        }
        return List.copyOf(rows);
    }

    private static void validateSamplingProfile(Path summary) throws IOException {
        String text = Files.readString(summary, StandardCharsets.UTF_8);
        for (String required : List.of(
                "Warmups: 2",
                "Minimum warmup seconds per run: 2.0",
                "Maximum warmup intervals per run: 5000",
                "Iterations: 3",
                "Minimum measured seconds per run: 2.0",
                "Maximum measured intervals per run: 5000",
                "Runs: 8")) {
            if (!text.contains(required + "\n") && !text.endsWith(required)) {
                throw new IllegalStateException("Sampling profile mismatch; missing '" + required + "' in " + summary);
            }
        }
    }

    private static Map<Shape, String> readOracle(Path oracle) throws IOException {
        List<String> lines = Files.readAllLines(oracle, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(
                "target,workload,clients,operationsPerTransaction,rowCount,kind,count,fingerprint,run")) {
            throw new IllegalStateException("Unexpected SQL oracle CSV: " + oracle);
        }
        Map<Shape, String> values = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            String[] fields = lines.get(index).split(",", -1);
            Shape shape = new Shape(
                    Integer.parseInt(fields[4]), fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
            String prior = values.putIfAbsent(shape, fields[7]);
            if (prior != null && !prior.equals(fields[7])) {
                throw new IllegalStateException("SQL oracle drift for " + shape + " in " + oracle);
            }
        }
        return Map.copyOf(values);
    }

    private static Map<SampleKey, SampleEvidence> readSamples(Path results) throws IOException {
        List<String> lines = Files.readAllLines(results, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Empty results CSV: " + results);
        }
        String[] header = lines.getFirst().split(",", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.length; index++) {
            columns.put(header[index], index);
        }
        for (String required : List.of(
                "target", "workload", "clients", "operationsPerTransaction", "rowCount", "warmups",
                "warmupElapsedNanos", "iterations", "elapsedNanos", "run")) {
            if (!columns.containsKey(required)) {
                throw new IllegalStateException("Missing results column " + required + " in " + results);
            }
        }
        Map<SampleKey, MutableSample> mutable = new HashMap<>();
        for (int index = 1; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            String[] fields = lines.get(index).split(",", -1);
            if (fields.length != header.length) {
                throw new IllegalStateException("Unexpected results row: " + lines.get(index));
            }
            Shape shape = new Shape(
                    integer(fields, columns, "rowCount"),
                    string(fields, columns, "workload"),
                    integer(fields, columns, "clients"),
                    integer(fields, columns, "operationsPerTransaction"));
            SampleKey key = new SampleKey(shape, string(fields, columns, "target"));
            MutableSample sample = mutable.computeIfAbsent(key, ignored -> new MutableSample());
            sample.runs++;
            sample.minElapsedSeconds = Math.min(
                    sample.minElapsedSeconds, longValue(fields, columns, "elapsedNanos") / 1_000_000_000.0);
            sample.minWarmupElapsedSeconds = Math.min(
                    sample.minWarmupElapsedSeconds,
                    longValue(fields, columns, "warmupElapsedNanos") / 1_000_000_000.0);
            sample.minWarmups = Math.min(sample.minWarmups, integer(fields, columns, "warmups"));
            sample.minIterations = Math.min(sample.minIterations, integer(fields, columns, "iterations"));
        }
        Map<SampleKey, SampleEvidence> values = new HashMap<>();
        for (Map.Entry<SampleKey, MutableSample> entry : mutable.entrySet()) {
            MutableSample sample = entry.getValue();
            values.put(entry.getKey(), new SampleEvidence(
                    sample.runs, sample.minElapsedSeconds, sample.minWarmupElapsedSeconds,
                    sample.minWarmups, sample.minIterations));
        }
        return Map.copyOf(values);
    }

    private static int integer(String[] fields, Map<String, Integer> columns, String name) {
        return Integer.parseInt(string(fields, columns, name));
    }

    private static long longValue(String[] fields, Map<String, Integer> columns, String name) {
        return Long.parseLong(string(fields, columns, name));
    }

    private static String string(String[] fields, Map<String, Integer> columns, String name) {
        return fields[Objects.requireNonNull(columns.get(name), name)];
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
        int expected = cases().stream().mapToInt(spec -> spec.clients().size() * 4).sum();
        if (expected != 224 || rows.size() != expected) {
            throw new IllegalStateException("Current baseline row count mismatch: expected=224 actual=" + rows.size());
        }
        Map<String, Integer> counts = new HashMap<>();
        for (FitnessRow row : rows) {
            counts.merge(row.spec().caseId(), 1, Integer::sum);
        }
        for (CaseSpec spec : cases()) {
            int expectedForCase = spec.clients().size() * 4;
            if (!Objects.equals(counts.get(spec.caseId()), expectedForCase)) {
                throw new IllegalStateException("Incomplete baseline coverage for " + spec.caseId()
                        + ": expected=" + expectedForCase + " actual=" + counts.get(spec.caseId()));
            }
        }
    }

    private static void writeRows(Path output, List<FitnessRow> rows) throws IOException {
        List<FitnessRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing((FitnessRow row) -> row.spec().caseId())
                .thenComparing(row -> row.lane().name())
                .thenComparingInt(FitnessRow::clients)
                .thenComparing(FitnessRow::target));
        StringBuilder text = new StringBuilder();
        text.append("caseId\tfamilyId\tworkloadFamily\tlane\ttarget\tclients\toperationsPerTransaction\t")
                .append("medianOperationsPerSecond\tiqrToMedian\tmadToMedian\tgoverningDispersion\t")
                .append("sampleRuns\tminElapsedSeconds\tminWarmupElapsedSeconds\tminWarmupIntervals\t")
                .append("minMeasuredIntervals\tsampleStatus\tdispersionStatus\tcaseStatus\t")
                .append("environmentStatus\tfinalStatus\tsqlOracleFingerprint\tserverProtocolClass\n");
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
                    .append(row.sampleRuns()).append('\t')
                    .append(format(row.minElapsedSeconds())).append('\t')
                    .append(format(row.minWarmupElapsedSeconds())).append('\t')
                    .append(row.minWarmups()).append('\t')
                    .append(row.minMeasuredIterations()).append('\t')
                    .append(row.sampleStatus()).append('\t')
                    .append(row.dispersionStatus()).append('\t')
                    .append(row.caseStatus()).append('\t')
                    .append(row.environmentStatus()).append('\t')
                    .append(row.finalStatus()).append('\t')
                    .append(row.oracleFingerprint()).append('\t')
                    .append(row.spec().protocolClass()).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static List<ComparisonRow> comparisons(List<FitnessRow> rows) {
        Map<ComparisonKey, Map<String, FitnessRow>> grouped = new HashMap<>();
        for (FitnessRow row : rows) {
            grouped.computeIfAbsent(
                    new ComparisonKey(row.spec(), row.lane(), row.clients()), ignored -> new HashMap<>())
                    .put(row.target(), row);
        }
        List<ComparisonRow> result = new ArrayList<>();
        for (Map.Entry<ComparisonKey, Map<String, FitnessRow>> entry : grouped.entrySet()) {
            ComparisonKey key = entry.getKey();
            String heapTarget = key.lane() == Lane.EMBEDDED ? "delos_heap" : "delos_heap_drda";
            String mvccTarget = key.lane() == Lane.EMBEDDED ? "delos_mvcc" : "delos_mvcc_drda";
            FitnessRow heap = Objects.requireNonNull(entry.getValue().get(heapTarget), heapTarget);
            FitnessRow mvcc = Objects.requireNonNull(entry.getValue().get(mvccTarget), mvccTarget);
            GateStatus decision = GateStatus.worst(heap.finalStatus(), mvcc.finalStatus());
            result.add(new ComparisonRow(
                    key.spec(), key.lane(), key.clients(), heap.medianOps(), mvcc.medianOps(),
                    mvcc.medianOps() / heap.medianOps(), heap.governing(), mvcc.governing(), decision));
        }
        result.sort(Comparator.comparing((ComparisonRow row) -> row.spec().caseId())
                .thenComparing(row -> row.lane().name())
                .thenComparingInt(ComparisonRow::clients));
        if (result.size() != 112) {
            throw new IllegalStateException("Current baseline comparison count mismatch: " + result.size());
        }
        return List.copyOf(result);
    }

    private static void writeComparisons(Path output, List<ComparisonRow> rows) throws IOException {
        StringBuilder text = new StringBuilder(
                "caseId\tfamilyId\tworkloadFamily\tlane\tclients\theapMedianOpsPerSecond\t"
                        + "mvccMedianOpsPerSecond\tmvccToHeap\theapGoverningDispersion\t"
                        + "mvccGoverningDispersion\tdecisionStatus\n");
        for (ComparisonRow row : rows) {
            text.append(row.spec().caseId()).append('\t')
                    .append(row.spec().familyId()).append('\t')
                    .append(row.spec().family()).append('\t')
                    .append(row.lane()).append('\t')
                    .append(row.clients()).append('\t')
                    .append(format(row.heapMedianOps())).append('\t')
                    .append(format(row.mvccMedianOps())).append('\t')
                    .append(format(row.mvccToHeap())).append('\t')
                    .append(format(row.heapDispersion())).append('\t')
                    .append(format(row.mvccDispersion())).append('\t')
                    .append(row.decisionStatus()).append('\n');
        }
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
    }

    private static GateStatus writeSummary(
            Path output,
            List<FitnessRow> rows,
            List<ComparisonRow> comparisons,
            GateStatus environment) throws IOException {
        Map<GateStatus, Integer> caseCounts = counts(rows, false);
        Map<GateStatus, Integer> finalCounts = counts(rows, true);
        GateStatus suite = environment;
        for (FitnessRow row : rows) {
            suite = GateStatus.worst(suite, row.finalStatus());
        }
        long comparisonBlocked = comparisons.stream()
                .filter(row -> row.decisionStatus() == GateStatus.INVALID)
                .count();
        StringBuilder text = new StringBuilder();
        text.append("DelosDB Phase-1 untouched-current architecture baseline\n")
                .append("====================================================\n\n")
                .append("Frozen sentinels: 30 / 30\n")
                .append("Workload families: 13 / 13\n")
                .append("Targets: Delos Heap + MVCC embedded; Delos Heap + MVCC DRDA\n")
                .append("Evidence rows: ").append(rows.size()).append(" / 224\n")
                .append("Heap-vs-MVCC comparisons: ").append(comparisons.size()).append(" / 112\n")
                .append("SQL semantic authority: PASS for every accepted target/run/cell\n")
                .append("Measurement environment: ").append(environment).append('\n')
                .append("Underlying VALID: ").append(caseCounts.get(GateStatus.VALID)).append('\n')
                .append("Underlying NOISY: ").append(caseCounts.get(GateStatus.NOISY)).append('\n')
                .append("Underlying INVALID: ").append(caseCounts.get(GateStatus.INVALID)).append('\n')
                .append("Final VALID: ").append(finalCounts.get(GateStatus.VALID)).append('\n')
                .append("Final NOISY: ").append(finalCounts.get(GateStatus.NOISY)).append('\n')
                .append("Final INVALID: ").append(finalCounts.get(GateStatus.INVALID)).append('\n')
                .append("Blocked Heap/MVCC comparisons: ").append(comparisonBlocked).append('\n')
                .append("Overall baseline status: ").append(suite).append("\n\n")
                .append("Interpretation:\n")
                .append("- This is the untouched-current baseline, not a production optimization result.\n")
                .append("- VALID rows are ordinary Phase-2 decomposition evidence.\n")
                .append("- NOISY rows require effects materially larger than the observed dispersion.\n")
                .append("- INVALID rows/comparisons are preserved but must not drive architecture conclusions.\n")
                .append("- Protocol attribution remains the separate Phase-1 Step-7 evidence tranche.\n")
                .append("- The next step is Phase 2 classification/decomposition, not immediate benchmark-specific tuning.\n");
        Files.writeString(output, text.toString(), StandardCharsets.UTF_8);
        return suite;
    }

    private static Map<GateStatus, Integer> counts(List<FitnessRow> rows, boolean finalStatus) {
        Map<GateStatus, Integer> result = new LinkedHashMap<>();
        for (GateStatus status : GateStatus.values()) {
            result.put(status, 0);
        }
        for (FitnessRow row : rows) {
            GateStatus status = finalStatus ? row.finalStatus() : row.caseStatus();
            result.merge(status, 1, Integer::sum);
        }
        return result;
    }

    private static void writeManifest(
            Path output,
            List<FitnessRow> rows,
            List<ComparisonRow> comparisons,
            GateStatus environment,
            GateStatus suite,
            Path measurementGate) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("generated", Instant.now().toString());
        properties.setProperty("phase", "1");
        properties.setProperty("purpose", "UNTOUCHED_CURRENT_ARCHITECTURE_BASELINE");
        properties.setProperty("case.count", "30");
        properties.setProperty("family.count", "13");
        properties.setProperty("result.row.count", Integer.toString(rows.size()));
        properties.setProperty("comparison.row.count", Integer.toString(comparisons.size()));
        properties.setProperty("targets.embedded", String.join(",", EMBEDDED_TARGETS));
        properties.setProperty("targets.server", String.join(",", SERVER_TARGETS));
        properties.setProperty("semantic.authority", "PHASE_0A_SQL_ORACLE");
        properties.setProperty("measurement.validity.file", measurementGate.toAbsolutePath().normalize().toString());
        properties.setProperty("measurement.validity.status", environment.name());
        properties.setProperty("baseline.status", suite.name());
        properties.setProperty("sample.minimum.runs", "8");
        properties.setProperty("sample.minimum.warmup.seconds", "2.0");
        properties.setProperty("sample.minimum.measured.seconds", "2.0");
        properties.setProperty("sample.minimum.warmup.intervals", "2");
        properties.setProperty("sample.minimum.measured.intervals", "3");
        properties.setProperty("production.optimization", "NONE_PHASE1_FREEZE");
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "DelosDB Phase-1 untouched-current architecture baseline");
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

        String directory() {
            return directory;
        }
    }

    private enum GateStatus {
        VALID,
        NOISY,
        INVALID;

        static GateStatus fromDispersion(double value) {
            if (!Double.isFinite(value) || value > 0.15) {
                return INVALID;
            }
            return value > 0.05 ? NOISY : VALID;
        }

        static GateStatus worst(GateStatus left, GateStatus right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    private record CaseSpec(
            String caseId,
            int familyId,
            String family,
            String tranche,
            String reportGroup,
            String workload,
            int operationsPerTransaction,
            Set<Integer> clients,
            String protocolClass) {
    }

    private record Shape(int rowCount, String workload, int clients, int operationsPerTransaction) {
    }

    private record SampleKey(Shape shape, String target) {
    }

    private static final class MutableSample {
        private int runs;
        private double minElapsedSeconds = Double.POSITIVE_INFINITY;
        private double minWarmupElapsedSeconds = Double.POSITIVE_INFINITY;
        private int minWarmups = Integer.MAX_VALUE;
        private int minIterations = Integer.MAX_VALUE;
    }

    private record SampleEvidence(
            int runs,
            double minElapsedSeconds,
            double minWarmupElapsedSeconds,
            int minWarmups,
            int minIterations) {
        GateStatus status() {
            return runs == 8
                    && minElapsedSeconds >= 2.0
                    && minWarmupElapsedSeconds >= 2.0
                    && minWarmups >= 2
                    && minIterations >= 3
                    ? GateStatus.VALID : GateStatus.INVALID;
        }
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
            int sampleRuns,
            double minElapsedSeconds,
            double minWarmupElapsedSeconds,
            int minWarmups,
            int minMeasuredIterations,
            GateStatus sampleStatus,
            GateStatus dispersionStatus,
            GateStatus caseStatus,
            GateStatus environmentStatus,
            GateStatus finalStatus,
            String oracleFingerprint) {
    }

    private record ComparisonKey(CaseSpec spec, Lane lane, int clients) {
    }

    private record ComparisonRow(
            CaseSpec spec,
            Lane lane,
            int clients,
            double heapMedianOps,
            double mvccMedianOps,
            double mvccToHeap,
            double heapDispersion,
            double mvccDispersion,
            GateStatus decisionStatus) {
    }
}
