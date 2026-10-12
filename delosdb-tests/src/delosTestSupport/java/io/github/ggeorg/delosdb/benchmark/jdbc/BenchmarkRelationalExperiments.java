/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.capturePhase2NExplain;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2EProfile;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2OFeatures;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2PFeatures;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2PMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2PPlanMetric;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2PResult;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2RConcurrencyMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Distribution;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ExplainCapture;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.MvccFeatureNames;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Row;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.deleteRecursively;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2K_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2N_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2O_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2P_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2Q_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.openPhase2AConnection;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2BPrimaryKeyIndex;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2EIndexScanNextMillis;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2EIndexToBaseNextMillis;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2PStatisticsSnapshot;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RMedian;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RRunRangeStatus;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RStatus;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.sumPhase2AField;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.writePhase2ACapture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.highCardGroupTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinDimensionTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.phase2BUpdateStatistics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareHighCardGroupFixture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareJoinDimensionFixture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.SEED;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.mix;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.distribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadRunner.ordered;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.FITNESS_CATEGORY;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bindFitnessRead;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.expectedFitnessRows;

/** Legacy projection, grouping and sorting experiments. */
final class BenchmarkRelationalExperiments {
    private BenchmarkRelationalExperiments() {
    }

    static void runPhase2TF07StreamingFalsification() throws Exception {
        Path reportDirectory = requiredPhase2TPath("reportDirectory");
        Path databaseRoot = requiredPhase2TPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        boolean streaming = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2StreamingBulkScan.enabled");
        boolean projected = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean singlePass = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2SinglePassCurrentScan.enabled");
        boolean reusable = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ReusableCurrentScanTemplate.enabled");
        if (!projected || !singlePass || !reusable) {
            throw new IllegalStateException(
                    "Phase-2T requires projected CURRENT plus single-pass/reusable scan flags");
        }

        DelosBenchmarkConfig config =
                new DelosBenchmarkConfig(rowCount, payloadSize, SEED, commitBatchSize);
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f07-streaming-falsification").toAbsolutePath()
                + ";create=true";
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            new DelosJdbcBenchmarkScenario(
                    setup, "p2t_heap", "", true, config).prepare();
            new DelosJdbcBenchmarkScenario(
                    setup, "p2t_mvcc", " using delos_mvcc", true, config).prepare();
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2T_HEAP");
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2T_MVCC");
            setup.commit();
        }

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put(
                "heap-input", phase2QSortSql("DELOS_BENCH_P2T_HEAP", false, true));
        sqlByVariant.put(
                "mvcc-input", phase2QSortSql("DELOS_BENCH_P2T_MVCC", false, true));
        sqlByVariant.put(
                "heap-sort", phase2QSortSql("DELOS_BENCH_P2T_HEAP", true, false));
        sqlByVariant.put(
                "mvcc-sort", phase2QSortSql("DELOS_BENCH_P2T_MVCC", true, false));

