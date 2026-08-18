/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport;
import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport.Algorithm;
import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport.Measurement;
import org.apache.derby.impl.store.access.mvcc.MvccCurrentRowTopologyTestSupport.Prototype;

/**
 * Below-SQL mechanism proof for three MVCC current-row access algorithms.
 *
 * <p>The test deliberately measures the physical access boundary rather than SQL/JDBC
 * execution. Prototype algorithms B/C consume ordinary RawStore page containers modeling
 * the ordered leaf stream after a key seek; historical fallback uses the production MVCC
 * version reader.</p>
 */
public final class DelosMvccCurrentRowTopologyMechanism {
    private static final String TABLE = "DELOS_MVCC_TOPOLOGY";
    private static volatile long blackhole;

    private DelosMvccCurrentRowTopologyMechanism() {
    }

    public static void main(String[] args) throws Exception {
        Path database = Path.of(property("database", "build/tmp/delos-mvcc-current-row-topology"));
        Path reportDirectory = Path.of(property(
                "reportDirectory",
                "build/reports/delosdb/benchmarks/mvcc-current-row-topology-mechanism"));
        boolean validationOnly = Boolean.parseBoolean(property("validationOnly", "false"));
        int rows = intProperty("rows", validationOnly ? 2000 : 10000);
        int updateRows = intProperty("updateRows", validationOnly ? 200 : 1000);
        int warmups = intProperty("warmups", validationOnly ? 0 : 1);
        int iterations = intProperty("iterations", validationOnly ? 1 : 2);
        int runs = intProperty("runs", validationOnly ? 1 : 4);
        int targetRows = intProperty("targetRows", validationOnly ? updateRows : 250000);
        if (rows < 2 * updateRows || updateRows <= 0) {
            throw new IllegalArgumentException(
                    "rows must be at least 2*updateRows; rows=" + rows + " updateRows=" + updateRows);
        }

        DelosBenchmarkSupport.prepareOutput(database, reportDirectory);
        List<RunResult> results = DelosBenchmarkSupport.withFreshEmbeddedDatabase(
                database,
                connection -> run(
                        connection,
                        rows,
                        updateRows,
                        warmups,
                        iterations,
                        runs,
                        targetRows));
        writeRuns(reportDirectory, results);
        writeComparison(reportDirectory, results);
        writeDecision(reportDirectory, results, validationOnly);
        System.out.println("MVCC current-row topology mechanism proof complete: " + reportDirectory);
    }

    private static List<RunResult> run(
            Connection connection,
            int rows,
            int updateRows,
            int warmups,
            int iterations,
            int runs,
            int targetRows) throws Exception {
        connection.setAutoCommit(false);
        prepareFixture(connection, rows);
        long historicalSnapshot =
                MvccCurrentRowTopologyTestSupport.captureCommittedSequence(connection, TABLE);
        updateFirstRows(connection, updateRows);
        long currentSnapshot =
                MvccCurrentRowTopologyTestSupport.captureCommittedSequence(connection, TABLE);
        if (currentSnapshot <= historicalSnapshot) {
            throw new IllegalStateException(
                    "Committed snapshot did not advance after update: old=" + historicalSnapshot
                            + " current=" + currentSnapshot);
        }

        List<RunResult> results = new ArrayList<>();
        try (MvccCurrentRowTopologyTestSupport.Session session =
                     MvccCurrentRowTopologyTestSupport.openSession(connection, TABLE)) {
            Prototype range = session.buildPrototype(1, updateRows + 1);
            Prototype full = session.buildPrototype(1, rows + 1);
            try {
                List<Scenario> scenarios = List.of(
                        new Scenario(
                                "CURRENT_INDEX_ONLY_" + updateRows,
                                range,
                                1,
                                updateRows + 1,
                                currentSnapshot,
                                false,
                                updateRows),
                        new Scenario(
                                "CURRENT_ROW_" + updateRows,
                                range,
                                1,
                                updateRows + 1,
                                currentSnapshot,
                                true,
                                updateRows),
                        new Scenario(
                                "CURRENT_FULL_" + rows,
                                full,
                                1,
                                rows + 1,
                                currentSnapshot,
                                true,
                                rows),
                        new Scenario(
                                "HISTORICAL_ROW_" + updateRows,
                                range,
                                1,
                                updateRows + 1,
                                historicalSnapshot,
                                true,
                                updateRows),
                        new Scenario(
                                "HISTORICAL_FULL_" + rows,
                                full,
                                1,
                                rows + 1,
                                historicalSnapshot,
                                true,
                                rows));

                validateSemantics(session, scenarios);
                for (int run = 1; run <= runs; run++) {
                    int scenarioIndex = 0;
                    for (Scenario scenario : scenarios) {
                        Algorithm[] order = rotatedAlgorithms(run + scenarioIndex++);
                        for (Algorithm algorithm : order) {
                            for (int warmup = 0; warmup < warmups; warmup++) {
                                Measurement measurement = session.measure(
                                        scenario.prototype(),
                                        algorithm,
                                        scenario.start(),
                                        scenario.endExclusive(),
                                        scenario.snapshot(),
                                        scenario.includeQuantity());
                                blackhole ^= measurement.fingerprint();
                            }
                            results.add(measure(
                                    session,
                                    scenario,
                                    algorithm,
                                    run,
                                    iterations,
                                    targetRows));
                        }
                    }
                }
            } finally {
                session.drop(range);
                session.drop(full);
            }
        }
        connection.rollback();
        return List.copyOf(results);
    }