        LinkedHashMap<String, Phase2RConcurrencyMeasurement> measurements =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            boolean ordered = entry.getKey().endsWith("-sort");
            measurements.put(
                    entry.getKey(),
                    phase2TMeasureConcurrent(
                            jdbcUrl, entry.getValue(), entry.getKey(), rowCount, ordered, 8));
        }
        phase2TAssertSemantics(measurements);
        phase2TWriteMeasurements(reportDirectory, measurements);

        double inputRatio = phase2RRatio(measurements, "mvcc-input", "heap-input");
        double sortRatio = phase2RRatio(measurements, "mvcc-sort", "heap-sort");
        String summary = "DelosDB Phase-2T F07 streaming falsification\n"
                + "diagnosticOnly=true\n"
                + "rows=" + rowCount + "\n"
                + "clients=8\n"
                + "holdability=CLOSE_CURSORS_AT_COMMIT\n"
                + "projectedCurrentRead=" + projected + "\n"
                + "singlePassCurrentScan=" + singlePass + "\n"
                + "reusableCurrentScanTemplate=" + reusable + "\n"
                + "streamingBulkScan=" + streaming + "\n"
                + "streamingReachable=" + streaming + "\n"
                + "heapInputTxPerSec=" + format(phase2RMedian(measurements, "heap-input")) + "\n"
                + "mvccInputTxPerSec=" + format(phase2RMedian(measurements, "mvcc-input")) + "\n"
                + "heapSortTxPerSec=" + format(phase2RMedian(measurements, "heap-sort")) + "\n"
                + "mvccSortTxPerSec=" + format(phase2RMedian(measurements, "mvcc-sort")) + "\n"
                + "mvccVsHeapInput=" + format(inputRatio) + "\n"
                + "mvccVsHeapSort=" + format(sortRatio) + "\n"
                + "semanticFingerprintsMatch=true\n"
                + "measurementStatus=" + phase2RStatus(measurements) + "\n"
                + "runRangeStatus=" + phase2RRunRangeStatus(measurements) + "\n";
        Files.writeString(
                reportDirectory.resolve("phase2t-f07-streaming-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Phase2RConcurrencyMeasurement phase2TMeasureConcurrent(
            String jdbcUrl,
            String sql,
            String variant,
            int expectedRows,
            boolean ordered,
            int clients) throws Exception {
        int warmupRounds = 3;
        int measuredRounds = 7;
        int executionsPerClient = 2;
        List<Connection> connections = new ArrayList<>();
        List<PreparedStatement> statements = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clients);
        try {
            for (int client = 0; client < clients; client++) {
                Connection connection = openPhase2AConnection(jdbcUrl);
                connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
                if (connection.getHoldability() != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                    throw new IllegalStateException("Phase-2T non-holdable control not applied");
                }
                connections.add(connection);
                statements.add(connection.prepareStatement(sql));
            }
            long fingerprint = phase2QExecute(statements.get(0), expectedRows, ordered);
            for (int warmup = 0; warmup < warmupRounds; warmup++) {
                phase2RConcurrentRound(
                        executor, statements, expectedRows, ordered, fingerprint,
                        executionsPerClient, false);
            }
            List<Double> throughputs = new ArrayList<>();
            for (int round = 0; round < measuredRounds; round++) {
                throughputs.add(phase2RConcurrentRound(
                        executor, statements, expectedRows, ordered, fingerprint,
                        executionsPerClient, true));
            }
            Distribution distribution = distribution(throughputs);
            DelosMeasurementValidityContract.DispersionDecision decision =
                    DelosMeasurementValidityContract.classifyCustom(
                            distribution.iqr() / distribution.median(),
                            distribution.mad() / distribution.median());
            return new Phase2RConcurrencyMeasurement(
                    fingerprint, throughputs, distribution, decision);
        } finally {
            executor.shutdownNow();
            for (PreparedStatement statement : statements) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // Diagnostic cleanup only.
                }
            }
            for (Connection connection : connections) {
                try {
                    connection.rollback();
                } finally {
                    connection.close();
                }
            }
        }
    }

    static void phase2TAssertSemantics(
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        long input = measurements.get("heap-input").fingerprint();
        if (measurements.get("mvcc-input").fingerprint() != input) {
            throw new IllegalStateException("Phase-2T input semantic drift");
        }
        long sorted = measurements.get("heap-sort").fingerprint();
        if (measurements.get("mvcc-sort").fingerprint() != sorted) {
            throw new IllegalStateException("Phase-2T sort semantic drift");
        }
    }

    static void phase2TWriteMeasurements(
            Path reportDirectory,
            Map<String, Phase2RConcurrencyMeasurement> measurements) throws IOException {
        StringBuilder samples = new StringBuilder("variant\tround\tthroughputTxPerSec\n");
        StringBuilder dispersion = new StringBuilder(
                "variant\tmedianTxPerSec\tiqrToMedian\tmadToMedian\tgoverningDispersion"
                        + "\tmaxMinRatio\trunRangeStatus\tstatus\n");
        for (Map.Entry<String, Phase2RConcurrencyMeasurement> entry : measurements.entrySet()) {
            Phase2RConcurrencyMeasurement measurement = entry.getValue();
            for (int i = 0; i < measurement.throughputs().size(); i++) {
                samples.append(entry.getKey()).append('\t').append(i + 1).append('\t')
                        .append(format(measurement.throughputs().get(i))).append('\n');
            }
            Distribution distribution = measurement.distribution();
            double maxMinRatio = distribution.min() == 0.0d
                    ? Double.POSITIVE_INFINITY
                    : distribution.max() / distribution.min();
            double iqrRatio = distribution.iqr() / distribution.median();
            double madRatio = distribution.mad() / distribution.median();
            dispersion.append(entry.getKey()).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(iqrRatio)).append('\t')
                    .append(format(madRatio)).append('\t')
                    .append(format(Math.max(iqrRatio, madRatio))).append('\t')
                    .append(format(maxMinRatio)).append('\t')
                    .append(maxMinRatio <= 1.20d ? "VALID" : "INVALID").append('\t')
                    .append(measurement.decision().status()).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("phase2t-repeated-throughput-samples.tsv"),
                samples.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("phase2t-dispersion.tsv"),
                dispersion.toString(), StandardCharsets.UTF_8);
    }

    static Path requiredPhase2TPath(String key) {
        String prefix = "delosdb.phase2.f07StreamingFalsification.";
        String value = System.getProperty(prefix + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + prefix + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2SF07EmbeddedJfrAttribution() throws Exception {
        Path reportDirectory = requiredPhase2RPath("reportDirectory");
        Path databaseRoot = requiredPhase2RPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        DelosBenchmarkConfig config =
                new DelosBenchmarkConfig(rowCount, payloadSize, SEED, commitBatchSize);
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f07-embedded-jfr").toAbsolutePath()
                + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            new DelosJdbcBenchmarkScenario(
                    setup, "p2s_heap", "", true, config).prepare();
            new DelosJdbcBenchmarkScenario(
                    setup, "p2s_mvcc", " using delos_mvcc", true, config).prepare();
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2S_HEAP");
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2S_MVCC");
            setup.commit();
        }

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put(
                "heap-input", phase2QSortSql("DELOS_BENCH_P2S_HEAP", false, true));
        sqlByVariant.put(
                "mvcc-input", phase2QSortSql("DELOS_BENCH_P2S_MVCC", false, true));
        sqlByVariant.put(
                "heap-sort", phase2QSortSql("DELOS_BENCH_P2S_HEAP", true, false));
        sqlByVariant.put(
                "mvcc-sort", phase2QSortSql("DELOS_BENCH_P2S_MVCC", true, false));

        Configuration profile = Configuration.getConfiguration("profile");
        StringBuilder summary = new StringBuilder();
        summary.append("DelosDB Phase-2S F07 embedded 8-client JFR attribution\n")
                .append("diagnosticOnly=true\n")
                .append("rows=").append(rowCount).append('\n')
                .append("payloadSize=").append(payloadSize).append('\n')
                .append("clients=8\n");
        long inputReferenceFingerprint = Long.MIN_VALUE;
        long sortReferenceFingerprint = Long.MIN_VALUE;
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            String variant = entry.getKey();
            boolean ordered = variant.endsWith("-sort");
            Path recordingPath = reportDirectory.resolve(variant + "-8c.jfr");
            Phase2RConcurrencyMeasurement measurement;
            try (Recording recording = new Recording(profile)) {
                recording.setName("DelosDB-F07-" + variant + "-8c");
                recording.start();
                measurement = phase2RMeasureConcurrent(
                        jdbcUrl,
                        entry.getValue(),
                        variant + "-8c",
                        rowCount,
                        ordered,
                        8);
                recording.stop();
                recording.dump(recordingPath);
            }
            long referenceFingerprint = ordered
                    ? sortReferenceFingerprint
                    : inputReferenceFingerprint;
            if (referenceFingerprint == Long.MIN_VALUE) {
                if (ordered) {
                    sortReferenceFingerprint = measurement.fingerprint();
                } else {
                    inputReferenceFingerprint = measurement.fingerprint();
                }
            } else if (measurement.fingerprint() != referenceFingerprint) {
                throw new IllegalStateException(
                        "Phase-2S semantic fingerprint mismatch for " + variant);
            }
            Distribution d = measurement.distribution();
            double iqr = d.iqr() / d.median();
            double mad = d.mad() / d.median();
            double runRange = measurement.throughputs().stream()
                    .mapToDouble(Double::doubleValue).max().orElseThrow()
                    / measurement.throughputs().stream()
                            .mapToDouble(Double::doubleValue).min().orElseThrow();
            summary.append(variant).append("MedianTxPerSec=")
                    .append(format(d.median())).append('\n')
                    .append(variant).append("IqrToMedian=")
                    .append(format(iqr)).append('\n')
                    .append(variant).append("MadToMedian=")
                    .append(format(mad)).append('\n')
                    .append(variant).append("MaxMinRatio=")
                    .append(format(runRange)).append('\n')
                    .append(variant).append("Jfr=")
                    .append(recordingPath.getFileName()).append('\n');
        }
        summary.append("semanticFingerprintsMatch=true\n");
        Files.writeString(
                reportDirectory.resolve("phase2s-f07-embedded-jfr-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static void runPhase2RF07EmbeddedConcurrency() throws Exception {
        Path reportDirectory = requiredPhase2RPath("reportDirectory");
        Path databaseRoot = requiredPhase2RPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        DelosBenchmarkConfig config =
                new DelosBenchmarkConfig(rowCount, payloadSize, SEED, commitBatchSize);
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f07-embedded-concurrency").toAbsolutePath()
                + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            new DelosJdbcBenchmarkScenario(
                    setup, "p2r_heap", "", true, config).prepare();
            new DelosJdbcBenchmarkScenario(
                    setup, "p2r_mvcc", " using delos_mvcc", true, config).prepare();
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2R_HEAP");
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2R_MVCC");
            setup.commit();
        }

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put(
                "heap-input", phase2QSortSql("DELOS_BENCH_P2R_HEAP", false, true));
        sqlByVariant.put(
                "mvcc-input", phase2QSortSql("DELOS_BENCH_P2R_MVCC", false, true));
        sqlByVariant.put(
                "heap-sort", phase2QSortSql("DELOS_BENCH_P2R_HEAP", true, false));
        sqlByVariant.put(
                "mvcc-sort", phase2QSortSql("DELOS_BENCH_P2R_MVCC", true, false));

        LinkedHashMap<String, Phase2RConcurrencyMeasurement> measurements =
                new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            boolean ordered = entry.getKey().endsWith("-sort");
            for (int clients : List.of(1, 8)) {
                String key = entry.getKey() + "-" + clients + "c";
                measurements.put(
                        key,
                        phase2RMeasureConcurrent(
                                jdbcUrl,
                                entry.getValue(),
                                key,
                                rowCount,
                                ordered,
                                clients));
            }
        }

        phase2RAssertSemantics(measurements);
        phase2RWriteMeasurements(reportDirectory, measurements);

        double heapInputScaling = phase2RScaling(measurements, "heap-input");
        double mvccInputScaling = phase2RScaling(measurements, "mvcc-input");
        double heapSortScaling = phase2RScaling(measurements, "heap-sort");
        double mvccSortScaling = phase2RScaling(measurements, "mvcc-sort");
        double inputRatio1 = phase2RRatio(measurements, "mvcc-input-1c", "heap-input-1c");
        double inputRatio8 = phase2RRatio(measurements, "mvcc-input-8c", "heap-input-8c");
        double sortRatio1 = phase2RRatio(measurements, "mvcc-sort-1c", "heap-sort-1c");
        double sortRatio8 = phase2RRatio(measurements, "mvcc-sort-8c", "heap-sort-8c");

        String classification;
        if (mvccInputScaling < heapInputScaling * 0.75d
                && mvccSortScaling <= heapSortScaling * 0.85d) {
            classification = "MVCC_TABLE_SCAN_CONCURRENCY_PRIMARY";
        } else if (mvccInputScaling >= heapInputScaling * 0.85d
                && mvccSortScaling < heapSortScaling * 0.75d) {
            classification = "MVCC_SORT_CONCURRENCY_PRIMARY";
        } else {
            classification = "MIXED_CONCURRENCY_REQUIRES_JFR";
        }

        String summary = "DelosDB Phase-2R F07 embedded concurrency decomposition\n"
                + "diagnosticOnly=true\n"
                + "rows=" + rowCount + "\n"
                + "payloadSize=" + payloadSize + "\n"
                + "clients=1,8\n"
                + "projectedCurrentRead="
                + Boolean.getBoolean(
                        "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled")
                + "\n"
                + "heapInput1cTxPerSec="
                + format(phase2RMedian(measurements, "heap-input-1c")) + "\n"
                + "heapInput8cTxPerSec="
                + format(phase2RMedian(measurements, "heap-input-8c")) + "\n"
                + "mvccInput1cTxPerSec="
                + format(phase2RMedian(measurements, "mvcc-input-1c")) + "\n"
                + "mvccInput8cTxPerSec="
                + format(phase2RMedian(measurements, "mvcc-input-8c")) + "\n"
                + "heapSort1cTxPerSec="
                + format(phase2RMedian(measurements, "heap-sort-1c")) + "\n"
                + "heapSort8cTxPerSec="
                + format(phase2RMedian(measurements, "heap-sort-8c")) + "\n"
                + "mvccSort1cTxPerSec="
                + format(phase2RMedian(measurements, "mvcc-sort-1c")) + "\n"
                + "mvccSort8cTxPerSec="
                + format(phase2RMedian(measurements, "mvcc-sort-8c")) + "\n"
                + "heapInputScaling=" + format(heapInputScaling) + "\n"
                + "mvccInputScaling=" + format(mvccInputScaling) + "\n"
                + "heapSortScaling=" + format(heapSortScaling) + "\n"
                + "mvccSortScaling=" + format(mvccSortScaling) + "\n"
                + "mvccVsHeapInput1c=" + format(inputRatio1) + "\n"
                + "mvccVsHeapInput8c=" + format(inputRatio8) + "\n"
                + "mvccVsHeapSort1c=" + format(sortRatio1) + "\n"
                + "mvccVsHeapSort8c=" + format(sortRatio8) + "\n"
                + "semanticFingerprintsMatch=true\n"
                + "measurementStatus=" + phase2RStatus(measurements) + "\n"
                + "runRangeStatus=" + phase2RRunRangeStatus(measurements) + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(
                reportDirectory.resolve("phase2r-f07-embedded-concurrency-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Phase2RConcurrencyMeasurement phase2RMeasureConcurrent(
            String jdbcUrl,
            String sql,
            String variant,
            int expectedRows,
            boolean ordered,
            int clients) throws Exception {
        int warmupRounds = 3;
        int measuredRounds = 7;
        int executionsPerClient = clients == 1 ? 3 : 2;
        List<Connection> connections = new ArrayList<>();
        List<PreparedStatement> statements = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clients);
        try {
            for (int client = 0; client < clients; client++) {
                Connection connection = openPhase2AConnection(jdbcUrl);
                connections.add(connection);
                statements.add(connection.prepareStatement(sql));
            }
            long fingerprint = phase2QExecute(statements.get(0), expectedRows, ordered);
            for (int warmup = 0; warmup < warmupRounds; warmup++) {
                phase2RConcurrentRound(
                        executor,
                        statements,
                        expectedRows,
                        ordered,
                        fingerprint,
                        executionsPerClient,
                        false);
            }
            List<Double> throughputs = new ArrayList<>();
            for (int round = 0; round < measuredRounds; round++) {
                throughputs.add(phase2RConcurrentRound(
                        executor,
                        statements,
                        expectedRows,
                        ordered,
                        fingerprint,
                        executionsPerClient,
                        true));
            }
            Distribution distribution = distribution(throughputs);
            DelosMeasurementValidityContract.DispersionDecision decision =
                    DelosMeasurementValidityContract.classifyCustom(
                            distribution.iqr() / distribution.median(),
                            distribution.mad() / distribution.median());
            return new Phase2RConcurrencyMeasurement(
                    fingerprint, throughputs, distribution, decision);
        } finally {
            executor.shutdownNow();
            for (PreparedStatement statement : statements) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // Diagnostic cleanup only.
                }
            }
            for (Connection connection : connections) {
                try {
                    connection.rollback();
                } finally {
                    connection.close();
                }
            }
        }
    }

    static double phase2RConcurrentRound(
            ExecutorService executor,
            List<PreparedStatement> statements,
            int expectedRows,
            boolean ordered,
            long fingerprint,
            int executionsPerClient,
            boolean measure) throws Exception {
        int clients = statements.size();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (PreparedStatement statement : statements) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int execution = 0; execution < executionsPerClient; execution++) {
                    long actual = phase2QExecute(statement, expectedRows, ordered);
                    if (actual != fingerprint) {
                        throw new IllegalStateException("Phase-2R semantic drift");
                    }
                }
                return fingerprint;
            }));
        }
        if (!ready.await(30L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Phase-2R clients did not reach start barrier");
        }
        long started = System.nanoTime();
        start.countDown();
        for (Future<Long> future : futures) {
            future.get(120L, TimeUnit.SECONDS);
        }
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0d;
        if (!measure) {
            return 0.0d;
        }
        return (clients * executionsPerClient) / elapsedSeconds;
    }

    static void phase2RAssertSemantics(
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        long input = measurements.get("heap-input-1c").fingerprint();
        for (String key : List.of("heap-input-8c", "mvcc-input-1c", "mvcc-input-8c")) {
            if (measurements.get(key).fingerprint() != input) {
                throw new IllegalStateException("Phase-2R input semantic drift for " + key);
            }
        }
        long sorted = measurements.get("heap-sort-1c").fingerprint();
        for (String key : List.of("heap-sort-8c", "mvcc-sort-1c", "mvcc-sort-8c")) {
            if (measurements.get(key).fingerprint() != sorted) {
                throw new IllegalStateException("Phase-2R sort semantic drift for " + key);
            }
        }
    }

    static void phase2RWriteMeasurements(
            Path reportDirectory,
            Map<String, Phase2RConcurrencyMeasurement> measurements) throws IOException {
        StringBuilder samples = new StringBuilder("variant\tround\tthroughputTxPerSec\n");
        StringBuilder dispersion = new StringBuilder(
                "variant\tmedianTxPerSec\tiqrToMedian\tmadToMedian\tgoverningDispersion"
                        + "\tmaxMinRatio\trunRangeStatus\tstatus\n");
        for (Map.Entry<String, Phase2RConcurrencyMeasurement> entry : measurements.entrySet()) {
            for (int i = 0; i < entry.getValue().throughputs().size(); i++) {
                samples.append(entry.getKey()).append('\t').append(i + 1).append('\t')
                        .append(format(entry.getValue().throughputs().get(i))).append('\n');
            }
            Phase2RConcurrencyMeasurement measurement = entry.getValue();
            Distribution distribution = measurement.distribution();
            double maxMinRatio = distribution.min() == 0.0d
                    ? Double.POSITIVE_INFINITY
                    : distribution.max() / distribution.min();
            double iqrRatio = distribution.iqr() / distribution.median();
            double madRatio = distribution.mad() / distribution.median();
            dispersion.append(entry.getKey()).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(iqrRatio)).append('\t')
                    .append(format(madRatio)).append('\t')
                    .append(format(Math.max(iqrRatio, madRatio))).append('\t')
                    .append(format(maxMinRatio)).append('\t')
                    .append(maxMinRatio <= 1.20d ? "VALID" : "INVALID").append('\t')
                    .append(measurement.decision().status()).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("phase2r-repeated-throughput-samples.tsv"),
                samples.toString(),
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("phase2r-dispersion.tsv"),
                dispersion.toString(),
                StandardCharsets.UTF_8);
    }

    static double phase2RScaling(
            Map<String, Phase2RConcurrencyMeasurement> measurements, String prefix) {
        return phase2RMedian(measurements, prefix + "-8c")
                / phase2RMedian(measurements, prefix + "-1c");
    }

    static double phase2RRatio(
            Map<String, Phase2RConcurrencyMeasurement> measurements,
            String numerator,
            String denominator) {
        return phase2RMedian(measurements, numerator)
                / phase2RMedian(measurements, denominator);
    }

    static Path requiredPhase2RPath(String key) {
        String prefix = "delosdb.phase2.f07EmbeddedConcurrency.";
        String value = System.getProperty(prefix + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + prefix + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2QF07SortDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2QPath("reportDirectory");
        Path databaseRoot = requiredPhase2QPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        DelosBenchmarkConfig config =
                new DelosBenchmarkConfig(rowCount, payloadSize, SEED, commitBatchSize);
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f07-sort-decomposition").toAbsolutePath()
                + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            new DelosJdbcBenchmarkScenario(
                    setup, "p2q_heap", "", true, config).prepare();
            new DelosJdbcBenchmarkScenario(
                    setup, "p2q_mvcc", " using delos_mvcc", true, config).prepare();
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2Q_HEAP");
            phase2BUpdateStatistics(setup, "DELOS_BENCH_P2Q_MVCC");
            setup.commit();
        }

        String heapTable = "DELOS_BENCH_P2Q_HEAP";
        String mvccTable = "DELOS_BENCH_P2Q_MVCC";
        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put("heap-input", phase2QSortSql(heapTable, false, true));
        sqlByVariant.put("mvcc-input", phase2QSortSql(mvccTable, false, true));
        sqlByVariant.put("heap-natural-sort", phase2QSortSql(heapTable, true, false));
        sqlByVariant.put("mvcc-natural-sort", phase2QSortSql(mvccTable, true, false));
        sqlByVariant.put("heap-forced-table-sort", phase2QSortSql(heapTable, true, true));
        sqlByVariant.put("mvcc-forced-table-sort", phase2QSortSql(mvccTable, true, true));

        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        LinkedHashMap<String, Phase2PMeasurement> measurements = new LinkedHashMap<>();
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                ExplainCapture analyze =
                        capturePhase2NExplain(connection, entry.getValue(), true);
                analyzeByVariant.put(entry.getKey(), analyze);
                writePhase2ACapture(
                        reportDirectory, entry.getKey() + "-explain-analyze", analyze);
            }
            for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                boolean ordered = entry.getKey().contains("sort");
                measurements.put(
                        entry.getKey(),
                        phase2QMeasure(
                                connection, entry.getValue(), entry.getKey(), rowCount, ordered));
            }
            connection.rollback();
        }

        phase2QAssertSemantics(measurements);
        phase2QWriteMeasurements(reportDirectory, measurements);

        StringBuilder plans = new StringBuilder(
                "variant\tplanShape\texplainAnalyzeWallMillis\ttotalOpenMillis"
                        + "\ttotalNextMillis\ttotalOpens\n");
        for (Map.Entry<String, ExplainCapture> entry : analyzeByVariant.entrySet()) {
            ExplainCapture analyze = entry.getValue();
            plans.append(entry.getKey()).append('\t')
                    .append(phase2NPlanShape(analyze.text())).append('\t')
                    .append(analyze.wallMillis()).append('\t')
                    .append(sumPhase2AField(analyze.text(), "openMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "nextMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "opens")).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("phase2q-plan-summary.tsv"),
                plans.toString(), StandardCharsets.UTF_8);

        Phase2PMeasurement heapInput = measurements.get("heap-input");
        Phase2PMeasurement mvccInput = measurements.get("mvcc-input");
        Phase2PMeasurement heapNatural = measurements.get("heap-natural-sort");
        Phase2PMeasurement mvccNatural = measurements.get("mvcc-natural-sort");
        Phase2PMeasurement heapForced = measurements.get("heap-forced-table-sort");
        Phase2PMeasurement mvccForced = measurements.get("mvcc-forced-table-sort");
        String heapNaturalShape = phase2NPlanShape(
                analyzeByVariant.get("heap-natural-sort").text());
        String mvccNaturalShape = phase2NPlanShape(
                analyzeByVariant.get("mvcc-natural-sort").text());
        String heapForcedShape = phase2NPlanShape(
                analyzeByVariant.get("heap-forced-table-sort").text());
        String mvccForcedShape = phase2NPlanShape(
                analyzeByVariant.get("mvcc-forced-table-sort").text());

        String classification;
        if (!mvccNaturalShape.equals(mvccForcedShape)
                && mvccForced.distribution().median()
                < mvccNatural.distribution().median() * 0.90d) {
            classification = "MVCC_NATURAL_ACCESS_PATH_COSTING_PRIMARY";
        } else {
            double inputRatio =
                    mvccInput.distribution().median() / heapInput.distribution().median();
            double forcedSortRatio =
                    mvccForced.distribution().median() / heapForced.distribution().median();
            if (inputRatio >= 1.50d && forcedSortRatio <= inputRatio * 1.20d) {
                classification = "MVCC_SORT_INPUT_PRIMARY";
            } else if (forcedSortRatio >= inputRatio * 1.35d) {
                classification = "MVCC_SORT_MATERIALIZATION_PRIMARY";
            } else {
                classification = "MIXED_REQUIRES_JFR_ATTRIBUTION";
            }
        }

        String summary = "DelosDB Phase-2Q F07 sort decomposition\n"
                + "diagnosticOnly=true\n"
                + "rows=" + rowCount + "\n"
                + "payloadSize=" + payloadSize + "\n"
                + "heapInputPlanShape="
                + phase2NPlanShape(analyzeByVariant.get("heap-input").text()) + "\n"
                + "mvccInputPlanShape="
                + phase2NPlanShape(analyzeByVariant.get("mvcc-input").text()) + "\n"
                + "heapNaturalSortPlanShape=" + heapNaturalShape + "\n"
                + "mvccNaturalSortPlanShape=" + mvccNaturalShape + "\n"
                + "heapForcedSortPlanShape=" + heapForcedShape + "\n"
                + "mvccForcedSortPlanShape=" + mvccForcedShape + "\n"
                + "heapInputMedianMillis=" + format(heapInput.distribution().median()) + "\n"
                + "mvccInputMedianMillis=" + format(mvccInput.distribution().median()) + "\n"
                + "inputMvccVsHeapRatio="
                + format(mvccInput.distribution().median() / heapInput.distribution().median()) + "\n"
                + "heapNaturalSortMedianMillis=" + format(heapNatural.distribution().median()) + "\n"
                + "mvccNaturalSortMedianMillis=" + format(mvccNatural.distribution().median()) + "\n"
                + "naturalSortMvccVsHeapRatio="
                + format(mvccNatural.distribution().median() / heapNatural.distribution().median()) + "\n"
                + "heapForcedSortMedianMillis=" + format(heapForced.distribution().median()) + "\n"
                + "mvccForcedSortMedianMillis=" + format(mvccForced.distribution().median()) + "\n"
                + "forcedSortMvccVsHeapRatio="
                + format(mvccForced.distribution().median() / heapForced.distribution().median()) + "\n"
                + "heapSortPenalty="
                + format(heapForced.distribution().median() / heapInput.distribution().median()) + "\n"
                + "mvccSortPenalty="
                + format(mvccForced.distribution().median() / mvccInput.distribution().median()) + "\n"
                + "mvccForcedVsNaturalRatio="
                + format(mvccForced.distribution().median()
                        / mvccNatural.distribution().median()) + "\n"
                + "semanticFingerprintsMatch=true\n"
                + "measurementStatus=" + phase2QStatus(measurements) + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(
                reportDirectory.resolve("phase2q-f07-sort-decomposition-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static String phase2QSortSql(
            String table, boolean ordered, boolean forceTableScan) {
        StringBuilder sql = new StringBuilder("select id, quantity from ").append(table);
        if (forceTableScan) {
            sql.append(" --DERBY-PROPERTIES index=null\n");
        }
        if (ordered) {
            sql.append(" order by quantity desc, id");
        }
        return sql.toString();
    }

    static Phase2PMeasurement phase2QMeasure(
            Connection connection,
            String sql,
            String variant,
            int expectedRows,
            boolean ordered) throws Exception {
        int warmups = 8;
        int measuredRounds = 9;
        int executionsPerSample = 3;
        List<Double> samples = new ArrayList<>();
        long fingerprint;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            fingerprint = phase2QExecute(statement, expectedRows, ordered);
            for (int warmup = 0; warmup < warmups; warmup++) {
                long actual = phase2QExecute(statement, expectedRows, ordered);
                if (actual != fingerprint) {
                    throw new IllegalStateException(
                            "Phase-2Q warmup semantic drift for " + variant);
                }
            }
            for (int round = 0; round < measuredRounds; round++) {
                long started = System.nanoTime();
                for (int execution = 0; execution < executionsPerSample; execution++) {
                    long actual = phase2QExecute(statement, expectedRows, ordered);
                    if (actual != fingerprint) {
                        throw new IllegalStateException(
                                "Phase-2Q measured semantic drift for " + variant);
                    }
                }
                samples.add((System.nanoTime() - started)
                        / (executionsPerSample * 1_000_000.0d));
            }
        }
        Distribution distribution = distribution(samples);
        DelosMeasurementValidityContract.DispersionDecision decision =
                DelosMeasurementValidityContract.classifyCustom(
                        distribution.iqr() / distribution.median(),
                        distribution.mad() / distribution.median());
        return new Phase2PMeasurement(fingerprint, samples, distribution, decision);
    }

    static long phase2QExecute(
            PreparedStatement statement, int expectedRows, boolean ordered) throws SQLException {
        int rows = 0;
        long fingerprint = ordered ? 1L : 0L;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long tuple = mix(
                        mix(0x9E3779B97F4A7C15L, resultSet.getInt(1)),
                        resultSet.getInt(2));
                fingerprint = ordered ? mix(fingerprint, tuple) : fingerprint + tuple;
                rows++;
            }
        }
        if (rows != expectedRows) {
            throw new SQLException(
                    "Phase-2Q row-count drift: expected=" + expectedRows + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    static void phase2QAssertSemantics(
            Map<String, Phase2PMeasurement> measurements) {
        if (measurements.get("heap-input").fingerprint()
                != measurements.get("mvcc-input").fingerprint()) {
            throw new IllegalStateException("Phase-2Q Heap/MVCC input semantic drift");
        }
        long sorted = measurements.get("heap-natural-sort").fingerprint();
        for (String variant : List.of(
                "mvcc-natural-sort", "heap-forced-table-sort", "mvcc-forced-table-sort")) {
            if (measurements.get(variant).fingerprint() != sorted) {
                throw new IllegalStateException(
                        "Phase-2Q sorted semantic drift for " + variant);
            }
        }
    }

    static void phase2QWriteMeasurements(
            Path reportDirectory,
            Map<String, Phase2PMeasurement> measurements) throws IOException {
        StringBuilder samples = new StringBuilder("variant\tround\telapsedMillis\n");
        StringBuilder dispersion = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tgoverningDispersion\tstatus\n");
        for (Map.Entry<String, Phase2PMeasurement> entry : measurements.entrySet()) {
            for (int i = 0; i < entry.getValue().samples().size(); i++) {
                samples.append(entry.getKey()).append('\t').append(i + 1).append('\t')
                        .append(format(entry.getValue().samples().get(i))).append('\n');
            }
            distributionRow(dispersion, entry.getKey(), entry.getValue());
        }
        Files.writeString(
                reportDirectory.resolve("phase2q-repeated-execution-samples.tsv"),
                samples.toString(), StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("phase2q-dispersion.tsv"),
                dispersion.toString(), StandardCharsets.UTF_8);
    }

    static DelosMeasurementValidityContract.Status phase2QStatus(
            Map<String, Phase2PMeasurement> measurements) {
        return DelosMeasurementValidityContract.combine(
                measurements.values().stream()
                        .map(measurement -> measurement.decision().status())
                        .toArray(DelosMeasurementValidityContract.Status[]::new));
    }

    static Path requiredPhase2QPath(String key) {
        String value = System.getProperty(PHASE2Q_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2Q_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2NF04F06RelationalDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2NPath("reportDirectory");
        Path databaseRoot = requiredPhase2NPath("databaseRoot");
        boolean gen2C3Enabled = Boolean.getBoolean("delosdb.experimental.mvccGen2B.pk.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C1.history.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C2.archivedUndo.enabled");
        boolean projectedCurrentRead = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean baseFetchPrefetch = Boolean.getBoolean(
                "delosdb.experimental.mvccBaseFetchPagePrefetch");
        boolean physicalScanCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalScanCost.enabled", "true"));
        boolean joinPlanFalsification = Boolean.getBoolean(
                PHASE2N_PREFIX + "joinPlanFalsification");
        if (Boolean.getBoolean(PHASE2N_PREFIX + "requireGen2C3")
                && (!gen2C3Enabled || projectedCurrentRead || baseFetchPrefetch)) {
            throw new IllegalStateException(
                    "Phase-2N Gen2-C3 diagnostic requires B1/C1/C2 enabled "
                            + "with projected-current and page-local prefetch disabled");
        }
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        int warmups = 4;
        int warmupExecutionsPerVisit = 32;
        int measuredRounds = 9;
        int measuredExecutionsPerSample = 128;
        String database = databaseRoot.resolve("f04-f06-relational-decomposition").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";
        DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                rowCount, payloadSize, SEED, commitBatchSize);

        String heapBase;
        String mvccBase;
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            DelosJdbcBenchmarkScenario heap = new DelosJdbcBenchmarkScenario(
                    setup, "phase2n_heap", "", false, config);
            heap.prepare();
            heapBase = heap.tableName();
            prepareJoinDimensionFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareHighCardGroupFixture(setup, heapBase, "", rowCount, commitBatchSize);

            DelosJdbcBenchmarkScenario mvcc = new DelosJdbcBenchmarkScenario(
                    setup, "phase2n_mvcc", " using delos_mvcc", false, config);
            mvcc.prepare();
            mvccBase = mvcc.tableName();
            prepareJoinDimensionFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            prepareHighCardGroupFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);

            for (String table : List.of(
                    heapBase,
                    joinDimensionTableName(heapBase),
                    highCardGroupTableName(heapBase),
                    mvccBase,
                    joinDimensionTableName(mvccBase),
                    highCardGroupTableName(mvccBase))) {
                phase2BUpdateStatistics(setup, table);
            }
            setup.commit();
        }

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        addPhase2NVariants(sqlByVariant, "heap", heapBase);
        addPhase2NVariants(sqlByVariant, "mvcc", mvccBase);
        if (joinPlanFalsification) {
            try (Connection connection = openPhase2AConnection(jdbcUrl)) {
                addPhase2NForcedJoinVariants(sqlByVariant, "heap", heapBase, connection);
                addPhase2NForcedJoinVariants(sqlByVariant, "mvcc", mvccBase, connection);
                connection.rollback();
            }
        }

        LinkedHashMap<String, Integer> expectedRowsByVariant = new LinkedHashMap<>();
        for (String provider : List.of("heap", "mvcc")) {
            expectedRowsByVariant.put(provider + "-join", 1_000);
            expectedRowsByVariant.put(provider + "-join-base-range", 1_000);
            expectedRowsByVariant.put(provider + "-join-dimension-scan", 1_000);
            expectedRowsByVariant.put(provider + "-group-input", 10_000);
            expectedRowsByVariant.put(provider + "-group-unordered", 1_000);
            expectedRowsByVariant.put(provider + "-group-ordered", 1_000);
            if (joinPlanFalsification) {
                expectedRowsByVariant.put(provider + "-join-forced-base-first-hash", 1_000);
                expectedRowsByVariant.put(provider + "-join-forced-dim-first-hash", 1_000);
            }
        }

        StringBuilder queryShapes = new StringBuilder();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            queryShapes.append("=== ").append(entry.getKey()).append(" ===\n")
                    .append(entry.getValue()).append("\n\n");
        }
        Files.writeString(reportDirectory.resolve("query-shapes.txt"),
                queryShapes.toString(), StandardCharsets.UTF_8);

        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        LinkedHashMap<String, Long> semanticByVariant = new LinkedHashMap<>();
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                ExplainCapture explain = capturePhase2NExplain(connection, entry.getValue(), false);
                ExplainCapture analyze = capturePhase2NExplain(connection, entry.getValue(), true);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain", explain);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain-analyze", analyze);
                analyzeByVariant.put(entry.getKey(), analyze);

                long fingerprint;
                try (PreparedStatement statement = connection.prepareStatement(entry.getValue())) {
                    fingerprint = executePhase2NRead(
                            statement, entry.getKey(), expectedRowsByVariant.get(entry.getKey()));
                }
                semanticByVariant.put(entry.getKey(), fingerprint);
            }
            connection.rollback();
        }

        assertPhase2NProviderSemantics(semanticByVariant, "join");
        assertPhase2NProviderSemantics(semanticByVariant, "join-base-range");
        assertPhase2NProviderSemantics(semanticByVariant, "join-dimension-scan");
        assertPhase2NProviderSemantics(semanticByVariant, "group-input");
        assertPhase2NProviderSemantics(semanticByVariant, "group-unordered");
        assertPhase2NProviderSemantics(semanticByVariant, "group-ordered");
        if (joinPlanFalsification) {
            assertPhase2NJoinPlanSemantics(semanticByVariant);
        }
        if (!semanticByVariant.get("heap-group-unordered")
                .equals(semanticByVariant.get("heap-group-ordered"))
                || !semanticByVariant.get("mvcc-group-unordered")
                        .equals(semanticByVariant.get("mvcc-group-ordered"))) {
            throw new IllegalStateException(
                    "Phase-2N ordered/unordered GROUP BY semantic drift");
        }

        LinkedHashMap<String, List<Double>> samples = new LinkedHashMap<>();
        for (String variant : sqlByVariant.keySet()) {
            samples.put(variant, new ArrayList<>());
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            LinkedHashMap<String, PreparedStatement> statements = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                    statements.put(entry.getKey(), connection.prepareStatement(entry.getValue()));
                }
                List<String> variants = new ArrayList<>(sqlByVariant.keySet());
                for (int round = -warmups; round < measuredRounds; round++) {
                    int rotation = Math.floorMod(round + warmups, variants.size());
                    for (int offset = 0; offset < variants.size(); offset++) {
                        String variant = variants.get((rotation + offset) % variants.size());
                        int executions = round >= 0
                                ? measuredExecutionsPerSample
                                : warmupExecutionsPerVisit;
                        long expectedFingerprint = semanticByVariant.get(variant);
                        long started = System.nanoTime();
                        for (int execution = 0; execution < executions; execution++) {
                            long fingerprint = executePhase2NRead(
                                    statements.get(variant),
                                    variant,
                                    expectedRowsByVariant.get(variant));
                            if (fingerprint != expectedFingerprint) {
                                throw new IllegalStateException(
                                        "Phase-2N measured semantic drift for " + variant
                                                + ": expected=" + expectedFingerprint
                                                + ", actual=" + fingerprint);
                            }
                        }
                        long elapsed = System.nanoTime() - started;
                        if (round >= 0) {
                            samples.get(variant).add(elapsed / (executions * 1_000_000.0d));
                        }
                    }
                }
                connection.rollback();
            } finally {
                SQLException closeFailure = null;
                for (PreparedStatement statement : statements.values()) {
                    try {
                        statement.close();
                    } catch (SQLException failure) {
                        if (closeFailure == null) {
                            closeFailure = failure;
                        } else {
                            closeFailure.addSuppressed(failure);
                        }
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }

        StringBuilder sampleTsv = new StringBuilder("variant\tround\telapsedMillis\n");
        LinkedHashMap<String, Distribution> distributions = new LinkedHashMap<>();
        LinkedHashMap<String, DelosMeasurementValidityContract.DispersionDecision> decisions =
                new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                sampleTsv.append(entry.getKey()).append('\t').append(i + 1).append('\t')
                        .append(format(entry.getValue().get(i))).append('\n');
            }
            Distribution distribution = distribution(entry.getValue());
            distributions.put(entry.getKey(), distribution);
            decisions.put(entry.getKey(), DelosMeasurementValidityContract.classifyCustom(
                    distribution.iqr() / distribution.median(),
                    distribution.mad() / distribution.median()));
        }
        Files.writeString(reportDirectory.resolve("phase2n-repeated-execution-samples.tsv"),
                sampleTsv.toString(), StandardCharsets.UTF_8);

        StringBuilder dispersionTsv = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tgoverningDispersion\tstatus\n");
        for (String variant : sqlByVariant.keySet()) {
            Distribution distribution = distributions.get(variant);
            DelosMeasurementValidityContract.DispersionDecision decision = decisions.get(variant);
            dispersionTsv.append(variant).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(decision.iqrToMedian())).append('\t')
                    .append(format(decision.madToMedian())).append('\t')
                    .append(format(decision.governingRatio())).append('\t')
                    .append(decision.status()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2n-dispersion.tsv"),
                dispersionTsv.toString(), StandardCharsets.UTF_8);

        StringBuilder operatorTsv = new StringBuilder(
                "variant\texplainAnalyzeWallMillis\tplanShape\tindexToBaseNextMillis\tindexScanNextMillis"
                        + "\ttotalOpenMillis\ttotalNextMillis\ttotalOpens\n");
        for (String variant : sqlByVariant.keySet()) {
            ExplainCapture analyze = analyzeByVariant.get(variant);
            operatorTsv.append(variant).append('\t').append(analyze.wallMillis()).append('\t')
                    .append(phase2NPlanShape(analyze.text())).append('\t')
                    .append(phase2EIndexToBaseNextMillis(analyze.text())).append('\t')
                    .append(phase2EIndexScanNextMillis(analyze.text())).append('\t')
                    .append(sumPhase2AField(analyze.text(), "openMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "nextMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "opens")).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2n-operator-summary.tsv"),
                operatorTsv.toString(), StandardCharsets.UTF_8);

        DelosMeasurementValidityContract.Status combinedStatus =
                DelosMeasurementValidityContract.combine(
                        decisions.values().stream()
                                .map(DelosMeasurementValidityContract.DispersionDecision::status)
                                .toArray(DelosMeasurementValidityContract.Status[]::new));
        double worstGoverningDispersion = decisions.values().stream()
                .mapToDouble(DelosMeasurementValidityContract.DispersionDecision::governingRatio)
                .max()
                .orElseThrow();

        double heapJoin = distributions.get("heap-join").median();
        double mvccJoin = distributions.get("mvcc-join").median();
        double heapBaseRange = distributions.get("heap-join-base-range").median();
        double mvccBaseRange = distributions.get("mvcc-join-base-range").median();
        double heapDimScan = distributions.get("heap-join-dimension-scan").median();
        double mvccDimScan = distributions.get("mvcc-join-dimension-scan").median();
        double heapGroupInput = distributions.get("heap-group-input").median();
        double mvccGroupInput = distributions.get("mvcc-group-input").median();
        double heapGroupUnordered = distributions.get("heap-group-unordered").median();
        double mvccGroupUnordered = distributions.get("mvcc-group-unordered").median();
        double heapGroupOrdered = distributions.get("heap-group-ordered").median();
        double mvccGroupOrdered = distributions.get("mvcc-group-ordered").median();

        String summary = "DelosDB Phase-2N F04/F06 relational decomposition\n"
                + "diagnosticOnly=true\n"
                + "gen2C3Enabled=" + gen2C3Enabled + "\n"
                + "projectedCurrentRead=" + projectedCurrentRead + "\n"
                + "baseFetchPagePrefetch=" + baseFetchPrefetch + "\n"
                + "physicalScanCost=" + physicalScanCost + "\n"
                + "joinPlanFalsification=" + joinPlanFalsification + "\n"
                + "rows=" + rowCount + "\n"
                + "payloadSize=" + payloadSize + "\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "warmupExecutionsPerVisit=" + warmupExecutionsPerVisit + "\n"
                + "measuredRoundsPerVariant=" + measuredRounds + "\n"
                + "measuredExecutionsPerSample=" + measuredExecutionsPerSample + "\n"
                + "heapJoinMedianMillis=" + format(heapJoin) + "\n"
                + "mvccJoinMedianMillis=" + format(mvccJoin) + "\n"
                + "joinMvccVsHeapRatio=" + format(mvccJoin / heapJoin) + "\n"
                + "heapJoinBaseRangeMedianMillis=" + format(heapBaseRange) + "\n"
                + "mvccJoinBaseRangeMedianMillis=" + format(mvccBaseRange) + "\n"
                + "joinBaseRangeMvccVsHeapRatio=" + format(mvccBaseRange / heapBaseRange) + "\n"
                + "heapJoinDimensionScanMedianMillis=" + format(heapDimScan) + "\n"
                + "mvccJoinDimensionScanMedianMillis=" + format(mvccDimScan) + "\n"
                + "joinDimensionScanMvccVsHeapRatio=" + format(mvccDimScan / heapDimScan) + "\n"
                + "heapJoinVsInputSumRatio=" + format(heapJoin / (heapBaseRange + heapDimScan)) + "\n"
                + "mvccJoinVsInputSumRatio=" + format(mvccJoin / (mvccBaseRange + mvccDimScan)) + "\n"
                + "heapGroupInputMedianMillis=" + format(heapGroupInput) + "\n"
                + "mvccGroupInputMedianMillis=" + format(mvccGroupInput) + "\n"
                + "groupInputMvccVsHeapRatio=" + format(mvccGroupInput / heapGroupInput) + "\n"
                + "heapGroupUnorderedMedianMillis=" + format(heapGroupUnordered) + "\n"
                + "mvccGroupUnorderedMedianMillis=" + format(mvccGroupUnordered) + "\n"
                + "groupUnorderedMvccVsHeapRatio=" + format(mvccGroupUnordered / heapGroupUnordered) + "\n"
                + "heapGroupOrderedMedianMillis=" + format(heapGroupOrdered) + "\n"
                + "mvccGroupOrderedMedianMillis=" + format(mvccGroupOrdered) + "\n"
                + "groupOrderedMvccVsHeapRatio=" + format(mvccGroupOrdered / heapGroupOrdered) + "\n"
                + "heapGroupVsInputRatio=" + format(heapGroupUnordered / heapGroupInput) + "\n"
                + "mvccGroupVsInputRatio=" + format(mvccGroupUnordered / mvccGroupInput) + "\n"
                + "heapOrderByGroupPenalty=" + format(heapGroupOrdered / heapGroupUnordered) + "\n"
                + "mvccOrderByGroupPenalty=" + format(mvccGroupOrdered / mvccGroupUnordered) + "\n"
                + phase2NJoinPlanSummary(joinPlanFalsification, distributions)
                + "worstGoverningDispersion=" + format(worstGoverningDispersion) + "\n"
                + "measurementStatus=" + combinedStatus + "\n"
                + "classification=EVIDENCE_READY_FOR_F04_F06_PHASE2_CLASSIFICATION\n";
        Files.writeString(reportDirectory.resolve(
                        "phase2n-f04-f06-relational-decomposition-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);
    }

    static void runPhase2OF06GroupInputJfrAttribution() throws Exception {
        Path reportDirectory = requiredPhase2OPath("reportDirectory");
        Path databaseRoot = requiredPhase2OPath("databaseRoot");
        String provider = requiredPhase2OProvider();
        boolean mvcc = "mvcc".equals(provider);
        Phase2OFeatures features = phase2OFeatures(mvcc);
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 100;
        int warmups = 128;
        int profileSeconds = 12;
        String base = mvcc ? "P2O_MVCC" : "P2O_HEAP";
        String suffix = mvcc ? " using delos_mvcc" : "";
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f06-group-input-" + provider) + ";create=true";
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareHighCardGroupFixture(setup, base, suffix, rowCount, commitBatchSize);
            phase2BUpdateStatistics(setup, highCardGroupTableName(base));
            setup.commit();
        }

        String variant = provider + "-group-input";
        String groupTable = highCardGroupTableName(base);
        String naturalSql = "select group_key, quantity from " + groupTable;
        String profileSql = "select group_key, quantity from " + groupTable
                + " --DERBY-PROPERTIES index=null\n";
        String canonicalGroupSql = "select group_key, count(*), sum(quantity) from " + groupTable
                + " group by group_key order by group_key";
        ExplainCapture naturalAnalyze;
        ExplainCapture profileAnalyze;
        ExplainCapture canonicalGroupAnalyze;
        long semanticFingerprint;
        long profileFingerprint;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            naturalAnalyze = capturePhase2NExplain(connection, naturalSql, true);
            profileAnalyze = capturePhase2NExplain(connection, profileSql, true);
            canonicalGroupAnalyze = capturePhase2NExplain(connection, canonicalGroupSql, true);
            try (PreparedStatement statement = connection.prepareStatement(naturalSql)) {
                semanticFingerprint = executePhase2NRead(statement, variant, rowCount);
            }
            try (PreparedStatement statement = connection.prepareStatement(profileSql)) {
                profileFingerprint = executePhase2NRead(statement, variant, rowCount);
            }
            connection.rollback();
        }
        String naturalPlanShape = phase2NPlanShape(naturalAnalyze.text());
        String profilePlanShape = phase2NPlanShape(profileAnalyze.text());
        String canonicalGroupPlanShape = phase2NPlanShape(canonicalGroupAnalyze.text());
        writePhase2ACapture(
                reportDirectory, provider + "-group-input-natural-explain-analyze", naturalAnalyze);
        writePhase2ACapture(
                reportDirectory, provider + "-group-input-profile-explain-analyze", profileAnalyze);
        writePhase2ACapture(
                reportDirectory, provider + "-group-high-card-canonical-explain-analyze",
                canonicalGroupAnalyze);
        if (profileFingerprint != semanticFingerprint) {
            throw new IllegalStateException(
                    "Phase-2O forced table scan changed semantics for " + provider);
        }
        if (!"TABLE_SCAN".equals(profilePlanShape)) {
            throw new IllegalStateException(
                    "Phase-2O index=null control requires TABLE_SCAN but observed "
                            + profilePlanShape);
        }

        Configuration profile = Configuration.getConfiguration("profile");
        Phase2EProfile result = profilePhase2OGroupInput(
                jdbcUrl, profileSql, variant, rowCount, semanticFingerprint, warmups,
                profileSeconds, profile, features.nonHoldableProfile(),
                reportDirectory.resolve(provider + "-group-input.jfr"));
        writePhase2OGroupInputSummary(
                reportDirectory, provider, features.gen2C3Enabled(), features.projectedCurrentRead(),
                features.baseFetchPrefetch(), features.physicalScanCost(),
                features.singlePassCurrentScan(), features.reusableCurrentScanTemplate(),
                features.streamingBulkScan(), features.fastCurrentVisibilityFetch(),
                features.skipCurrentTagFetch(), features.lazyCurrentCreatorFetch(),
                features.lazyCurrentIdentityFetch(), features.nonHoldableProfile(),
                rowCount, warmups, profileSeconds, semanticFingerprint, naturalPlanShape,
                profilePlanShape, canonicalGroupPlanShape, result);
    }

    static Phase2OFeatures phase2OFeatures(boolean mvcc) {
        boolean gen2C3Enabled = Boolean.getBoolean("delosdb.experimental.mvccGen2B.pk.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C1.history.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C2.archivedUndo.enabled");
        boolean projectedCurrentRead = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean baseFetchPrefetch = Boolean.getBoolean(
                "delosdb.experimental.mvccBaseFetchPagePrefetch");
        boolean physicalScanCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalScanCost.enabled", "true"));
        boolean singlePassCurrentScan = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2SinglePassCurrentScan.enabled");
        boolean reusableCurrentScanTemplate = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ReusableCurrentScanTemplate.enabled");
        boolean streamingBulkScan = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2StreamingBulkScan.enabled");
        boolean fastCurrentVisibilityFetch = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2FastCurrentVisibilityFetch.enabled");
        boolean skipCurrentTagFetch = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2SkipCurrentTagFetch.enabled");
        boolean lazyCurrentCreatorFetch = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2LazyCurrentCreatorFetch.enabled");
        boolean lazyCurrentIdentityFetch = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2LazyCurrentIdentityFetch.enabled");
        boolean nonHoldableProfile = Boolean.getBoolean(PHASE2O_PREFIX + "nonHoldableControl");
        if (streamingBulkScan && !nonHoldableProfile) {
            throw new IllegalStateException(
                    "Phase-2O streaming bulk scan requires the non-holdable profile control");
        }
        if (reusableCurrentScanTemplate && !singlePassCurrentScan) {
            throw new IllegalStateException(
                    "Phase-2O reusable CURRENT scan template requires single-pass CURRENT scan");
        }
        if (streamingBulkScan && (!singlePassCurrentScan || !reusableCurrentScanTemplate)) {
            throw new IllegalStateException(
                    "Phase-2O streaming bulk scan requires single-pass and reusable CURRENT scan template");
        }
        if (fastCurrentVisibilityFetch && !streamingBulkScan) {
            throw new IllegalStateException(
                    "Phase-2O fast CURRENT visibility fetch requires streaming bulk scan");
        }
        if (skipCurrentTagFetch && !fastCurrentVisibilityFetch) {
            throw new IllegalStateException(
                    "Phase-2O skipped CURRENT tag fetch requires fast CURRENT visibility fetch");
        }
        if (lazyCurrentCreatorFetch && !skipCurrentTagFetch) {
            throw new IllegalStateException(
                    "Phase-2O lazy CURRENT creator fetch requires skipped CURRENT tag fetch");
        }
        if (lazyCurrentIdentityFetch && !lazyCurrentCreatorFetch) {
            throw new IllegalStateException(
                    "Phase-2O lazy CURRENT identity fetch requires lazy CURRENT creator fetch");
        }
        if (mvcc && (!gen2C3Enabled || projectedCurrentRead || baseFetchPrefetch)) {
            throw new IllegalStateException(
                    "Phase-2O Gen2 profile requires B1/C1/C2 enabled with rejected read experiments off");
        }
        return new Phase2OFeatures(
                gen2C3Enabled, projectedCurrentRead, baseFetchPrefetch, physicalScanCost,
                singlePassCurrentScan, reusableCurrentScanTemplate, streamingBulkScan,
                fastCurrentVisibilityFetch, skipCurrentTagFetch, lazyCurrentCreatorFetch,
                lazyCurrentIdentityFetch, nonHoldableProfile);
    }

    static Phase2EProfile profilePhase2OGroupInput(
            String jdbcUrl,
            String sql,
            String variant,
            int expectedRows,
            long expectedFingerprint,
            int warmups,
            int profileSeconds,
            Configuration profile,
            boolean nonHoldableProfile,
            Path recordingPath) throws Exception {
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            if (nonHoldableProfile) {
                connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
                if (connection.getHoldability() != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                    throw new IllegalStateException(
                            "Phase-2O non-holdable profile control was not applied");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < warmups; i++) {
                    assertPhase2OFingerprint(
                            statement, variant, expectedRows, expectedFingerprint, "warmup", i);
                }
                int iterations = 0;
                long elapsed;
                long started;
                long targetNanos = TimeUnit.SECONDS.toNanos(profileSeconds);
                try (Recording recording = new Recording(profile)) {
                    recording.setName(recordingPath.getFileName().toString());
                    recording.setToDisk(true);
                    recording.start();
                    started = System.nanoTime();
                    do {
                        assertPhase2OFingerprint(
                                statement, variant, expectedRows, expectedFingerprint,
                                "profile", iterations);
                        iterations++;
                        elapsed = System.nanoTime() - started;
                    } while (elapsed < targetNanos);
                    recording.stop();
                    recording.dump(recordingPath);
                }
                connection.rollback();
                return new Phase2EProfile(elapsed / 1_000_000.0d, iterations);
            }
        }
    }

    static void assertPhase2OFingerprint(
            PreparedStatement statement,
            String variant,
            int expectedRows,
            long expectedFingerprint,
            String stage,
            int iteration) throws SQLException {
        long fingerprint = executePhase2NRead(statement, variant, expectedRows);
        if (fingerprint != expectedFingerprint) {
            throw new IllegalStateException(
                    "Phase-2O semantic drift during " + stage + " iteration " + iteration);
        }
    }

    static void writePhase2OGroupInputSummary(
            Path reportDirectory,
            String provider,
            boolean gen2C3Enabled,
            boolean projectedCurrentRead,
            boolean baseFetchPrefetch,
            boolean physicalScanCost,
            boolean singlePassCurrentScan,
            boolean reusableCurrentScanTemplate,
            boolean streamingBulkScan,
            boolean fastCurrentVisibilityFetch,
            boolean skipCurrentTagFetch,
            boolean lazyCurrentCreatorFetch,
            boolean lazyCurrentIdentityFetch,
            boolean nonHoldableProfile,
            int rowCount,
            int warmups,
            int profileSeconds,
            long semanticFingerprint,
            String naturalPlanShape,
            String profilePlanShape,
            String canonicalGroupPlanShape,
            Phase2EProfile result) throws IOException {
        String summary = "DelosDB Phase-2O F06 raw group-input JFR attribution\n"
                + "diagnosticOnly=true\n"
                + "provider=" + provider + "\n"
                + "rows=" + rowCount + "\n"
                + "selectedColumns=group_key,quantity\n"
                + "semanticFingerprint=" + semanticFingerprint + "\n"
                + "naturalPlanShape=" + naturalPlanShape + "\n"
                + "profilePlanShape=" + profilePlanShape + "\n"
                + "canonicalGroupPlanShape=" + canonicalGroupPlanShape + "\n"
                + "profileAccessControl=index=null\n"
                + "gen2C3Enabled=" + gen2C3Enabled + "\n"
                + "projectedCurrentRead=" + projectedCurrentRead + "\n"
                + "baseFetchPagePrefetch=" + baseFetchPrefetch + "\n"
                + "physicalScanCost=" + physicalScanCost + "\n"
                + "singlePassCurrentScan=" + singlePassCurrentScan + "\n"
                + "reusableCurrentScanTemplate=" + reusableCurrentScanTemplate + "\n"
                + "streamingBulkScan=" + streamingBulkScan + "\n"
                + "fastCurrentVisibilityFetch=" + fastCurrentVisibilityFetch + "\n"
                + "skipCurrentTagFetch=" + skipCurrentTagFetch + "\n"
                + "lazyCurrentCreatorFetch=" + lazyCurrentCreatorFetch + "\n"
                + "lazyCurrentIdentityFetch=" + lazyCurrentIdentityFetch + "\n"
                + "profileHoldability="
                + (nonHoldableProfile ? "CLOSE_CURSORS_AT_COMMIT" : "DEFAULT") + "\n"
                + "warmups=" + warmups + "\n"
                + "profileSecondsTarget=" + profileSeconds + "\n"
                + "profileIterations=" + result.iterations() + "\n"
                + "profileElapsedMillis=" + format(result.elapsedMillis()) + "\n"
                + "profileMillisPerExecution=" + format(result.millisPerExecution()) + "\n"
                + "attributionTarget=FORCED_TABLE_SCAN_RAW_TWO_COLUMN_ROW_PRODUCTION\n"
                + "interpretation=JFR_ATTRIBUTION_ONLY_NOT_ACCEPTANCE_PERFORMANCE\n";
        Files.writeString(
                reportDirectory.resolve("phase2o-f06-group-input-jfr-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static void runPhase2PF06CanonicalPlanFalsification() throws Exception {
        Path reportDirectory = requiredPhase2PPath("reportDirectory");
        Path databaseRoot = requiredPhase2PPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 100;
        int expectedRows = Math.min(1000, rowCount);
        String heapBase = "P2P_HEAP";
        String mvccBase = "P2P_MVCC";
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f06-canonical-plan-falsification") + ";create=true";
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            phase2PSetAutoIndexStatistics(setup, false);
            prepareHighCardGroupFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareHighCardGroupFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            for (String table : List.of(
                    highCardGroupTableName(heapBase),
                    highCardGroupTableName(mvccBase))) {
                phase2BUpdateStatistics(setup, table);
            }
            setup.commit();
            String statisticsBefore = phase2PStatisticsSnapshot(setup, List.of(
                    highCardGroupTableName(heapBase),
                    highCardGroupTableName(mvccBase)));
            setup.commit();
            Files.writeString(
                    reportDirectory.resolve("phase2p-sysstatistics-before.tsv"),
                    statisticsBefore,
                    StandardCharsets.UTF_8);
        }

        Phase2PResult result;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            result = phase2PMeasureCanonicalPlans(
                    connection, reportDirectory, heapBase, mvccBase, expectedRows);
            connection.rollback();
        }
        Files.writeString(
                reportDirectory.resolve("phase2p-f06-canonical-plan-summary.txt"),
                phase2PSummary(result), StandardCharsets.UTF_8);
        System.out.print(phase2PSummary(result));
    }

    static Phase2PResult phase2PMeasureCanonicalPlans(
            Connection connection,
            Path reportDirectory,
            String heapBase,
            String mvccBase,
            int expectedRows) throws Exception {
        String heapNaturalSql = phase2PCanonicalGroupSql(heapBase, false);
        String heapForcedSql = phase2PCanonicalGroupSql(heapBase, true);
        String mvccNaturalSql = phase2PCanonicalGroupSql(mvccBase, false);
        String mvccForcedSql = phase2PCanonicalGroupSql(mvccBase, true);
        String mvccAfterFeedbackSql = phase2PCanonicalGroupSql(mvccBase, false)
                + "\n-- phase2p-after-row-count-feedback";

        ExplainCapture heapNaturalBefore = capturePhase2NExplain(connection, heapNaturalSql, false);
        ExplainCapture heapForcedBefore = capturePhase2NExplain(connection, heapForcedSql, false);
        ExplainCapture mvccNaturalBefore = capturePhase2NExplain(connection, mvccNaturalSql, false);
        ExplainCapture mvccForcedBefore = capturePhase2NExplain(connection, mvccForcedSql, false);
        writePhase2ACapture(reportDirectory, "heap-natural-before", heapNaturalBefore);
        writePhase2ACapture(reportDirectory, "heap-forced-before", heapForcedBefore);
        writePhase2ACapture(reportDirectory, "mvcc-natural-before", mvccNaturalBefore);
        writePhase2ACapture(reportDirectory, "mvcc-forced-before", mvccForcedBefore);

        LinkedHashMap<String, Phase2PMeasurement> measurements = new LinkedHashMap<>();
        measurements.put("heap-natural", phase2PMeasure(
                connection, heapNaturalSql, "heap-natural", expectedRows));
        measurements.put("heap-forced", phase2PMeasure(
                connection, heapForcedSql, "heap-forced", expectedRows));
        measurements.put("mvcc-natural-fresh", phase2PMeasure(
                connection, mvccNaturalSql, "mvcc-natural-fresh", expectedRows));
        measurements.put("mvcc-forced-control", phase2PMeasure(
                connection, mvccForcedSql, "mvcc-forced-control", expectedRows));

        String statsAfter = phase2PStatisticsSnapshot(connection, List.of(
                highCardGroupTableName(heapBase),
                highCardGroupTableName(mvccBase)));
        Files.writeString(reportDirectory.resolve("phase2p-sysstatistics-after.tsv"),
                statsAfter, StandardCharsets.UTF_8);
        ExplainCapture mvccNaturalAfter = capturePhase2NExplain(
                connection, mvccAfterFeedbackSql, false);
        ExplainCapture mvccForcedAfter = capturePhase2NExplain(
                connection, mvccForcedSql + "\n-- after-row-count-feedback", false);
        writePhase2ACapture(reportDirectory, "mvcc-natural-after-feedback", mvccNaturalAfter);
        writePhase2ACapture(reportDirectory, "mvcc-forced-after-feedback", mvccForcedAfter);
        measurements.put("mvcc-natural-after-feedback", phase2PMeasure(
                connection, mvccAfterFeedbackSql, "mvcc-natural-after-feedback", expectedRows));

        phase2PAssertSemantics(measurements);
        phase2PWriteMeasurements(reportDirectory, measurements);
        String statsBefore = Files.readString(
                reportDirectory.resolve("phase2p-sysstatistics-before.tsv"), StandardCharsets.UTF_8);
        return new Phase2PResult(
                connection.getHoldability(), statsBefore.equals(statsAfter), measurements,
                phase2PPlanMetric(heapNaturalBefore), phase2PPlanMetric(heapForcedBefore),
                phase2PPlanMetric(mvccNaturalBefore), phase2PPlanMetric(mvccForcedBefore),
                phase2PPlanMetric(mvccNaturalAfter), phase2PPlanMetric(mvccForcedAfter));
    }

    static Phase2PMeasurement phase2PMeasure(
            Connection connection, String sql, String variant, int expectedRows) throws Exception {
        int warmups = 8;
        int measuredRounds = 9;
        int executionsPerSample = 5;
        List<Double> samples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long fingerprint = executePhase2NRead(statement, variant, expectedRows);
            for (int warmup = 0; warmup < warmups; warmup++) {
                long actual = executePhase2NRead(statement, variant, expectedRows);
                if (actual != fingerprint) {
                    throw new IllegalStateException("Phase-2P warmup semantic drift for " + variant);
                }
            }
            for (int round = 0; round < measuredRounds; round++) {
                long started = System.nanoTime();
                for (int execution = 0; execution < executionsPerSample; execution++) {
                    long actual = executePhase2NRead(statement, variant, expectedRows);
                    if (actual != fingerprint) {
                        throw new IllegalStateException(
                                "Phase-2P measured semantic drift for " + variant);
                    }
                }
                samples.add((System.nanoTime() - started)
                        / (executionsPerSample * 1_000_000.0d));
            }
            Distribution distribution = distribution(samples);
            DelosMeasurementValidityContract.DispersionDecision decision =
                    DelosMeasurementValidityContract.classifyCustom(
                            distribution.iqr() / distribution.median(),
                            distribution.mad() / distribution.median());
            return new Phase2PMeasurement(fingerprint, samples, distribution, decision);
        }
    }

    static void phase2PWriteMeasurements(
            Path reportDirectory, Map<String, Phase2PMeasurement> measurements) throws IOException {
        StringBuilder samples = new StringBuilder("variant\tround\telapsedMillis\n");
        StringBuilder dispersion = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tgoverningDispersion\tstatus\n");
        for (Map.Entry<String, Phase2PMeasurement> entry : measurements.entrySet()) {
            Phase2PMeasurement measurement = entry.getValue();
            for (int index = 0; index < measurement.samples().size(); index++) {
                samples.append(entry.getKey()).append('\t').append(index + 1).append('\t')
                        .append(format(measurement.samples().get(index))).append('\n');
            }
            distributionRow(dispersion, entry.getKey(), measurement);
        }
        Files.writeString(reportDirectory.resolve("phase2p-repeated-execution-samples.tsv"),
                samples.toString(), StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("phase2p-dispersion.tsv"),
                dispersion.toString(), StandardCharsets.UTF_8);
    }

    static void distributionRow(
            StringBuilder output, String variant, Phase2PMeasurement measurement) {
        Distribution distribution = measurement.distribution();
        DelosMeasurementValidityContract.DispersionDecision decision = measurement.decision();
        output.append(variant).append('\t')
                .append(format(distribution.median())).append('\t')
                .append(format(decision.iqrToMedian())).append('\t')
                .append(format(decision.madToMedian())).append('\t')
                .append(format(decision.governingRatio())).append('\t')
                .append(decision.status()).append('\n');
    }

    static void phase2PAssertSemantics(Map<String, Phase2PMeasurement> measurements) {
        long fingerprint = measurements.values().iterator().next().fingerprint();
        for (Map.Entry<String, Phase2PMeasurement> entry : measurements.entrySet()) {
            if (entry.getValue().fingerprint() != fingerprint) {
                throw new IllegalStateException(
                        "Phase-2P semantic drift for " + entry.getKey() + ": expected="
                                + fingerprint + ", actual=" + entry.getValue().fingerprint());
            }
        }
    }

    static String phase2PSummary(Phase2PResult result) {
        Phase2PMeasurement mvccNatural = result.measurements().get("mvcc-natural-fresh");
        Phase2PMeasurement mvccForced = result.measurements().get("mvcc-forced-control");
        Phase2PMeasurement mvccAfter = result.measurements().get("mvcc-natural-after-feedback");
        Phase2PFeatures features = phase2PFeatures();
        boolean closeOnCommit = result.holdability() == ResultSet.CLOSE_CURSORS_AT_COMMIT;
        return "DelosDB Phase-2P F06 canonical GROUP_HIGH_CARD plan falsification\n"
                + "diagnosticOnly=true\n"
                + "autoIndexStatistics=false\n"
                + "explicitStatisticsRefresh=true\n"
                + "statisticsCatalogStable=" + result.statisticsCatalogStable() + "\n"
                + "canonicalHoldability=" + holdabilityName(result.holdability()) + "\n"
                + phase2PFeatureSummary(
                        features, result.mvccNaturalBefore().shape(),
                        result.mvccNaturalAfter().shape(), closeOnCommit)
                + phase2PPlanSummary("heapNaturalBefore", result.heapNaturalBefore())
                + phase2PPlanSummary("heapForcedBefore", result.heapForcedBefore())
                + phase2PPlanSummary("mvccNaturalBefore", result.mvccNaturalBefore())
                + phase2PPlanSummary("mvccForcedBefore", result.mvccForcedBefore())
                + phase2PPlanSummary("mvccNaturalAfterFeedback", result.mvccNaturalAfter())
                + phase2PPlanSummary("mvccForcedAfterFeedback", result.mvccForcedAfter())
                + "mvccNaturalFreshMedianMillis=" + format(mvccNatural.distribution().median()) + "\n"
                + "mvccForcedControlMedianMillis=" + format(mvccForced.distribution().median()) + "\n"
                + "mvccNaturalAfterFeedbackMedianMillis=" + format(mvccAfter.distribution().median()) + "\n"
                + "forcedVsNaturalFreshRatio="
                + format(mvccForced.distribution().median() / mvccNatural.distribution().median()) + "\n"
                + "naturalAfterVsForcedRatio="
                + format(mvccAfter.distribution().median() / mvccForced.distribution().median()) + "\n"
                + "semanticFingerprintsMatch=true\n"
                + "measurementStatus=" + phase2PCombinedStatus(result.measurements()) + "\n";
    }

    static String phase2PFeatureSummary(
            Phase2PFeatures features, String naturalPlanShape,
            String afterFeedbackPlanShape, boolean closeOnCommit) {
        boolean naturalTableScan = naturalPlanShape.contains("TABLE_SCAN");
        boolean afterTableScan = afterFeedbackPlanShape.contains("TABLE_SCAN");
        boolean singlePassReachable = naturalTableScan && features.singlePassCurrentScan();
        boolean reusableReachable = singlePassReachable && features.reusableCurrentScanTemplate();
        boolean streamingReachable = reusableReachable && closeOnCommit && features.streamingBulkScan();
        boolean forcedStreaming = closeOnCommit && features.streamingBulkScan();
        return "gen2C3Enabled=" + features.gen2C3Enabled() + "\n"
                + "projectedCurrentRead=" + features.projectedCurrentRead() + "\n"
                + "baseFetchPagePrefetch=" + features.baseFetchPagePrefetch() + "\n"
                + "physicalScanCost=" + features.physicalScanCost() + "\n"
                + "physicalRowLocationCost=" + features.physicalRowLocationCost() + "\n"
                + "singlePassCurrentScan=" + features.singlePassCurrentScan() + "\n"
                + "reusableCurrentScanTemplate=" + features.reusableCurrentScanTemplate() + "\n"
                + "streamingBulkScan=" + features.streamingBulkScan() + "\n"
                + "fastCurrentVisibilityFetch=" + features.fastCurrentVisibilityFetch() + "\n"
                + "skipCurrentTagFetch=" + features.skipCurrentTagFetch() + "\n"
                + "lazyCurrentCreatorFetch=" + features.lazyCurrentCreatorFetch() + "\n"
                + "lazyCurrentIdentityFetch=" + features.lazyCurrentIdentityFetch() + "\n"
                + "naturalSinglePassReachable=" + singlePassReachable + "\n"
                + "naturalReusableTemplateReachable=" + reusableReachable + "\n"
                + "naturalStreamingReachable=" + streamingReachable + "\n"
                + "naturalFastVisibilityReachable="
                + (streamingReachable && features.fastCurrentVisibilityFetch()) + "\n"
                + "afterFeedbackSinglePassReachable="
                + (afterTableScan && features.singlePassCurrentScan()) + "\n"
                + "afterFeedbackStreamingReachable="
                + (afterTableScan && forcedStreaming) + "\n"
                + "forcedTableScanSinglePassReachable=" + features.singlePassCurrentScan() + "\n"
                + "forcedTableScanReusableTemplateReachable="
                + (features.singlePassCurrentScan() && features.reusableCurrentScanTemplate()) + "\n"
                + "forcedTableScanStreamingReachable=" + forcedStreaming + "\n"
                + "forcedTableScanFastVisibilityReachable="
                + (forcedStreaming && features.fastCurrentVisibilityFetch()) + "\n"
                + "forcedTableScanLazyIdentityReachable="
                + (forcedStreaming && features.lazyCurrentIdentityFetch()) + "\n";
    }

    static String phase2PPlanSummary(String prefix, Phase2PPlanMetric metric) {
        return prefix + "PlanShape=" + metric.shape() + "\n"
                + prefix + "EstimatedRows=" + format(metric.rows()) + "\n"
                + prefix + "EstimatedCost=" + format(metric.cost()) + "\n";
    }

    static Phase2PPlanMetric phase2PPlanMetric(ExplainCapture capture) {
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*n\\d+ SCAN/(TABLE_SCAN|INDEX_TO_BASE_ROW).*? rows=([0-9.Ee+-]+) cost=([0-9.Ee+-]+)");
        Matcher matcher = pattern.matcher(capture.text());
        if (!matcher.find()) {
            throw new IllegalStateException("Phase-2P scan metric missing from plan:\n" + capture.text());
        }
        return new Phase2PPlanMetric(
                phase2NPlanShape(capture.text()),
                Double.parseDouble(matcher.group(2)),
                Double.parseDouble(matcher.group(3)));
    }

    static String phase2PCanonicalGroupSql(String base, boolean forceTableScan) {
        String table = highCardGroupTableName(base);
        String from = forceTableScan
                ? table + " --DERBY-PROPERTIES index=null\n"
                : table + "\n";
        return "select group_key, count(*), sum(quantity) from " + from
                + "group by group_key order by group_key";
    }

    static void phase2PSetAutoIndexStatistics(Connection connection, boolean enabled)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "call syscs_util.syscs_set_database_property('derby.storage.indexStats.auto', ?)")) {
            statement.setString(1, Boolean.toString(enabled));
            statement.execute();
        }
        connection.commit();
    }

    static DelosMeasurementValidityContract.Status phase2PCombinedStatus(
            Map<String, Phase2PMeasurement> measurements) {
        return DelosMeasurementValidityContract.combine(
                measurements.values().stream()
                        .map(value -> value.decision().status())
                        .toArray(DelosMeasurementValidityContract.Status[]::new));
    }

    static String holdabilityName(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT
                ? "CLOSE_CURSORS_AT_COMMIT"
                : holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT
                        ? "HOLD_CURSORS_OVER_COMMIT"
                        : Integer.toString(holdability);
    }

    static Phase2PFeatures phase2PFeatures() {
        boolean gen2C3Enabled = Boolean.getBoolean("delosdb.experimental.mvccGen2B.pk.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C1.history.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C2.archivedUndo.enabled");
        return new Phase2PFeatures(
                gen2C3Enabled,
                Boolean.getBoolean("delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled"),
                Boolean.getBoolean("delosdb.experimental.mvccBaseFetchPagePrefetch"),
                Boolean.parseBoolean(System.getProperty(
                        "delosdb.experimental.mvccPhysicalScanCost.enabled", "true")),
                Boolean.parseBoolean(System.getProperty(
                        MvccFeatureNames.PHYSICAL_ROW_LOCATION_COST, "false")),
                Boolean.getBoolean(MvccFeatureNames.SINGLE_PASS),
                Boolean.getBoolean(MvccFeatureNames.REUSABLE_TEMPLATE),
                Boolean.getBoolean(MvccFeatureNames.STREAMING),
                Boolean.getBoolean(MvccFeatureNames.FAST_VISIBILITY),
                Boolean.getBoolean(MvccFeatureNames.SKIP_TAGS),
                Boolean.getBoolean(MvccFeatureNames.LAZY_CREATOR),
                Boolean.getBoolean(MvccFeatureNames.LAZY_IDENTITY));
    }

    static Path requiredPhase2PPath(String key) {
        String value = System.getProperty(PHASE2P_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2P_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static Path requiredPhase2OPath(String key) {
        String value = System.getProperty(PHASE2O_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2O_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static String requiredPhase2OProvider() {
        String provider = System.getProperty(PHASE2O_PREFIX + "provider", "").trim();
        if (!provider.equals("heap") && !provider.equals("mvcc")) {
            throw new IllegalArgumentException(
                    "-D" + PHASE2O_PREFIX + "provider must be heap or mvcc");
        }
        return provider;
    }

    static void addPhase2NVariants(
            Map<String, String> sqlByVariant, String provider, String base) {
        String dimension = joinDimensionTableName(base);
        String group = highCardGroupTableName(base);
        sqlByVariant.put(provider + "-join",
                "select a.id from " + base + " a join " + dimension + " b on a.id = b.id");
        sqlByVariant.put(provider + "-join-base-range",
                "select id from " + base + " where id between 1 and 1000");
        sqlByVariant.put(provider + "-join-dimension-scan",
                "select id from " + dimension);
        sqlByVariant.put(provider + "-group-input",
                "select group_key, quantity from " + group);
        sqlByVariant.put(provider + "-group-unordered",
                "select group_key, count(*), sum(quantity) from " + group + " group by group_key");
        sqlByVariant.put(provider + "-group-ordered",
                "select group_key, count(*), sum(quantity) from " + group
                        + " group by group_key order by group_key");
    }

    static void addPhase2NForcedJoinVariants(
            Map<String, String> sqlByVariant,
            String provider,
            String base,
            Connection connection) throws SQLException {
        String dimension = joinDimensionTableName(base);
        String basePkIndex = phase2BPrimaryKeyIndex(connection, base, "ID");
        String dimensionPkIndex = phase2BPrimaryKeyIndex(connection, dimension, "ID");
        sqlByVariant.put(provider + "-join-forced-base-first-hash",
                phase2NForcedJoinSql(
                        base, "a", basePkIndex, dimension, "b", dimensionPkIndex));
        sqlByVariant.put(provider + "-join-forced-dim-first-hash",
                phase2NForcedJoinSql(
                        dimension, "b", dimensionPkIndex, base, "a", basePkIndex));
    }

    static String phase2NForcedJoinSql(
            String firstTable,
            String firstAlias,
            String firstIndex,
            String secondTable,
            String secondAlias,
            String secondIndex) {
        return "select a.id from --DERBY-PROPERTIES joinOrder=FIXED\n"
                + firstTable + " " + firstAlias
                + " --DERBY-PROPERTIES index='" + firstIndex + "'\n"
                + "join " + secondTable + " " + secondAlias
                + " --DERBY-PROPERTIES index='" + secondIndex
                + "', joinStrategy=HASH\n"
                + "on a.id = b.id";
    }

    static void assertPhase2NJoinPlanSemantics(
            Map<String, Long> semanticByVariant) {
        for (String provider : List.of("heap", "mvcc")) {
            long natural = semanticByVariant.get(provider + "-join");
            for (String suffix : List.of(
                    "join-forced-base-first-hash",
                    "join-forced-dim-first-hash")) {
                long forced = semanticByVariant.get(provider + "-" + suffix);
                if (forced != natural) {
                    throw new IllegalStateException(
                            "Phase-2N forced join semantic drift for " + provider
                                    + "/" + suffix + ": natural=" + natural
                                    + ", forced=" + forced);
                }
            }
        }
    }

    static String phase2NJoinPlanSummary(
            boolean joinPlanFalsification, Map<String, Distribution> distributions) {
        if (!joinPlanFalsification) {
            return "";
        }
        double heapNatural = distributions.get("heap-join").median();
        double mvccNatural = distributions.get("mvcc-join").median();
        double heapBaseFirst = distributions.get("heap-join-forced-base-first-hash").median();
        double mvccBaseFirst = distributions.get("mvcc-join-forced-base-first-hash").median();
        double heapDimFirst = distributions.get("heap-join-forced-dim-first-hash").median();
        double mvccDimFirst = distributions.get("mvcc-join-forced-dim-first-hash").median();
        return "heapJoinForcedBaseFirstHashMedianMillis=" + format(heapBaseFirst) + "\n"
                + "mvccJoinForcedBaseFirstHashMedianMillis=" + format(mvccBaseFirst) + "\n"
                + "forcedBaseFirstMvccVsHeapRatio=" + format(mvccBaseFirst / heapBaseFirst) + "\n"
                + "mvccForcedBaseFirstVsNaturalRatio=" + format(mvccBaseFirst / mvccNatural) + "\n"
                + "heapJoinForcedDimFirstHashMedianMillis=" + format(heapDimFirst) + "\n"
                + "mvccJoinForcedDimFirstHashMedianMillis=" + format(mvccDimFirst) + "\n"
                + "forcedDimFirstMvccVsHeapRatio=" + format(mvccDimFirst / heapDimFirst) + "\n"
                + "mvccForcedDimFirstVsNaturalRatio=" + format(mvccDimFirst / mvccNatural) + "\n"
                + "heapForcedBaseFirstVsNaturalRatio=" + format(heapBaseFirst / heapNatural) + "\n";
    }

    static long executePhase2NRead(
            PreparedStatement statement, String variant, int expectedRows) throws SQLException {
        int rows = 0;
        long fingerprint = 0L;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long tuple = 0x9E3779B97F4A7C15L;
                if (variant.endsWith("-group-input")) {
                    tuple = mix(tuple, resultSet.getInt(1));
                    tuple = mix(tuple, resultSet.getInt(2));
                } else if (variant.endsWith("-group-unordered")
                        || variant.endsWith("-group-ordered")) {
                    tuple = mix(tuple, resultSet.getInt(1));
                    tuple = mix(tuple, resultSet.getLong(2));
                    tuple = mix(tuple, resultSet.getLong(3));
                } else {
                    tuple = mix(tuple, resultSet.getInt(1));
                }
                fingerprint += tuple;
                rows++;
            }
        }
        if (rows != expectedRows) {
            throw new SQLException(
                    "Phase-2N row-count drift for " + variant
                            + ": expected=" + expectedRows + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    static void assertPhase2NProviderSemantics(
            Map<String, Long> semanticByVariant, String suffix) {
        long heap = semanticByVariant.get("heap-" + suffix);
        long mvcc = semanticByVariant.get("mvcc-" + suffix);
        if (heap != mvcc) {
            throw new IllegalStateException(
                    "Phase-2N Heap/MVCC semantic drift for " + suffix
                            + ": heap=" + heap + ", mvcc=" + mvcc);
        }
    }

    static String phase2NPlanShape(String analyzeText) {
        List<String> shape = new ArrayList<>();
        if (analyzeText.contains("JOIN/HASH_JOIN") || analyzeText.contains("Hash Join ResultSet")) {
            shape.add("HASH_JOIN");
        }
        if (analyzeText.contains("JOIN/NESTED_LOOP")
                || analyzeText.contains("Nested Loop Join ResultSet")) {
            shape.add("NESTED_LOOP");
        }
        if (analyzeText.contains("AGGREGATE")) {
            shape.add("AGGREGATE");
        }
        if (analyzeText.contains("SORT") || analyzeText.contains("Sort ResultSet")) {
            shape.add("SORT");
        }
        if (analyzeText.contains("SCAN/INDEX_TO_BASE_ROW")
                || analyzeText.contains("Index Row to Base Row ResultSet")) {
            shape.add("INDEX_TO_BASE_ROW");
        }
        if (analyzeText.contains("SCAN/INDEX_SCAN")
                || analyzeText.contains("Index Scan ResultSet")) {
            shape.add("INDEX_SCAN");
        }
        if (analyzeText.contains("SCAN/TABLE_SCAN")
                || analyzeText.contains("Table Scan ResultSet")) {
            shape.add("TABLE_SCAN");
        }
        return shape.isEmpty() ? "UNKNOWN" : String.join("+", shape);
    }

    static Path requiredPhase2NPath(String key) {
        String value = System.getProperty(PHASE2N_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2N_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2KF03ProjectionMaterializationDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2KPath("reportDirectory");
        Path databaseRoot = requiredPhase2KPath("databaseRoot");
        boolean gen2C3Enabled = Boolean.getBoolean("delosdb.experimental.mvccGen2B.pk.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C1.history.enabled")
                && Boolean.getBoolean("delosdb.experimental.mvccGen2C2.archivedUndo.enabled");
        boolean projectedCurrentRead = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean baseFetchPrefetch = Boolean.getBoolean(
                "delosdb.experimental.mvccBaseFetchPagePrefetch");
        if (Boolean.getBoolean(PHASE2K_PREFIX + "requireGen2C3")
                && (!gen2C3Enabled || projectedCurrentRead || baseFetchPrefetch)) {
            throw new IllegalStateException(
                    "Phase-2K Gen2-C3 diagnostic requires B1/C1/C2 enabled "
                            + "with projected-current and page-local prefetch disabled");
        }
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        int expectedRows = expectedFitnessRows(Workload.PROJECTION_COVERED, rowCount);
        int warmups = 4;
        int warmupExecutionsPerVisit = 64;
        int measuredRounds = 9;
        int measuredExecutionsPerSample = 512;
        String database = databaseRoot.resolve("f03-projection-materialization").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";
        DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                rowCount, payloadSize, SEED, commitBatchSize);

        String heapTable;
        String mvccTable;
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            DelosJdbcBenchmarkScenario heap = new DelosJdbcBenchmarkScenario(
                    setup, "phase2k_heap", "", false, config);
            heap.prepare();
            heapTable = heap.tableName();
            DelosJdbcBenchmarkScenario mvcc = new DelosJdbcBenchmarkScenario(
                    setup, "phase2k_mvcc", " using delos_mvcc", false, config);
            mvcc.prepare();
            mvccTable = mvcc.tableName();
            phase2BUpdateStatistics(setup, heapTable);
            phase2BUpdateStatistics(setup, mvccTable);
            setup.commit();
        }

        LinkedHashMap<String, Workload> workloadByVariant = new LinkedHashMap<>();
        workloadByVariant.put("heap-covered", Workload.PROJECTION_COVERED);
        workloadByVariant.put("mvcc-covered", Workload.PROJECTION_COVERED);
        workloadByVariant.put("heap-two-column", Workload.PROJECTION_TWO_COLUMN);
        workloadByVariant.put("mvcc-two-column", Workload.PROJECTION_TWO_COLUMN);
        workloadByVariant.put("heap-full-row", Workload.PROJECTION_FULL_ROW);
        workloadByVariant.put("mvcc-full-row", Workload.PROJECTION_FULL_ROW);

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        for (Map.Entry<String, Workload> entry : workloadByVariant.entrySet()) {
            boolean mvccVariant = entry.getKey().startsWith("mvcc-");
            String table = mvccVariant ? mvccTable : heapTable;
            sqlByVariant.put(entry.getKey(), phase2KProjectionSql(entry.getValue(), table));
        }

        StringBuilder queryShapes = new StringBuilder();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            queryShapes.append("=== ").append(entry.getKey()).append(" ===\n")
                    .append(entry.getValue()).append("\n\n");
        }
        Files.writeString(reportDirectory.resolve("query-shapes.txt"),
                queryShapes.toString(), StandardCharsets.UTF_8);

        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        LinkedHashMap<Workload, Long> semanticByWorkload = new LinkedHashMap<>();
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                Workload workload = workloadByVariant.get(entry.getKey());
                ExplainCapture explain = capturePhase2KExplain(
                        connection, entry.getValue(), workload, false);
                ExplainCapture analyze = capturePhase2KExplain(
                        connection, entry.getValue(), workload, true);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain", explain);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain-analyze", analyze);
                analyzeByVariant.put(entry.getKey(), analyze);

                long fingerprint;
                try (PreparedStatement statement = connection.prepareStatement(entry.getValue())) {
                    fingerprint = executePhase2KProjectionRead(statement, workload, expectedRows);
                }
                Long prior = semanticByWorkload.putIfAbsent(workload, fingerprint);
                if (prior != null && prior.longValue() != fingerprint) {
                    throw new IllegalStateException(
                            "Phase-2K Heap/MVCC semantic drift for " + workload
                                    + ": expected=" + prior + ", actual=" + fingerprint);
                }
            }
            connection.rollback();
        }

        boolean matchedCoveredAccessShape = phase2KAccessShape(
                        analyzeByVariant.get("heap-covered").text())
                .equals(phase2KAccessShape(analyzeByVariant.get("mvcc-covered").text()));
        boolean matchedTwoColumnAccessShape = phase2KAccessShape(
                        analyzeByVariant.get("heap-two-column").text())
                .equals(phase2KAccessShape(analyzeByVariant.get("mvcc-two-column").text()));
        boolean matchedFullRowAccessShape = phase2KAccessShape(
                        analyzeByVariant.get("heap-full-row").text())
                .equals(phase2KAccessShape(analyzeByVariant.get("mvcc-full-row").text()));
        if (!matchedCoveredAccessShape || !matchedTwoColumnAccessShape || !matchedFullRowAccessShape) {
            throw new IllegalStateException(
                    "Phase-2K matched-access guard failed: covered=" + matchedCoveredAccessShape
                            + ", twoColumn=" + matchedTwoColumnAccessShape
                            + ", fullRow=" + matchedFullRowAccessShape);
        }

        LinkedHashMap<String, List<Double>> samples = new LinkedHashMap<>();
        for (String variant : sqlByVariant.keySet()) {
            samples.put(variant, new ArrayList<>());
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            LinkedHashMap<String, PreparedStatement> statements = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                    statements.put(entry.getKey(), connection.prepareStatement(entry.getValue()));
                }
                List<String> variants = new ArrayList<>(sqlByVariant.keySet());
                for (int round = -warmups; round < measuredRounds; round++) {
                    int rotation = Math.floorMod(round + warmups, variants.size());
                    for (int offset = 0; offset < variants.size(); offset++) {
                        String variant = variants.get((rotation + offset) % variants.size());
                        int executions = round >= 0
                                ? measuredExecutionsPerSample
                                : warmupExecutionsPerVisit;
                        Workload workload = workloadByVariant.get(variant);
                        long expectedFingerprint = semanticByWorkload.get(workload);
                        long started = System.nanoTime();
                        for (int execution = 0; execution < executions; execution++) {
                            long fingerprint = executePhase2KProjectionRead(
                                    statements.get(variant), workload, expectedRows);
                            if (fingerprint != expectedFingerprint) {
                                throw new IllegalStateException(
                                        "Phase-2K measured semantic drift for " + variant
                                                + ": expected=" + expectedFingerprint
                                                + ", actual=" + fingerprint);
                            }
                        }
                        long elapsed = System.nanoTime() - started;
                        if (round >= 0) {
                            samples.get(variant).add(elapsed / (executions * 1_000_000.0d));
                        }
                    }
                }
                connection.rollback();
            } finally {
                SQLException closeFailure = null;
                for (PreparedStatement statement : statements.values()) {
                    try {
                        statement.close();
                    } catch (SQLException failure) {
                        if (closeFailure == null) {
                            closeFailure = failure;
                        } else {
                            closeFailure.addSuppressed(failure);
                        }
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }

        StringBuilder sampleTsv = new StringBuilder("variant\tround\telapsedMillis\n");
        LinkedHashMap<String, Distribution> distributions = new LinkedHashMap<>();
        LinkedHashMap<String, DelosMeasurementValidityContract.DispersionDecision> decisions =
                new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                sampleTsv.append(entry.getKey()).append('\t').append(i + 1).append('\t')
                        .append(format(entry.getValue().get(i))).append('\n');
            }
            Distribution distribution = distribution(entry.getValue());
            distributions.put(entry.getKey(), distribution);
            decisions.put(entry.getKey(), DelosMeasurementValidityContract.classifyCustom(
                    distribution.iqr() / distribution.median(),
                    distribution.mad() / distribution.median()));
        }
        Files.writeString(reportDirectory.resolve("phase2k-repeated-execution-samples.tsv"),
                sampleTsv.toString(), StandardCharsets.UTF_8);

        StringBuilder dispersionTsv = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tgoverningDispersion\tstatus\n");
        for (String variant : sqlByVariant.keySet()) {
            Distribution distribution = distributions.get(variant);
            DelosMeasurementValidityContract.DispersionDecision decision = decisions.get(variant);
            dispersionTsv.append(variant).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(decision.iqrToMedian())).append('\t')
                    .append(format(decision.madToMedian())).append('\t')
                    .append(format(decision.governingRatio())).append('\t')
                    .append(decision.status()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2k-dispersion.tsv"),
                dispersionTsv.toString(), StandardCharsets.UTF_8);

        StringBuilder operatorTsv = new StringBuilder(
                "variant\texplainAnalyzeWallMillis\taccessShape\tindexToBaseNextMillis\tindexScanNextMillis"
                        + "\ttotalOpenMillis\ttotalNextMillis\ttotalOpens\n");
        for (String variant : sqlByVariant.keySet()) {
            ExplainCapture analyze = analyzeByVariant.get(variant);
            operatorTsv.append(variant).append('\t').append(analyze.wallMillis()).append('\t')
                    .append(phase2KAccessShape(analyze.text())).append('\t')
                    .append(phase2EIndexToBaseNextMillis(analyze.text())).append('\t')
                    .append(phase2EIndexScanNextMillis(analyze.text())).append('\t')
                    .append(sumPhase2AField(analyze.text(), "openMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "nextMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "opens")).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2k-operator-summary.tsv"),
                operatorTsv.toString(), StandardCharsets.UTF_8);

        double heapCovered = distributions.get("heap-covered").median();
        double mvccCovered = distributions.get("mvcc-covered").median();
        double heapTwo = distributions.get("heap-two-column").median();
        double mvccTwo = distributions.get("mvcc-two-column").median();
        double heapFull = distributions.get("heap-full-row").median();
        double mvccFull = distributions.get("mvcc-full-row").median();
        DelosMeasurementValidityContract.Status combinedStatus = DelosMeasurementValidityContract.combine(
                decisions.get("heap-covered").status(),
                decisions.get("mvcc-covered").status(),
                decisions.get("heap-two-column").status(),
                decisions.get("mvcc-two-column").status(),
                decisions.get("heap-full-row").status(),
                decisions.get("mvcc-full-row").status());
        double worstGoverningDispersion = decisions.values().stream()
                .mapToDouble(DelosMeasurementValidityContract.DispersionDecision::governingRatio)
                .max()
                .orElseThrow();

        String summary = "DelosDB Phase-2K F03 projection/materialization decomposition\n"
                + "diagnosticOnly=true\n"
                + "gen2C3Enabled=" + gen2C3Enabled + "\n"
                + "projectedCurrentRead=" + projectedCurrentRead + "\n"
                + "baseFetchPagePrefetch=" + baseFetchPrefetch + "\n"
                + "rows=" + rowCount + "\n"
                + "payloadSize=" + payloadSize + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "warmupExecutionsPerVisit=" + warmupExecutionsPerVisit + "\n"
                + "measuredRoundsPerVariant=" + measuredRounds + "\n"
                + "measuredExecutionsPerSample=" + measuredExecutionsPerSample + "\n"
                + "matchedCoveredAccessShape=" + matchedCoveredAccessShape + "\n"
                + "matchedTwoColumnAccessShape=" + matchedTwoColumnAccessShape + "\n"
                + "matchedFullRowAccessShape=" + matchedFullRowAccessShape + "\n"
                + "heapCoveredMedianMillis=" + format(heapCovered) + "\n"
                + "mvccCoveredMedianMillis=" + format(mvccCovered) + "\n"
                + "coveredMvccVsHeapRatio=" + format(mvccCovered / heapCovered) + "\n"
                + "heapTwoColumnMedianMillis=" + format(heapTwo) + "\n"
                + "mvccTwoColumnMedianMillis=" + format(mvccTwo) + "\n"
                + "twoColumnMvccVsHeapRatio=" + format(mvccTwo / heapTwo) + "\n"
                + "heapFullRowMedianMillis=" + format(heapFull) + "\n"
                + "mvccFullRowMedianMillis=" + format(mvccFull) + "\n"
                + "fullRowMvccVsHeapRatio=" + format(mvccFull / heapFull) + "\n"
                + "heapTwoVsCoveredRatio=" + format(heapTwo / heapCovered) + "\n"
                + "mvccTwoVsCoveredRatio=" + format(mvccTwo / mvccCovered) + "\n"
                + "heapFullVsTwoRatio=" + format(heapFull / heapTwo) + "\n"
                + "mvccFullVsTwoRatio=" + format(mvccFull / mvccTwo) + "\n"
                + "worstGoverningDispersion=" + format(worstGoverningDispersion) + "\n"
                + "measurementStatus=" + combinedStatus + "\n"
                + "classification=EVIDENCE_READY_FOR_F03_PHASE2_CLASSIFICATION\n";
        Files.writeString(reportDirectory.resolve(
                        "phase2k-f03-projection-materialization-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);
    }

    static ExplainCapture capturePhase2KExplain(
            Connection connection, String sql, Workload workload, boolean analyze) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(
                (analyze ? "explain analyze " : "explain ") + sql)) {
            bindFitnessRead(statement, workload);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Phase-2K EXPLAIN returned no row");
                }
                String text = resultSet.getString(1);
                String json = resultSet.getString(2);
                if (resultSet.next()) {
                    throw new SQLException("Phase-2K EXPLAIN returned more than one row");
                }
                return new ExplainCapture(
                        text,
                        json,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    static String phase2KProjectionSql(Workload workload, String table) {
        String index = table + "_CATEGORY_IDX";
        return switch (workload) {
            case PROJECTION_COVERED ->
                    "select category from " + table + " --DERBY-PROPERTIES index=" + index
                            + "\nwhere category = ?";
            case PROJECTION_TWO_COLUMN ->
                    "select id, quantity from " + table + " --DERBY-PROPERTIES index=" + index
                            + "\nwhere category = ?";
            case PROJECTION_FULL_ROW ->
                    "select id, category, bucket, quantity, payload from " + table
                            + " --DERBY-PROPERTIES index=" + index + "\nwhere category = ?";
            default -> throw new IllegalArgumentException(
                    "Not a Phase-2K projection workload: " + workload);
        };
    }

    static long executePhase2KProjectionRead(
            PreparedStatement statement, Workload workload, int expectedRows) throws SQLException {
        statement.setInt(1, FITNESS_CATEGORY);
        int rows = 0;
        long fingerprint = 0L;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long tuple = 0x9E3779B97F4A7C15L;
                switch (workload) {
                    case PROJECTION_COVERED -> tuple = mix(tuple, resultSet.getInt(1));
                    case PROJECTION_TWO_COLUMN -> {
                        tuple = mix(tuple, resultSet.getInt(1));
                        tuple = mix(tuple, resultSet.getInt(2));
                    }
                    case PROJECTION_FULL_ROW -> {
                        tuple = mix(tuple, resultSet.getInt(1));
                        tuple = mix(tuple, resultSet.getInt(2));
                        tuple = mix(tuple, resultSet.getInt(3));
                        tuple = mix(tuple, resultSet.getInt(4));
                        tuple = mix(tuple, resultSet.getString(5).hashCode());
                    }
                    default -> throw new SQLException(
                            "Unexpected Phase-2K projection workload: " + workload);
                }
                fingerprint += tuple;
                rows++;
            }
        }
        if (rows != expectedRows) {
            throw new SQLException(
                    "Phase-2K row-count drift: expected=" + expectedRows + ", actual=" + rows);
        }
        return mix(fingerprint, rows);
    }

    static String phase2KAccessShape(String analyzeText) {
        boolean indexToBase = analyzeText.contains("SCAN/INDEX_TO_BASE_ROW");
        boolean indexScan = analyzeText.contains("SCAN/INDEX_SCAN");
        return (indexToBase ? "INDEX_TO_BASE_ROW+" : "")
                + (indexScan ? "INDEX_SCAN" : "NO_INDEX_SCAN");
    }

    static Path requiredPhase2KPath(String key) {
        String value = System.getProperty(PHASE2K_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2K_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }
}