    private static void prepareFixture(Connection connection, int rows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE
                    + " (id int not null primary key, quantity int not null) using delos_mvcc");
        }
        connection.commit();
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + TABLE + " (id, quantity) values (?, ?)")) {
            for (int id = 1; id <= rows; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id * 3);
                insert.addBatch();
                if (id % 100 == 0) {
                    insert.executeBatch();
                }
            }
            insert.executeBatch();
        }
        connection.commit();
    }

    private static void updateFirstRows(Connection connection, int updateRows) throws Exception {
        try (PreparedStatement update = connection.prepareStatement(
                "update " + TABLE + " set quantity = quantity + 1 where id >= 1 and id < ?")) {
            update.setInt(1, updateRows + 1);
            int changed = update.executeUpdate();
            if (changed != updateRows) {
                throw new IllegalStateException(
                        "Expected " + updateRows + " committed updates, got " + changed);
            }
        }
        connection.commit();
    }

    private static void validateSemantics(
            MvccCurrentRowTopologyTestSupport.Session session,
            List<Scenario> scenarios) throws Exception {
        for (Scenario scenario : scenarios) {
            Measurement expected = null;
            for (Algorithm algorithm : Algorithm.values()) {
                Measurement actual = session.measure(
                        scenario.prototype(),
                        algorithm,
                        scenario.start(),
                        scenario.endExclusive(),
                        scenario.snapshot(),
                        scenario.includeQuantity());
                if (actual.rows() != scenario.expectedRows()) {
                    throw new IllegalStateException(
                            scenario.name() + " " + algorithm + " returned " + actual.rows()
                                    + " rows instead of " + scenario.expectedRows());
                }
                if (expected == null) {
                    expected = actual;
                } else if (actual.fingerprint() != expected.fingerprint()) {
                    throw new IllegalStateException(
                            scenario.name() + " fingerprint differs: existing="
                                    + Long.toUnsignedString(expected.fingerprint()) + " " + algorithm + "="
                                    + Long.toUnsignedString(actual.fingerprint()));
                }
            }
        }
    }

    private static RunResult measure(
            MvccCurrentRowTopologyTestSupport.Session session,
            Scenario scenario,
            Algorithm algorithm,
            int run,
            int iterations,
            int targetRows) throws Exception {
        int scansPerIteration = Math.max(1, (targetRows + scenario.expectedRows() - 1)
                / scenario.expectedRows());
        long elapsed = 0L;
        long returnedRows = 0L;
        long localVisible = 0L;
        long historyFallbacks = 0L;
        long versionSlotFetches = 0L;
        long directoryPages = 0L;
        long candidateCount = 0L;
        long anchorHits = 0L;
        long fingerprint = 0L;
        for (int iteration = 0; iteration < iterations; iteration++) {
            long startTime = System.nanoTime();
            for (int scan = 0; scan < scansPerIteration; scan++) {
                Measurement measurement = session.measure(
                        scenario.prototype(),
                        algorithm,
                        scenario.start(),
                        scenario.endExclusive(),
                        scenario.snapshot(),
                        scenario.includeQuantity());
                returnedRows += measurement.rows();
                localVisible += measurement.localVisible();
                historyFallbacks += measurement.historyFallbacks();
                versionSlotFetches += measurement.versionSlotFetches();
                directoryPages += measurement.directoryPageAcquisitions();
                candidateCount += measurement.candidateCount();
                anchorHits += measurement.anchorHits();
                fingerprint ^= measurement.fingerprint();
            }
            elapsed += System.nanoTime() - startTime;
        }
        blackhole ^= fingerprint;
        double seconds = elapsed / 1_000_000_000.0d;
        double rowsPerSecond = returnedRows / seconds;
        return new RunResult(
                scenario.name(),
                algorithm.name(),
                run,
                rowsPerSecond,
                returnedRows,
                elapsed,
                fingerprint,
                perRow(localVisible, returnedRows),
                perRow(historyFallbacks, returnedRows),
                perRow(versionSlotFetches, returnedRows),
                perRow(directoryPages, returnedRows),
                perRow(candidateCount, returnedRows),
                perRow(anchorHits, returnedRows));
    }

    private static Algorithm[] rotatedAlgorithms(int seed) {
        Algorithm[] algorithms = Algorithm.values().clone();
        int offset = Math.floorMod(seed - 1, algorithms.length);
        Algorithm[] rotated = new Algorithm[algorithms.length];
        for (int index = 0; index < algorithms.length; index++) {
            rotated[index] = algorithms[(index + offset) % algorithms.length];
        }
        return rotated;
    }

    private static void writeRuns(Path reportDirectory, List<RunResult> results) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,algorithm,run,rowsPerSecond,returnedRows,elapsedNanos,fingerprint,")
                .append("localVisiblePerRow,historyFallbacksPerRow,versionSlotFetchesPerRow,")
                .append("directoryPageAcquisitionsPerRow,candidatesPerRow,anchorHitsPerRow\n");
        for (RunResult row : results) {
            csv.append(row.scenario()).append(',')
                    .append(row.algorithm()).append(',')
                    .append(row.run()).append(',')
                    .append(format(row.rowsPerSecond())).append(',')
                    .append(row.returnedRows()).append(',')
                    .append(row.elapsedNanos()).append(',')
                    .append(Long.toUnsignedString(row.fingerprint())).append(',')
                    .append(format(row.localVisiblePerRow())).append(',')
                    .append(format(row.historyFallbacksPerRow())).append(',')
                    .append(format(row.versionSlotFetchesPerRow())).append(',')
                    .append(format(row.directoryPageAcquisitionsPerRow())).append(',')
                    .append(format(row.candidatesPerRow())).append(',')
                    .append(format(row.anchorHitsPerRow())).append('\n');
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-current-row-topology-runs.csv"), csv.toString());
    }

    private static void writeComparison(Path reportDirectory, List<RunResult> results)
            throws Exception {
        Map<String, Map<Algorithm, Stats>> byScenario = summarize(results);
        StringBuilder csv = new StringBuilder();
        csv.append("scenario,algorithm,medianRowsPerSecond,iqrToMedian,madToMedian,")
                .append("vsExisting,percentChangeVsExisting,medianVersionSlotFetchesPerRow,")
                .append("medianHistoryFallbacksPerRow,medianLocalVisiblePerRow\n");
        for (var scenarioEntry : byScenario.entrySet()) {
            Stats existing = scenarioEntry.getValue().get(Algorithm.EXISTING);
            for (Algorithm algorithm : Algorithm.values()) {
                Stats stats = scenarioEntry.getValue().get(algorithm);
                double ratio = stats.median() / existing.median();
                csv.append(scenarioEntry.getKey()).append(',')
                        .append(algorithm.name()).append(',')
                        .append(format(stats.median())).append(',')
                        .append(format(stats.iqrToMedian())).append(',')
                        .append(format(stats.madToMedian())).append(',')
                        .append(format(ratio)).append(',')
                        .append(format((ratio - 1.0d) * 100.0d)).append(',')
                        .append(format(stats.versionReadsPerRow())).append(',')
                        .append(format(stats.historyFallbacksPerRow())).append(',')
                        .append(format(stats.localVisiblePerRow())).append('\n');
            }
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-current-row-topology-comparison.csv"), csv.toString());
    }

    private static void writeDecision(
            Path reportDirectory,
            List<RunResult> results,
            boolean validationOnly) throws Exception {
        Map<String, Map<Algorithm, Stats>> byScenario = summarize(results);
        StringBuilder text = new StringBuilder();
        text.append("MVCC current-row topology mechanism proof\n")
                .append("Purpose: compare algorithms below SQL, not cache sizing or optimizer behavior.\n")
                .append("A EXISTING: production ordered-index candidate/materialization + directory/version resolution.\n")
                .append("B CURRENT_ROW_ANCHOR: ordered leaf entry carries stable current-version identity/validity; current payload comes from real version storage.\n")
                .append("C ROW_BEARING_INTERVAL: ordered leaf entry carries current row payload + validity; history uses real production version reader only when local current version is not visible.\n")
                .append("Prototype B/C rows are ordinary RawStore page records representing the ordered leaf stream after key seek.\n")
                .append("Heap is untouched; this is MVCC-only physical-topology evidence.\n\n");
        for (var scenario : byScenario.entrySet()) {
            Stats a = scenario.getValue().get(Algorithm.EXISTING);
            Stats b = scenario.getValue().get(Algorithm.CURRENT_ROW_ANCHOR);
            Stats c = scenario.getValue().get(Algorithm.ROW_BEARING_INTERVAL);
            text.append(String.format(Locale.ROOT,
                    "%s: A=%.2f B=%.2f (%.3fx) C=%.2f (%.3fx) C/B=%.3fx%n",
                    scenario.getKey(),
                    a.median(),
                    b.median(), b.median() / a.median(),
                    c.median(), c.median() / a.median(),
                    c.median() / b.median()));
        }
        text.append('\n');
        if (validationOnly) {
            text.append("VALIDATION PASS: all three algorithms returned identical fingerprints for current and historical snapshots.\n");
        } else {
            text.append("Decision rule:\n")
                    .append("- C materially > B and A on current scans, with bounded historical fallback: prefer row-bearing current-row primary prototype.\n")
                    .append("- B ~= C and both materially > A: prefer lower-risk stable-current-row-anchor topology.\n")
                    .append("- Historical fallback dominates or collapses either prototype: redesign history integration before production.\n")
                    .append("- No production storage change is accepted from this proof alone; write/update/delete/concurrency/recovery gates remain mandatory.\n");
        }
        DelosBenchmarkSupport.writeUtf8(
                reportDirectory.resolve("mvcc-current-row-topology-decision.txt"), text.toString());
    }

    private static Map<String, Map<Algorithm, Stats>> summarize(List<RunResult> results) {
        Map<String, Map<Algorithm, Stats>> output = new LinkedHashMap<>();
        for (RunResult result : results) {
            output.computeIfAbsent(result.scenario(), ignored -> new EnumMap<>(Algorithm.class));
        }
        for (String scenario : output.keySet()) {
            for (Algorithm algorithm : Algorithm.values()) {
                List<RunResult> rows = results.stream()
                        .filter(row -> row.scenario().equals(scenario)
                                && row.algorithm().equals(algorithm.name()))
                        .toList();
                if (rows.isEmpty()) {
                    throw new IllegalStateException(
                            "Missing topology measurements for " + scenario + " " + algorithm);
                }
                double[] throughput = rows.stream().mapToDouble(RunResult::rowsPerSecond).toArray();
                double median = median(throughput);
                double q1 = percentile(throughput, 0.25d);
                double q3 = percentile(throughput, 0.75d);
                double[] deviations = Arrays.stream(throughput)
                        .map(value -> Math.abs(value - median)).toArray();
                Stats stats = new Stats(
                        median,
                        median == 0.0d ? 0.0d : (q3 - q1) / median,
                        median == 0.0d ? 0.0d : median(deviations) / median,
                        median(rows.stream().mapToDouble(RunResult::versionSlotFetchesPerRow).toArray()),
                        median(rows.stream().mapToDouble(RunResult::historyFallbacksPerRow).toArray()),
                        median(rows.stream().mapToDouble(RunResult::localVisiblePerRow).toArray()));
                output.get(scenario).put(algorithm, stats);
            }
        }
        return output;
    }

    private static double median(double[] values) {
        return percentile(values, 0.5d);
    }

    private static double percentile(double[] values, double fraction) {
        if (values.length == 0) {
            return 0.0d;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = fraction * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1.0d - weight) + sorted[upper] * weight;
    }

    private static double perRow(long value, long rows) {
        return rows == 0L ? 0.0d : value / (double) rows;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String property(String suffix, String defaultValue) {
        return System.getProperty("delosdb.benchmark.mvccCurrentRowTopology." + suffix, defaultValue);
    }

    private static int intProperty(String suffix, int defaultValue) {
        return Integer.parseInt(property(suffix, Integer.toString(defaultValue)));
    }

    private record Scenario(
            String name,
            Prototype prototype,
            int start,
            int endExclusive,
            long snapshot,
            boolean includeQuantity,
            int expectedRows) {
    }

    private record RunResult(
            String scenario,
            String algorithm,
            int run,
            double rowsPerSecond,
            long returnedRows,
            long elapsedNanos,
            long fingerprint,
            double localVisiblePerRow,
            double historyFallbacksPerRow,
            double versionSlotFetchesPerRow,
            double directoryPageAcquisitionsPerRow,
            double candidatesPerRow,
            double anchorHitsPerRow) {
    }

    private record Stats(
            double median,
            double iqrToMedian,
            double madToMedian,
            double versionReadsPerRow,
            double historyFallbacksPerRow,
            double localVisiblePerRow) {
    }
}
