/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2UPlanShape;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2EProfile;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2RConcurrencyMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2UVariant;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.Phase2VFixtures;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Distribution;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.ExplainCapture;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2A_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2B_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2D_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2E_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2H_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2J_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2U_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentModel.PHASE2V_PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.openPhase2AConnection;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2BPrimaryKeyIndex;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2EIndexScanNextMillis;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2EIndexToBaseNextMillis;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2ENodeFieldTotal;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2PStatisticsSnapshot;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RMedian;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RRunRangeStatus;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.phase2RStatus;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.sumPhase2AField;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkExperimentSupport.writePhase2ACapture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinDimensionTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutChildTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.joinFanoutParentTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinCustomerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinLineTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.multiJoinOrderTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.phase2BUpdateStatistics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareJoinDimensionFixture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareJoinFanoutFixture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareMultiJoinFixture;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.SEED;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.format;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.mix;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkReports.distribution;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bindFitnessRead;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.executeFitnessRead;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.expectedFitnessRows;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.fitnessReadSql;

/** Legacy join, costing and access-path experiments. */
final class BenchmarkJoinExperiments {
    private BenchmarkJoinExperiments() {
    }

    static void runPhase2BF05CardinalityCostingProof() throws Exception {
        Path reportDirectory = requiredPhase2BPath("reportDirectory");
        Path databaseRoot = requiredPhase2BPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 1_000;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        String mvccBase = "P2B_MVCC";
        String database = databaseRoot.resolve("f05-cardinality-costing").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String currentSql = fitnessReadSql(Workload.JOIN_3WAY_SELECTIVE, mvccBase);
        long customerRows;
        long orderRows;
        long lineRows;
        String customerPkIndex;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            customerRows = phase2BCountRows(connection, multiJoinCustomerTableName(mvccBase));
            orderRows = phase2BCountRows(connection, multiJoinOrderTableName(mvccBase));
            lineRows = phase2BCountRows(connection, multiJoinLineTableName(mvccBase));
            customerPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(mvccBase), "ID");
            connection.rollback();
        }
        if (customerRows != 1_000L || orderRows != 4_000L || lineRows != 12_000L) {
            throw new IllegalStateException(
                    "Phase-2B fixture cardinality drift: customer=" + customerRows
                            + ", order=" + orderRows + ", line=" + lineRows);
        }

        ExplainCapture beforeStats;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            beforeStats = capturePhase2AExplain(connection, currentSql, false);
            connection.rollback();
        }

        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            phase2BUpdateStatistics(connection, multiJoinCustomerTableName(mvccBase));
            phase2BUpdateStatistics(connection, multiJoinOrderTableName(mvccBase));
            phase2BUpdateStatistics(connection, multiJoinLineTableName(mvccBase));
            connection.commit();
        }

        ExplainCapture afterStats;
        ExplainCapture currentAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            afterStats = capturePhase2AExplain(connection, currentSql, false);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            currentAnalyze = capturePhase2AExplain(connection, currentSql, true);
            connection.rollback();
        }

        String forcedSql = phase2BForcedIndexedSql(mvccBase, customerPkIndex);
        ExplainCapture forcedExplain;
        ExplainCapture forcedAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            forcedExplain = capturePhase2AExplain(connection, forcedSql, false);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            forcedAnalyze = capturePhase2AExplain(connection, forcedSql, true);
            connection.rollback();
        }

        long currentFingerprint;
        long forcedFingerprint;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            currentFingerprint = executePhase2AQuery(connection, currentSql, expectedRows);
            forcedFingerprint = executePhase2AQuery(connection, forcedSql, expectedRows);
            connection.rollback();
        }
        if (currentFingerprint != forcedFingerprint) {
            throw new IllegalStateException(
                    "Phase-2B forced-index SQL semantic drift: current=" + currentFingerprint
                            + ", forced=" + forcedFingerprint);
        }

        writePhase2ACapture(reportDirectory, "mvcc-before-stats", beforeStats);
        writePhase2ACapture(reportDirectory, "mvcc-after-stats", afterStats);
        writePhase2ACapture(reportDirectory, "mvcc-current-explain-analyze", currentAnalyze);
        writePhase2ACapture(reportDirectory, "mvcc-forced-index-explain", forcedExplain);
        writePhase2ACapture(reportDirectory, "mvcc-forced-index-explain-analyze", forcedAnalyze);

        String beforeShape = normalizePhase2APlanShape(beforeStats.text(), mvccBase, "delos_mvcc");
        String afterShape = normalizePhase2APlanShape(afterStats.text(), mvccBase, "delos_mvcc");
        String forcedShape = normalizePhase2APlanShape(forcedExplain.text(), mvccBase, "delos_mvcc");
        Files.writeString(
                reportDirectory.resolve("mvcc-before-stats-plan-shape-normalized.txt"),
                beforeShape,
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("mvcc-after-stats-plan-shape-normalized.txt"),
                afterShape,
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("mvcc-forced-index-plan-shape-normalized.txt"),
                forcedShape,
                StandardCharsets.UTF_8);

        boolean statsChangedPlan = !beforeShape.equals(afterShape);
        boolean forcedUsesOrderIndex = forcedExplain.text().contains(multiJoinOrderTableName(mvccBase) + "_C_IDX");
        boolean forcedUsesLineIndex = forcedExplain.text().contains(multiJoinLineTableName(mvccBase) + "_O_IDX");
        long currentMillis = currentAnalyze.wallMillis();
        long forcedMillis = forcedAnalyze.wallMillis();
        double forcedSpeedup = forcedMillis == 0L
                ? Double.POSITIVE_INFINITY
                : (double) currentMillis / (double) forcedMillis;

        String classification;
        if (statsChangedPlan) {
            classification = "EXPLICIT_STATS_CHANGE_PLAN_STATISTICS_FRESHNESS_PATH";
        } else if (forcedUsesOrderIndex && forcedUsesLineIndex && forcedSpeedup >= 10.0d) {
            classification = "STATS_DO_NOT_REPAIR_PLAN_FORCED_INDEX_FAST_COSTING_ROWCOUNT_PATH";
        } else if (forcedUsesOrderIndex && forcedUsesLineIndex) {
            classification = "INDEXES_USABLE_BUT_PHYSICAL_ACCESS_REMAINS_MATERIAL";
        } else {
            classification = "FORCED_INDEX_PATH_NOT_ESTABLISHED_REQUIRES_OPTIMIZER_TRACE";
        }

        String summary = "DelosDB Phase-2B F05 MVCC cardinality/access-path costing proof\n"
                + "customerActualRows=" + customerRows + "\n"
                + "orderActualRows=" + orderRows + "\n"
                + "lineActualRows=" + lineRows + "\n"
                + "customerPrimaryKeyIndex=" + customerPkIndex + "\n"
                + "statsChangedPhysicalPlanShape=" + statsChangedPlan + "\n"
                + "forcedUsesOrderCustomerIndex=" + forcedUsesOrderIndex + "\n"
                + "forcedUsesLineOrderIndex=" + forcedUsesLineIndex + "\n"
                + "currentExplainAnalyzeWallMillis=" + currentMillis + "\n"
                + "forcedIndexExplainAnalyzeWallMillis=" + forcedMillis + "\n"
                + "forcedIndexSpeedup=" + forcedSpeedup + "\n"
                + "semanticFingerprint=" + currentFingerprint + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(
                reportDirectory.resolve("phase2b-f05-cardinality-costing-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("query-shapes.txt"),
                "Current MVCC SQL:\n" + currentSql + "\n\nForced indexed MVCC SQL:\n" + forcedSql + "\n",
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Path requiredPhase2BPath(String key) {
        String value = System.getProperty(PHASE2B_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2B_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static long phase2BCountRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("select count(*) from " + table)) {
            if (!resultSet.next()) {
                throw new SQLException("COUNT returned no row for " + table);
            }
            long count = resultSet.getLong(1);
            if (resultSet.next()) {
                throw new SQLException("COUNT returned more than one row for " + table);
            }
            return count;
        }
    }

    static String phase2BForcedIndexedSql(String base, String customerPkIndex) {
        String customer = multiJoinCustomerTableName(base);
        String order = multiJoinOrderTableName(base);
        String line = multiJoinLineTableName(base);
        return "select c.id, o.id, l.id from --DERBY-PROPERTIES joinOrder=FIXED\n"
                + customer + " c --DERBY-PROPERTIES index='" + customerPkIndex + "'\n"
                + "join " + order + " o --DERBY-PROPERTIES index=" + order
                + "_C_IDX, joinStrategy=NESTEDLOOP\n"
                + "on o.customer_id = c.id\n"
                + "join " + line + " l --DERBY-PROPERTIES index=" + line
                + "_O_IDX, joinStrategy=NESTEDLOOP\n"
                + "on l.order_id = o.id\n"
                + "where c.id between ? and ?";
    }

    static void runPhase2DF05ResidualIndexedAccessDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2DPath("reportDirectory");
        Path databaseRoot = requiredPhase2DPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 1_000;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        int warmups = 4;
        int warmupExecutionsPerVisit = 16;
        int measuredRounds = 9;
        int measuredExecutionsPerSample = 64;
        String heapBase = "P2D_HEAP";
        String mvccBase = "P2D_MVCC";
        String database = databaseRoot.resolve("f05-residual-indexed-access").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareMultiJoinFixture(setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String heapPkIndex;
        String mvccPkIndex;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(heapBase), "ID");
            mvccPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(mvccBase), "ID");
            for (String base : List.of(heapBase, mvccBase)) {
                phase2BUpdateStatistics(connection, multiJoinCustomerTableName(base));
                phase2BUpdateStatistics(connection, multiJoinOrderTableName(base));
                phase2BUpdateStatistics(connection, multiJoinLineTableName(base));
            }
            connection.commit();
        }

        String heapForcedSql = phase2BForcedIndexedSql(heapBase, heapPkIndex);
        String mvccForcedSql = phase2BForcedIndexedSql(mvccBase, mvccPkIndex);
        String mvccNaturalSql = fitnessReadSql(Workload.JOIN_3WAY_SELECTIVE, mvccBase);
        String mvccForcedNestedLoopSql = phase2DForcedNestedLoopSql(mvccBase);

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put("heap-forced-index-nl", heapForcedSql);
        sqlByVariant.put("mvcc-forced-index-nl", mvccForcedSql);
        sqlByVariant.put("mvcc-forced-nl-only", mvccForcedNestedLoopSql);
        sqlByVariant.put("mvcc-natural", mvccNaturalSql);

        LinkedHashMap<String, ExplainCapture> explainByVariant = new LinkedHashMap<>();
        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            try (Connection connection = openPhase2AConnection(jdbcUrl)) {
                explainByVariant.put(
                        entry.getKey(), capturePhase2AExplain(connection, entry.getValue(), false));
                connection.rollback();
            }
            try (Connection connection = openPhase2AConnection(jdbcUrl)) {
                analyzeByVariant.put(
                        entry.getKey(), capturePhase2AExplain(connection, entry.getValue(), true));
                connection.rollback();
            }
        }

        long semanticFingerprint = Long.MIN_VALUE;
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            try (Connection connection = openPhase2AConnection(jdbcUrl)) {
                long fingerprint = executePhase2AQuery(connection, entry.getValue(), expectedRows);
                connection.rollback();
                if (semanticFingerprint == Long.MIN_VALUE) {
                    semanticFingerprint = fingerprint;
                } else if (semanticFingerprint != fingerprint) {
                    throw new IllegalStateException(
                            "Phase-2D SQL semantic drift for " + entry.getKey()
                                    + ": expected=" + semanticFingerprint + ", actual=" + fingerprint);
                }
            }
        }

        for (Map.Entry<String, ExplainCapture> entry : explainByVariant.entrySet()) {
            writePhase2ACapture(reportDirectory, entry.getKey() + "-explain", entry.getValue());
        }
        for (Map.Entry<String, ExplainCapture> entry : analyzeByVariant.entrySet()) {
            writePhase2ACapture(reportDirectory, entry.getKey() + "-explain-analyze", entry.getValue());
        }

        String heapForcedShape = normalizePhase2DPlanShape(
                explainByVariant.get("heap-forced-index-nl").text(), heapBase, "heap", heapPkIndex);
        String mvccForcedShape = normalizePhase2DPlanShape(
                explainByVariant.get("mvcc-forced-index-nl").text(), mvccBase, "delos_mvcc", mvccPkIndex);
        String mvccForcedNlOnlyShape = normalizePhase2DPlanShape(
                explainByVariant.get("mvcc-forced-nl-only").text(), mvccBase, "delos_mvcc", mvccPkIndex);
        String mvccNaturalShape = normalizePhase2DPlanShape(
                explainByVariant.get("mvcc-natural").text(), mvccBase, "delos_mvcc", mvccPkIndex);
        Files.writeString(reportDirectory.resolve("heap-forced-index-nl-plan-shape-normalized.txt"),
                heapForcedShape, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("mvcc-forced-index-nl-plan-shape-normalized.txt"),
                mvccForcedShape, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("mvcc-forced-nl-only-plan-shape-normalized.txt"),
                mvccForcedNlOnlyShape, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("mvcc-natural-plan-shape-normalized.txt"),
                mvccNaturalShape, StandardCharsets.UTF_8);

        boolean matchedHeapMvccForcedShape = heapForcedShape.equals(mvccForcedShape);
        boolean mvccNlOnlyMatchesForcedIndexShape = mvccForcedNlOnlyShape.equals(mvccForcedShape);
        boolean mvccNaturalUsesHashJoin = explainByVariant.get("mvcc-natural").text().contains("join=HASH");
        boolean mvccNaturalUsesOrderIndex = explainByVariant.get("mvcc-natural").text()
                .contains(multiJoinOrderTableName(mvccBase) + "_C_IDX");
        boolean mvccNaturalUsesLineIndex = explainByVariant.get("mvcc-natural").text()
                .contains(multiJoinLineTableName(mvccBase) + "_O_IDX");

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
                        long started = System.nanoTime();
                        for (int execution = 0; execution < executions; execution++) {
                            long fingerprint = executeFitnessRead(
                                    statements.get(variant),
                                    Workload.JOIN_3WAY_SELECTIVE,
                                    expectedRows);
                            if (fingerprint != semanticFingerprint) {
                                throw new IllegalStateException(
                                        "Phase-2D measured semantic drift for " + variant
                                                + ": expected=" + semanticFingerprint
                                                + ", actual=" + fingerprint);
                            }
                        }
                        long elapsed = System.nanoTime() - started;
                        if (round >= 0) {
                            samples.get(variant).add(
                                    elapsed / (executions * 1_000_000.0d));
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
        Files.writeString(reportDirectory.resolve("phase2d-repeated-execution-samples.tsv"),
                sampleTsv.toString(), StandardCharsets.UTF_8);

        StringBuilder dispersionTsv = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tgoverningDispersion\tstatus\n");
        for (String variant : samples.keySet()) {
            Distribution distribution = distributions.get(variant);
            DelosMeasurementValidityContract.DispersionDecision decision = decisions.get(variant);
            dispersionTsv.append(variant).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(decision.iqrToMedian())).append('\t')
                    .append(format(decision.madToMedian())).append('\t')
                    .append(format(decision.governingRatio())).append('\t')
                    .append(decision.status()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2d-dispersion.tsv"),
                dispersionTsv.toString(), StandardCharsets.UTF_8);

        String[] metricNames = {
            "PAGES_VISITED",
            "ROWS_VISITED",
            "ROWS_QUALIFIED",
            "MVCC_ORDERED_CANDIDATES",
            "MVCC_COVERING_CANDIDATES",
            "MVCC_COVERED_CANDIDATES",
            "MVCC_FALLBACK_CANDIDATES",
            "MVCC_DIRECTORY_PAGE_ACQUISITIONS",
            "MVCC_DIRECTORY_PAGE_BATCH_CANDIDATES",
            "MVCC_DIRECTORY_PAGE_REUSE_HITS",
            "MVCC_DIRECTORY_LOGICAL_FALLBACKS",
            "MVCC_VERSION_PAGE_ACQUISITIONS",
            "MVCC_VERSION_SLOT_FETCHES",
            "MVCC_VISIBILITY_CHECKS",
            "MVCC_VERSION_CHAIN_STEPS",
            "MVCC_VERSION_LOGICAL_FALLBACKS"
        };
        StringBuilder operatorTsv = new StringBuilder(
                "variant\texplainAnalyzeWallMillis\ttotalOpenMillis\ttotalNextMillis\ttotalOpens");
        for (String metric : metricNames) {
            operatorTsv.append('\t').append(metric);
        }
        operatorTsv.append('\n');
        for (String variant : sqlByVariant.keySet()) {
            ExplainCapture analyze = analyzeByVariant.get(variant);
            operatorTsv.append(variant).append('\t').append(analyze.wallMillis()).append('\t')
                    .append(sumPhase2AField(analyze.text(), "openMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "nextMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "opens"));
            for (String metric : metricNames) {
                operatorTsv.append('\t').append(sumPhase2AMetric(analyze.text(), metric));
            }
            operatorTsv.append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2d-operator-storage-summary.tsv"),
                operatorTsv.toString(), StandardCharsets.UTF_8);

        double heapForcedMedian = distributions.get("heap-forced-index-nl").median();
        double mvccForcedMedian = distributions.get("mvcc-forced-index-nl").median();
        double mvccNlOnlyMedian = distributions.get("mvcc-forced-nl-only").median();
        double mvccNaturalMedian = distributions.get("mvcc-natural").median();
        double naturalVsForcedRatio = mvccNaturalMedian / mvccForcedMedian;
        double nlOnlyVsForcedRatio = mvccNlOnlyMedian / mvccForcedMedian;
        double mvccVsHeapForcedRatio = mvccForcedMedian / heapForcedMedian;
        DelosMeasurementValidityContract.Status combinedStatus = DelosMeasurementValidityContract.combine(
                decisions.get("heap-forced-index-nl").status(),
                decisions.get("mvcc-forced-index-nl").status(),
                decisions.get("mvcc-forced-nl-only").status(),
                decisions.get("mvcc-natural").status());
        double worstGoverningDispersion = decisions.values().stream()
                .mapToDouble(DelosMeasurementValidityContract.DispersionDecision::governingRatio)
                .max()
                .orElseThrow();
        double materialRatioThreshold = 1.0d + Math.max(0.05d, 2.0d * worstGoverningDispersion);

        String classification;
        if (combinedStatus == DelosMeasurementValidityContract.Status.INVALID) {
            classification = "MEASUREMENT_INVALID_RETRY_SHORT_DIAGNOSTIC";
        } else if (!matchedHeapMvccForcedShape) {
            classification = "FORCED_HEAP_MVCC_SHAPE_MISMATCH_FIX_DIAGNOSTIC";
        } else if (mvccNaturalUsesHashJoin
                && mvccNlOnlyMatchesForcedIndexShape
                && naturalVsForcedRatio > materialRatioThreshold
                && nlOnlyVsForcedRatio < materialRatioThreshold) {
            classification = "NATURAL_HASH_JOIN_PLAN_DOMINATES_RESIDUAL_GAP";
        } else if (!mvccNlOnlyMatchesForcedIndexShape
                || nlOnlyVsForcedRatio >= materialRatioThreshold) {
            classification = "INDEX_DIRECTIVE_OR_PREDICATE_SHAPE_EFFECT_REMAINS";
        } else if (mvccVsHeapForcedRatio >= materialRatioThreshold) {
            classification = "MATCHED_PLAN_MVCC_PHYSICAL_ACCESS_GAP_REMAINS";
        } else {
            classification = "MATCHED_PLAN_PROVIDER_GAP_SMALL_OR_UNRESOLVED";
        }

        String summary = "DelosDB Phase-2D F05 residual indexed-access decomposition\n"
                + "rows=" + rowCount + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "warmupExecutionsPerVisit=" + warmupExecutionsPerVisit + "\n"
                + "measuredRoundsPerVariant=" + measuredRounds + "\n"
                + "measuredExecutionsPerSample=" + measuredExecutionsPerSample + "\n"
                + "semanticFingerprint=" + semanticFingerprint + "\n"
                + "matchedHeapMvccForcedPhysicalShape=" + matchedHeapMvccForcedShape + "\n"
                + "mvccNlOnlyMatchesForcedIndexShape=" + mvccNlOnlyMatchesForcedIndexShape + "\n"
                + "mvccNaturalUsesHashJoin=" + mvccNaturalUsesHashJoin + "\n"
                + "mvccNaturalUsesOrderCustomerIndex=" + mvccNaturalUsesOrderIndex + "\n"
                + "mvccNaturalUsesLineOrderIndex=" + mvccNaturalUsesLineIndex + "\n"
                + "heapForcedMedianMillis=" + format(heapForcedMedian) + "\n"
                + "mvccForcedMedianMillis=" + format(mvccForcedMedian) + "\n"
                + "mvccForcedNlOnlyMedianMillis=" + format(mvccNlOnlyMedian) + "\n"
                + "mvccNaturalMedianMillis=" + format(mvccNaturalMedian) + "\n"
                + "mvccNaturalVsForcedIndexRatio=" + format(naturalVsForcedRatio) + "\n"
                + "mvccNlOnlyVsForcedIndexRatio=" + format(nlOnlyVsForcedRatio) + "\n"
                + "mvccForcedVsHeapForcedRatio=" + format(mvccVsHeapForcedRatio) + "\n"
                + "worstGoverningDispersion=" + format(worstGoverningDispersion) + "\n"
                + "materialRatioThreshold=" + format(materialRatioThreshold) + "\n"
                + "measurementStatus=" + combinedStatus + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(reportDirectory.resolve("phase2d-f05-residual-indexed-access-summary.txt"),
                summary, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("query-shapes.txt"),
                "Heap forced index/NL SQL:\n" + heapForcedSql
                        + "\n\nMVCC forced index/NL SQL:\n" + mvccForcedSql
                        + "\n\nMVCC forced NL-only SQL:\n" + mvccForcedNestedLoopSql
                        + "\n\nMVCC natural post-statistics SQL:\n" + mvccNaturalSql + "\n",
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static void runPhase2EF05BaseFetchAttribution() throws Exception {
        Path reportDirectory = requiredPhase2EPath("reportDirectory");
        Path databaseRoot = requiredPhase2EPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 1_000;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        int heapIterations = 3_000;
        int mvccIterations = 50;
        int warmups = 4;
        String heapBase = "P2E_HEAP";
        String mvccBase = "P2E_MVCC";
        String database = databaseRoot.resolve("f05-base-fetch-attribution").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareMultiJoinFixture(setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String heapPkIndex;
        String mvccPkIndex;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(heapBase), "ID");
            mvccPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(mvccBase), "ID");
            for (String base : List.of(heapBase, mvccBase)) {
                phase2BUpdateStatistics(connection, multiJoinCustomerTableName(base));
                phase2BUpdateStatistics(connection, multiJoinOrderTableName(base));
                phase2BUpdateStatistics(connection, multiJoinLineTableName(base));
            }
            connection.commit();
        }

        String heapSql = phase2BForcedIndexedSql(heapBase, heapPkIndex);
        String mvccSql = phase2BForcedIndexedSql(mvccBase, mvccPkIndex);

        ExplainCapture heapExplain;
        ExplainCapture mvccExplain;
        ExplainCapture heapAnalyze;
        ExplainCapture mvccAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapExplain = capturePhase2AExplain(connection, heapSql, false);
            mvccExplain = capturePhase2AExplain(connection, mvccSql, false);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapAnalyze = capturePhase2AExplain(connection, heapSql, true);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            mvccAnalyze = capturePhase2AExplain(connection, mvccSql, true);
            connection.rollback();
        }

        String heapShape = normalizePhase2DPlanShape(
                heapExplain.text(), heapBase, "heap", heapPkIndex);
        String mvccShape = normalizePhase2DPlanShape(
                mvccExplain.text(), mvccBase, "delos_mvcc", mvccPkIndex);
        if (!heapShape.equals(mvccShape)) {
            throw new IllegalStateException(
                    "Phase-2E requires matched forced Heap/MVCC plan shapes");
        }
        Files.writeString(reportDirectory.resolve("heap-plan-shape-normalized.txt"),
                heapShape, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("mvcc-plan-shape-normalized.txt"),
                mvccShape, StandardCharsets.UTF_8);
        writePhase2ACapture(reportDirectory, "heap-explain-analyze", heapAnalyze);
        writePhase2ACapture(reportDirectory, "mvcc-explain-analyze", mvccAnalyze);

        long semanticFingerprint;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            semanticFingerprint = executePhase2AQuery(connection, heapSql, expectedRows);
            long mvccFingerprint = executePhase2AQuery(connection, mvccSql, expectedRows);
            connection.rollback();
            if (semanticFingerprint != mvccFingerprint) {
                throw new IllegalStateException(
                        "Phase-2E SQL semantic drift: heap=" + semanticFingerprint
                                + ", mvcc=" + mvccFingerprint);
            }
        }

        Configuration profile = Configuration.getConfiguration("profile");
        Phase2EProfile heapProfile = profilePhase2EVariant(
                jdbcUrl, heapSql, expectedRows, semanticFingerprint, warmups,
                heapIterations, profile, reportDirectory.resolve("heap-forced-index-nl.jfr"));
        Phase2EProfile mvccProfile = profilePhase2EVariant(
                jdbcUrl, mvccSql, expectedRows, semanticFingerprint, warmups,
                mvccIterations, profile, reportDirectory.resolve("mvcc-forced-index-nl.jfr"));

        long heapIndexToBaseNextMillis = phase2EIndexToBaseNextMillis(heapAnalyze.text());
        long mvccIndexToBaseNextMillis = phase2EIndexToBaseNextMillis(mvccAnalyze.text());
        long heapIndexScanNextMillis = phase2EIndexScanNextMillis(heapAnalyze.text());
        long mvccIndexScanNextMillis = phase2EIndexScanNextMillis(mvccAnalyze.text());

        String summary = "DelosDB Phase-2E F05 matched-plan base-fetch JFR attribution\n"
                + "rows=" + rowCount + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "semanticFingerprint=" + semanticFingerprint + "\n"
                + "matchedForcedPlanShape=true\n"
                + "heapProfileIterations=" + heapIterations + "\n"
                + "mvccProfileIterations=" + mvccIterations + "\n"
                + "heapProfileElapsedMillis=" + format(heapProfile.elapsedMillis()) + "\n"
                + "mvccProfileElapsedMillis=" + format(mvccProfile.elapsedMillis()) + "\n"
                + "heapProfileMillisPerExecution=" + format(heapProfile.millisPerExecution()) + "\n"
                + "mvccProfileMillisPerExecution=" + format(mvccProfile.millisPerExecution()) + "\n"
                + "heapIndexToBaseNextMillis=" + heapIndexToBaseNextMillis + "\n"
                + "mvccIndexToBaseNextMillis=" + mvccIndexToBaseNextMillis + "\n"
                + "heapIndexScanNextMillis=" + heapIndexScanNextMillis + "\n"
                + "mvccIndexScanNextMillis=" + mvccIndexScanNextMillis + "\n"
                + "sourceHypothesis=MVCC_BASE_FETCH_REOPENS_DIRECTORY_AND_VERSION_CONTAINERS_PER_ROW\n"
                + "interpretation=JFR_ATTRIBUTION_ONLY_NOT_ACCEPTANCE_PERFORMANCE\n";
        Files.writeString(
                reportDirectory.resolve("phase2e-f05-base-fetch-attribution-summary.txt"),
                summary, StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("query-shapes.txt"),
                "Heap forced index/NL SQL:\n" + heapSql
                        + "\n\nMVCC forced index/NL SQL:\n" + mvccSql + "\n",
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static void runPhase2HF05PostHintResidualAttribution() throws Exception {
        Path reportDirectory = requiredPhase2HPath("reportDirectory");
        Path databaseRoot = requiredPhase2HPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 1_000;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        int heapIterations = 6_000;
        int mvccIterations = 2_500;
        int warmups = 256;
        String heapBase = "P2H_HEAP";
        String mvccBase = "P2H_MVCC";
        String database = databaseRoot.resolve("f05-post-hint-residual-attribution").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareMultiJoinFixture(setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String heapPkIndex;
        String mvccPkIndex;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(heapBase), "ID");
            mvccPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(mvccBase), "ID");
            for (String base : List.of(heapBase, mvccBase)) {
                phase2BUpdateStatistics(connection, multiJoinCustomerTableName(base));
                phase2BUpdateStatistics(connection, multiJoinOrderTableName(base));
                phase2BUpdateStatistics(connection, multiJoinLineTableName(base));
            }
            connection.commit();
        }

        String heapSql = phase2BForcedIndexedSql(heapBase, heapPkIndex);
        String mvccSql = phase2BForcedIndexedSql(mvccBase, mvccPkIndex);

        ExplainCapture heapExplain;
        ExplainCapture mvccExplain;
        ExplainCapture heapAnalyze;
        ExplainCapture mvccAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapExplain = capturePhase2AExplain(connection, heapSql, false);
            mvccExplain = capturePhase2AExplain(connection, mvccSql, false);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapAnalyze = capturePhase2AExplain(connection, heapSql, true);
            connection.rollback();
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            mvccAnalyze = capturePhase2AExplain(connection, mvccSql, true);
            connection.rollback();
        }

        String heapShape = normalizePhase2DPlanShape(
                heapExplain.text(), heapBase, "heap", heapPkIndex);
        String mvccShape = normalizePhase2DPlanShape(
                mvccExplain.text(), mvccBase, "delos_mvcc", mvccPkIndex);
        if (!heapShape.equals(mvccShape)) {
            throw new IllegalStateException(
                    "Phase-2H requires matched forced Heap/MVCC plan shapes");
        }
        Files.writeString(reportDirectory.resolve("heap-plan-shape-normalized.txt"),
                heapShape, StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("mvcc-plan-shape-normalized.txt"),
                mvccShape, StandardCharsets.UTF_8);
        writePhase2ACapture(reportDirectory, "heap-explain-analyze", heapAnalyze);
        writePhase2ACapture(reportDirectory, "mvcc-explain-analyze", mvccAnalyze);

        long semanticFingerprint;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            semanticFingerprint = executePhase2AQuery(connection, heapSql, expectedRows);
            long mvccFingerprint = executePhase2AQuery(connection, mvccSql, expectedRows);
            connection.rollback();
            if (semanticFingerprint != mvccFingerprint) {
                throw new IllegalStateException(
                        "Phase-2H SQL semantic drift: heap=" + semanticFingerprint
                                + ", mvcc=" + mvccFingerprint);
            }
        }

        Configuration profile = Configuration.getConfiguration("profile");
        Phase2EProfile heapProfile = profilePhase2HVariant(
                jdbcUrl, heapSql, expectedRows, semanticFingerprint, warmups,
                heapIterations, profile, reportDirectory.resolve("heap-forced-index-nl.jfr"));
        Phase2EProfile mvccProfile = profilePhase2HVariant(
                jdbcUrl, mvccSql, expectedRows, semanticFingerprint, warmups,
                mvccIterations, profile, reportDirectory.resolve("mvcc-forced-index-nl.jfr"));

        long heapIndexToBaseNextMillis = phase2EIndexToBaseNextMillis(heapAnalyze.text());
        long mvccIndexToBaseNextMillis = phase2EIndexToBaseNextMillis(mvccAnalyze.text());
        long heapIndexScanNextMillis = phase2EIndexScanNextMillis(heapAnalyze.text());
        long mvccIndexScanNextMillis = phase2EIndexScanNextMillis(mvccAnalyze.text());

        String summary = "DelosDB Phase-2H F05 post-directory-hint residual JFR attribution\n"
                + "rows=" + rowCount + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "semanticFingerprint=" + semanticFingerprint + "\n"
                + "matchedForcedPlanShape=true\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "heapProfileIterations=" + heapIterations + "\n"
                + "mvccProfileIterations=" + mvccIterations + "\n"
                + "heapProfileElapsedMillis=" + format(heapProfile.elapsedMillis()) + "\n"
                + "mvccProfileElapsedMillis=" + format(mvccProfile.elapsedMillis()) + "\n"
                + "heapProfileMillisPerExecution=" + format(heapProfile.millisPerExecution()) + "\n"
                + "mvccProfileMillisPerExecution=" + format(mvccProfile.millisPerExecution()) + "\n"
                + "profileRatio=" + format(
                        mvccProfile.millisPerExecution() / heapProfile.millisPerExecution()) + "\n"
                + "heapIndexToBaseNextMillis=" + heapIndexToBaseNextMillis + "\n"
                + "mvccIndexToBaseNextMillis=" + mvccIndexToBaseNextMillis + "\n"
                + "heapIndexScanNextMillis=" + heapIndexScanNextMillis + "\n"
                + "mvccIndexScanNextMillis=" + mvccIndexScanNextMillis + "\n"
                + "acceptedPhase2GHeapMedianMillis=1.133244\n"
                + "acceptedPhase2GMvccMedianMillis=2.672889\n"
                + "acceptedPhase2GRatio=2.358617\n"
                + "sourceQuestion=WHAT_MACHINE_WORK_EXPLAINS_POST_HINT_MVCC_RESIDUAL\n"
                + "interpretation=JFR_ATTRIBUTION_ONLY_NOT_ACCEPTANCE_PERFORMANCE\n";
        Files.writeString(
                reportDirectory.resolve("phase2h-f05-post-hint-residual-attribution-summary.txt"),
                summary, StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("query-shapes.txt"),
                "Heap forced index/NL SQL:\n" + heapSql
                        + "\n\nMVCC forced index/NL SQL:\n" + mvccSql + "\n",
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Phase2EProfile profilePhase2HVariant(
            String jdbcUrl,
            String sql,
            int expectedRows,
            long expectedFingerprint,
            int warmups,
            int iterations,
            Configuration profile,
            Path recordingPath) throws Exception {
        try (Connection connection = openPhase2AConnection(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < warmups; i++) {
                long fingerprint = executeFitnessRead(
                        statement, Workload.JOIN_3WAY_SELECTIVE, expectedRows);
                if (fingerprint != expectedFingerprint) {
                    throw new IllegalStateException(
                            "Phase-2H warmup semantic drift for " + recordingPath.getFileName());
                }
            }

            long started;
            long elapsed;
            try (Recording recording = new Recording(profile)) {
                recording.setName(recordingPath.getFileName().toString());
                recording.setToDisk(true);
                recording.start();
                started = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    long fingerprint = executeFitnessRead(
                            statement, Workload.JOIN_3WAY_SELECTIVE, expectedRows);
                    if (fingerprint != expectedFingerprint) {
                        throw new IllegalStateException(
                                "Phase-2H profiled semantic drift for "
                                        + recordingPath.getFileName() + " iteration " + i);
                    }
                }
                elapsed = System.nanoTime() - started;
                recording.stop();
                recording.dump(recordingPath);
            }
            connection.rollback();
            return new Phase2EProfile(elapsed / 1_000_000.0d, iterations);
        }
    }

    static Path requiredPhase2HPath(String key) {
        String value = System.getProperty(PHASE2H_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2H_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static Phase2EProfile profilePhase2EVariant(
            String jdbcUrl,
            String sql,
            int expectedRows,
            long expectedFingerprint,
            int warmups,
            int iterations,
            Configuration profile,
            Path recordingPath) throws Exception {
        try (Connection connection = openPhase2AConnection(jdbcUrl);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < warmups; i++) {
                long fingerprint = executeFitnessRead(
                        statement, Workload.JOIN_3WAY_SELECTIVE, expectedRows);
                if (fingerprint != expectedFingerprint) {
                    throw new IllegalStateException(
                            "Phase-2E warmup semantic drift for " + recordingPath.getFileName());
                }
            }

            long started;
            long elapsed;
            try (Recording recording = new Recording(profile)) {
                recording.setName(recordingPath.getFileName().toString());
                recording.setToDisk(true);
                recording.start();
                started = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    long fingerprint = executeFitnessRead(
                            statement, Workload.JOIN_3WAY_SELECTIVE, expectedRows);
                    if (fingerprint != expectedFingerprint) {
                        throw new IllegalStateException(
                                "Phase-2E profiled semantic drift for "
                                        + recordingPath.getFileName() + " iteration " + i);
                    }
                }
                elapsed = System.nanoTime() - started;
                recording.stop();
                recording.dump(recordingPath);
            }
            connection.rollback();
            return new Phase2EProfile(elapsed / 1_000_000.0d, iterations);
        }
    }

    static Path requiredPhase2EPath(String key) {
        String value = System.getProperty(PHASE2E_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2E_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2JF05CompatibilityNativePathDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2JPath("reportDirectory");
        Path databaseRoot = requiredPhase2JPath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int commitBatchSize = 1_000;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        int warmups = 4;
        int warmupExecutionsPerVisit = 16;
        int measuredRounds = 9;
        int measuredExecutionsPerSample = 64;
        String base = "P2J_MVCC";
        String database = databaseRoot.resolve("f05-compatibility-native-path").toString();
        String jdbcUrl = "jdbc:derby:" + database + ";create=true";

        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(setup, base, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String customerPkIndex;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            customerPkIndex = phase2BPrimaryKeyIndex(
                    connection, multiJoinCustomerTableName(base), "ID");
            phase2BUpdateStatistics(connection, multiJoinCustomerTableName(base));
            phase2BUpdateStatistics(connection, multiJoinOrderTableName(base));
            phase2BUpdateStatistics(connection, multiJoinLineTableName(base));
            connection.commit();
        }

        String customerPk = "'" + customerPkIndex + "'";
        String orderIndex = multiJoinOrderTableName(base) + "_C_IDX";
        String lineIndex = multiJoinLineTableName(base) + "_O_IDX";

        LinkedHashMap<String, String> sqlByVariant = new LinkedHashMap<>();
        sqlByVariant.put("compat-all",
                phase2JAccessSql(base, customerPk, orderIndex, lineIndex));
        sqlByVariant.put("native-customer",
                phase2JAccessSql(base, "null", orderIndex, lineIndex));
        sqlByVariant.put("native-order",
                phase2JAccessSql(base, customerPk, "null", lineIndex));
        sqlByVariant.put("native-line",
                phase2JAccessSql(base, customerPk, orderIndex, "null"));
        sqlByVariant.put("native-all",
                phase2JAccessSql(base, "null", "null", "null"));

        StringBuilder queryShapes = new StringBuilder();
        for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
            queryShapes.append("=== ").append(entry.getKey()).append(" ===\n")
                    .append(entry.getValue()).append("\n\n");
        }
        Files.writeString(reportDirectory.resolve("query-shapes.txt"),
                queryShapes.toString(), StandardCharsets.UTF_8);

        LinkedHashMap<String, ExplainCapture> explainByVariant = new LinkedHashMap<>();
        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        long semanticFingerprint = Long.MIN_VALUE;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, String> entry : sqlByVariant.entrySet()) {
                ExplainCapture explain = capturePhase2AExplain(connection, entry.getValue(), false);
                ExplainCapture analyze = capturePhase2AExplain(connection, entry.getValue(), true);
                explainByVariant.put(entry.getKey(), explain);
                analyzeByVariant.put(entry.getKey(), analyze);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain", explain);
                writePhase2ACapture(reportDirectory, entry.getKey() + "-explain-analyze", analyze);

                long fingerprint = executePhase2AQuery(connection, entry.getValue(), expectedRows);
                if (semanticFingerprint == Long.MIN_VALUE) {
                    semanticFingerprint = fingerprint;
                } else if (fingerprint != semanticFingerprint) {
                    throw new IllegalStateException(
                            "Phase-2J semantic drift for " + entry.getKey()
                                    + ": expected=" + semanticFingerprint
                                    + ", actual=" + fingerprint);
                }
            }
            connection.rollback();
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
                        long started = System.nanoTime();
                        for (int execution = 0; execution < executions; execution++) {
                            long fingerprint = executeFitnessRead(
                                    statements.get(variant),
                                    Workload.JOIN_3WAY_SELECTIVE,
                                    expectedRows);
                            if (fingerprint != semanticFingerprint) {
                                throw new IllegalStateException(
                                        "Phase-2J measured semantic drift for " + variant
                                                + ": expected=" + semanticFingerprint
                                                + ", actual=" + fingerprint);
                            }
                        }
                        long elapsed = System.nanoTime() - started;
                        if (round >= 0) {
                            samples.get(variant).add(
                                    elapsed / (executions * 1_000_000.0d));
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
        Files.writeString(reportDirectory.resolve("phase2j-repeated-execution-samples.tsv"),
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
        Files.writeString(reportDirectory.resolve("phase2j-dispersion.tsv"),
                dispersionTsv.toString(), StandardCharsets.UTF_8);

        String[] metricNames = {
            "MVCC_ORDERED_CANDIDATES",
            "MVCC_DIRECTORY_PAGE_ACQUISITIONS",
            "MVCC_DIRECTORY_LOGICAL_FALLBACKS",
            "MVCC_VERSION_PAGE_ACQUISITIONS",
            "MVCC_VERSION_SLOT_FETCHES",
            "MVCC_VISIBILITY_CHECKS",
            "MVCC_VERSION_CHAIN_STEPS",
            "MVCC_VERSION_LOGICAL_FALLBACKS"
        };
        StringBuilder operatorTsv = new StringBuilder(
                "variant\texplainAnalyzeWallMillis\ttotalOpenMillis\ttotalNextMillis\ttotalOpens");
        for (String metric : metricNames) {
            operatorTsv.append('\t').append(metric);
        }
        operatorTsv.append('\n');
        for (String variant : sqlByVariant.keySet()) {
            ExplainCapture analyze = analyzeByVariant.get(variant);
            operatorTsv.append(variant).append('\t').append(analyze.wallMillis()).append('\t')
                    .append(sumPhase2AField(analyze.text(), "openMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "nextMillis")).append('\t')
                    .append(sumPhase2AField(analyze.text(), "opens"));
            for (String metric : metricNames) {
                operatorTsv.append('\t').append(sumPhase2AMetric(analyze.text(), metric));
            }
            operatorTsv.append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2j-operator-storage-summary.tsv"),
                operatorTsv.toString(), StandardCharsets.UTF_8);

        double compatMedian = distributions.get("compat-all").median();
        String bestVariant = "compat-all";
        double bestMedian = compatMedian;
        for (String variant : sqlByVariant.keySet()) {
            double median = distributions.get(variant).median();
            if (median < bestMedian) {
                bestMedian = median;
                bestVariant = variant;
            }
        }
        double bestVsCompatSpeedup = compatMedian / bestMedian;
        double nativeCustomerSpeedup = compatMedian / distributions.get("native-customer").median();
        double nativeOrderSpeedup = compatMedian / distributions.get("native-order").median();
        double nativeLineSpeedup = compatMedian / distributions.get("native-line").median();
        double nativeAllSpeedup = compatMedian / distributions.get("native-all").median();

        DelosMeasurementValidityContract.Status combinedStatus = DelosMeasurementValidityContract.combine(
                decisions.get("compat-all").status(),
                decisions.get("native-customer").status(),
                decisions.get("native-order").status(),
                decisions.get("native-line").status(),
                decisions.get("native-all").status());
        double worstGoverningDispersion = decisions.values().stream()
                .mapToDouble(DelosMeasurementValidityContract.DispersionDecision::governingRatio)
                .max()
                .orElseThrow();
        double materialRatioThreshold = 1.0d + Math.max(0.05d, 2.0d * worstGoverningDispersion);

        long bestVariantOrderedCandidates = "compat-all".equals(bestVariant)
                ? 0L
                : sumPhase2AMetric(
                        analyzeByVariant.get(bestVariant).text(), "MVCC_ORDERED_CANDIDATES");
        boolean bestVariantUsesNativeOrderedPath = bestVariantOrderedCandidates > 0L
                || analyzeByVariant.get(bestVariant).text()
                        .contains("delos_mvcc_rawstore_ordered_index");

        String classification;
        if (combinedStatus == DelosMeasurementValidityContract.Status.INVALID) {
            classification = "MEASUREMENT_INVALID_RETRY_SHORT_DIAGNOSTIC";
        } else if (bestVsCompatSpeedup >= materialRatioThreshold
                && !"compat-all".equals(bestVariant)
                && bestVariantUsesNativeOrderedPath) {
            classification =
                    "NATIVE_CURRENT_ROW_PATH_MATERIALLY_BEATS_COMPATIBILITY_BASE_FETCH";
        } else {
            classification =
                    "COMPATIBILITY_BASE_FETCH_RESIDUAL_NOT_EXPLAINED_BY_NATIVE_CURRENT_ROW_PATH";
        }

        String summary = "DelosDB Phase-2J F05 compatibility/native path decomposition\n"
                + "diagnosticOnly=true\n"
                + "rows=" + rowCount + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "warmupExecutionsPerVisit=" + warmupExecutionsPerVisit + "\n"
                + "measuredRoundsPerVariant=" + measuredRounds + "\n"
                + "measuredExecutionsPerSample=" + measuredExecutionsPerSample + "\n"
                + "semanticFingerprint=" + semanticFingerprint + "\n"
                + "compatAllMedianMillis=" + format(compatMedian) + "\n"
                + "nativeCustomerMedianMillis="
                + format(distributions.get("native-customer").median()) + "\n"
                + "nativeOrderMedianMillis="
                + format(distributions.get("native-order").median()) + "\n"
                + "nativeLineMedianMillis="
                + format(distributions.get("native-line").median()) + "\n"
                + "nativeAllMedianMillis="
                + format(distributions.get("native-all").median()) + "\n"
                + "nativeCustomerVsCompatSpeedup=" + format(nativeCustomerSpeedup) + "\n"
                + "nativeOrderVsCompatSpeedup=" + format(nativeOrderSpeedup) + "\n"
                + "nativeLineVsCompatSpeedup=" + format(nativeLineSpeedup) + "\n"
                + "nativeAllVsCompatSpeedup=" + format(nativeAllSpeedup) + "\n"
                + "bestVariant=" + bestVariant + "\n"
                + "bestVsCompatSpeedup=" + format(bestVsCompatSpeedup) + "\n"
                + "bestVariantOrderedCandidates=" + bestVariantOrderedCandidates + "\n"
                + "bestVariantUsesNativeOrderedPath=" + bestVariantUsesNativeOrderedPath + "\n"
                + "worstGoverningDispersion=" + format(worstGoverningDispersion) + "\n"
                + "materialRatioThreshold=" + format(materialRatioThreshold) + "\n"
                + "measurementStatus=" + combinedStatus + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(reportDirectory.resolve(
                        "phase2j-f05-compatibility-native-path-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);
    }

    static String phase2JAccessSql(
            String base, String customerIndex, String orderIndex, String lineIndex) {
        String customer = multiJoinCustomerTableName(base);
        String order = multiJoinOrderTableName(base);
        String line = multiJoinLineTableName(base);
        return "select c.id, o.id, l.id from --DERBY-PROPERTIES joinOrder=FIXED\n"
                + customer + " c --DERBY-PROPERTIES index=" + customerIndex + "\n"
                + "join " + order + " o --DERBY-PROPERTIES index=" + orderIndex
                + ", joinStrategy=NESTEDLOOP\n"
                + "on o.customer_id = c.id\n"
                + "join " + line + " l --DERBY-PROPERTIES index=" + lineIndex
                + ", joinStrategy=NESTEDLOOP\n"
                + "on l.order_id = o.id\n"
                + "where c.id between ? and ?";
    }

    static void runPhase2UF04CurrentJoinDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2UPath("reportDirectory");
        Path databaseRoot = requiredPhase2UPath("databaseRoot");
        boolean physicalScanCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalScanCost.enabled", "true"));
        boolean physicalRowLocationCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalRowLocationCost.enabled", "true"));
        boolean projectedCurrentRead = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean baseFetchPagePrefetch = Boolean.getBoolean(
                "delosdb.experimental.mvccBaseFetchPagePrefetch");
        if (!physicalScanCost || !physicalRowLocationCost
                || projectedCurrentRead || baseFetchPagePrefetch) {
            throw new IllegalStateException(
                    "Phase-2U requires current F04 production read settings: physical scan and "
                            + "RowLocation costing enabled, projected-current and base-fetch "
                            + "prefetch disabled");
        }

        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        int warmups = 2;
        int warmupExecutionsPerVisit = 16;
        int measuredRounds = 7;
        int measuredExecutionsPerSample = 32;
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f04-current-join-decomposition") + ";create=true";
        DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                rowCount, payloadSize, SEED, commitBatchSize);

        String heapBase;
        String mvccBase;
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            DelosJdbcBenchmarkScenario heap = new DelosJdbcBenchmarkScenario(
                    setup, "phase2u_heap", "", false, config);
            heap.prepare();
            heapBase = heap.tableName();
            prepareJoinDimensionFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareJoinFanoutFixture(setup, heapBase, "", rowCount, commitBatchSize);

            DelosJdbcBenchmarkScenario mvcc = new DelosJdbcBenchmarkScenario(
                    setup, "phase2u_mvcc", " using delos_mvcc", false, config);
            mvcc.prepare();
            mvccBase = mvcc.tableName();
            prepareJoinDimensionFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            prepareJoinFanoutFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        List<String> statisticsTables = List.of(
                heapBase,
                joinDimensionTableName(heapBase),
                joinFanoutParentTableName(heapBase),
                joinFanoutChildTableName(heapBase),
                mvccBase,
                joinDimensionTableName(mvccBase),
                joinFanoutParentTableName(mvccBase),
                joinFanoutChildTableName(mvccBase));

        LinkedHashMap<String, Phase2UVariant> variants = new LinkedHashMap<>();
        addPhase2UF04Variants(variants, "heap", heapBase);
        addPhase2UF04Variants(variants, "mvcc", mvccBase);
        writePhase2UQueryShapes(reportDirectory, variants);

        String statisticsBefore;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            statisticsBefore = phase2PStatisticsSnapshot(connection, statisticsTables);
            connection.rollback();
        }
        Files.writeString(reportDirectory.resolve("phase2u-sysstatistics-before.tsv"),
                statisticsBefore, StandardCharsets.UTF_8);

        LinkedHashMap<String, ExplainCapture> analyzeByVariant = new LinkedHashMap<>();
        LinkedHashMap<String, Long> semanticByVariant = new LinkedHashMap<>();
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, Phase2UVariant> entry : variants.entrySet()) {
                String name = entry.getKey();
                Phase2UVariant variant = entry.getValue();
                ExplainCapture explain = capturePhase2UExplain(connection, variant, false);
                ExplainCapture analyze = capturePhase2UExplain(connection, variant, true);
                writePhase2ACapture(reportDirectory, name + "-explain", explain);
                writePhase2ACapture(reportDirectory, name + "-explain-analyze", analyze);
                analyzeByVariant.put(name, analyze);
                try (PreparedStatement statement = connection.prepareStatement(variant.sql())) {
                    semanticByVariant.put(name, executePhase2URead(statement, variant));
                }
            }
            connection.rollback();
        }
        assertPhase2UProviderSemantics(semanticByVariant, variants);

        LinkedHashMap<String, List<Double>> samples = new LinkedHashMap<>();
        for (String name : variants.keySet()) {
            samples.put(name, new ArrayList<>());
        }
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            LinkedHashMap<String, PreparedStatement> statements = new LinkedHashMap<>();
            try {
                for (Map.Entry<String, Phase2UVariant> entry : variants.entrySet()) {
                    statements.put(entry.getKey(), connection.prepareStatement(entry.getValue().sql()));
                }
                List<String> names = new ArrayList<>(variants.keySet());
                for (int round = -warmups; round < measuredRounds; round++) {
                    int rotation = Math.floorMod(round + warmups, names.size());
                    for (int offset = 0; offset < names.size(); offset++) {
                        String name = names.get((rotation + offset) % names.size());
                        Phase2UVariant variant = variants.get(name);
                        int executions = round >= 0
                                ? measuredExecutionsPerSample : warmupExecutionsPerVisit;
                        long expectedFingerprint = semanticByVariant.get(name);
                        long started = System.nanoTime();
                        for (int execution = 0; execution < executions; execution++) {
                            long fingerprint = executePhase2URead(statements.get(name), variant);
                            if (fingerprint != expectedFingerprint) {
                                throw new IllegalStateException(
                                        "Phase-2U measured semantic drift for " + name);
                            }
                        }
                        long elapsed = System.nanoTime() - started;
                        if (round >= 0) {
                            samples.get(name).add(elapsed / (executions * 1_000_000.0d));
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

        LinkedHashMap<String, Distribution> distributions = new LinkedHashMap<>();
        LinkedHashMap<String, DelosMeasurementValidityContract.Status> statuses =
                writePhase2UMeasurementTables(reportDirectory, samples, analyzeByVariant, distributions);
        DelosMeasurementValidityContract.Status combinedStatus =
                DelosMeasurementValidityContract.combine(statuses.values().toArray(
                        DelosMeasurementValidityContract.Status[]::new));

        String statisticsAfter;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            statisticsAfter = phase2PStatisticsSnapshot(connection, statisticsTables);
            connection.rollback();
        }
        Files.writeString(reportDirectory.resolve("phase2u-sysstatistics-after.tsv"),
                statisticsAfter, StandardCharsets.UTF_8);
        boolean statisticsCatalogStable = statisticsBefore.equals(statisticsAfter);

        String summary = phase2USummary(
                physicalScanCost,
                physicalRowLocationCost,
                projectedCurrentRead,
                baseFetchPagePrefetch,
                warmups,
                warmupExecutionsPerVisit,
                measuredRounds,
                measuredExecutionsPerSample,
                statisticsCatalogStable,
                distributions,
                combinedStatus);
        Files.writeString(reportDirectory.resolve("phase2u-f04-current-join-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.println(summary);
    }

    static void addPhase2UF04Variants(
            Map<String, Phase2UVariant> variants, String provider, String base) {
        String dimension = joinDimensionTableName(base);
        String parent = joinFanoutParentTableName(base);
        String child = joinFanoutChildTableName(base);
        variants.put(provider + "-1to1-natural", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_1TO1, base), 1_000, 1, new int[0]));
        variants.put(provider + "-1to1-base-range", new Phase2UVariant(
                "select id from " + base + " where id between 1 and 1000", 1_000, 1, new int[0]));
        variants.put(provider + "-1to1-dimension-scan", new Phase2UVariant(
                "select id from " + dimension, 1_000, 1, new int[0]));
        variants.put(provider + "-fanout-natural", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_FANOUT, base), 1_000, 2, new int[] {1, 100}));
        variants.put(provider + "-fanout-parent-range", new Phase2UVariant(
                "select id from " + parent + " where id between ? and ?", 100, 1,
                new int[] {1, 100}));
        variants.put(provider + "-fanout-child-covered", new Phase2UVariant(
                "select parent_id from " + child + " where parent_id between ? and ?", 1_000, 1,
                new int[] {1, 100}));
        variants.put(provider + "-fanout-child-rowbearing", new Phase2UVariant(
                "select parent_id, id from " + child + " where parent_id between ? and ?", 1_000, 2,
                new int[] {1, 100}));
    }

    static void writePhase2UQueryShapes(
            Path reportDirectory, Map<String, Phase2UVariant> variants) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Phase2UVariant> entry : variants.entrySet()) {
            out.append("=== ").append(entry.getKey()).append(" ===\n")
                    .append(entry.getValue().sql()).append("\nparameters=")
                    .append(Arrays.toString(entry.getValue().parameters())).append("\n\n");
        }
        Files.writeString(reportDirectory.resolve("phase2u-query-shapes.txt"),
                out.toString(), StandardCharsets.UTF_8);
    }

    static ExplainCapture capturePhase2UExplain(
            Connection connection, Phase2UVariant variant, boolean analyze) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(
                (analyze ? "explain analyze " : "explain ") + variant.sql())) {
            bindPhase2UParameters(statement, variant.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Phase-2U EXPLAIN returned no row");
                }
                String text = resultSet.getString(1);
                String json = resultSet.getString(2);
                if (resultSet.next()) {
                    throw new SQLException("Phase-2U EXPLAIN returned more than one row");
                }
                return new ExplainCapture(
                        text, json, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    static long executePhase2URead(
            PreparedStatement statement, Phase2UVariant variant) throws SQLException {
        bindPhase2UParameters(statement, variant.parameters());
        int rows = 0;
        long fingerprint = 0L;
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                long tuple = 0x9E3779B97F4A7C15L;
                for (int column = 1; column <= variant.resultColumns(); column++) {
                    tuple = mix(tuple, resultSet.getLong(column));
                }
                fingerprint += tuple;
                rows++;
            }
        }
        if (rows != variant.expectedRows()) {
            throw new SQLException(
                    "Phase-2U row-count drift: expected=" + variant.expectedRows()
                            + ", actual=" + rows + ", sql=" + variant.sql());
        }
        return mix(fingerprint, rows);
    }

    static void bindPhase2UParameters(PreparedStatement statement, int[] parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setInt(index + 1, parameters[index]);
        }
    }

    static void assertPhase2UProviderSemantics(
            Map<String, Long> fingerprints, Map<String, Phase2UVariant> variants) {
        for (String name : variants.keySet()) {
            if (!name.startsWith("heap-")) {
                continue;
            }
            String suffix = name.substring("heap-".length());
            long heap = fingerprints.get(name);
            long mvcc = fingerprints.get("mvcc-" + suffix);
            if (heap != mvcc) {
                throw new IllegalStateException(
                        "Phase-2U Heap/MVCC semantic drift for " + suffix
                                + ": heap=" + heap + ", mvcc=" + mvcc);
            }
        }
    }

    static LinkedHashMap<String, DelosMeasurementValidityContract.Status>
            writePhase2UMeasurementTables(
                    Path reportDirectory,
                    Map<String, List<Double>> samples,
                    Map<String, ExplainCapture> analyzeByVariant,
                    Map<String, Distribution> distributions) throws IOException {
        StringBuilder samplesTsv = new StringBuilder("variant\tround\telapsedMillis\n");
        StringBuilder dispersionTsv = new StringBuilder(
                "variant\tmedianMillis\tiqrToMedian\tmadToMedian\tmaxToMin\tstatus\n");
        StringBuilder operatorTsv = new StringBuilder(
                "variant\texplainAnalyzeWallMillis\tplanShape\tjoinNextMillis\t"
                        + "indexToBaseNextMillis\tindexScanNextMillis\ttableScanNextMillis\t"
                        + "totalOpenMillis\ttotalNextMillis\ttotalOpens\n");
        StringBuilder estimatesTsv = new StringBuilder("variant\tnode\tkind\trows\tcost\n");
        LinkedHashMap<String, DelosMeasurementValidityContract.Status> statuses =
                new LinkedHashMap<>();
        for (Map.Entry<String, List<Double>> entry : samples.entrySet()) {
            String name = entry.getKey();
            for (int index = 0; index < entry.getValue().size(); index++) {
                samplesTsv.append(name).append('\t').append(index + 1).append('\t')
                        .append(format(entry.getValue().get(index))).append('\n');
            }
            Distribution distribution = distribution(entry.getValue());
            distributions.put(name, distribution);
            DelosMeasurementValidityContract.DispersionDecision decision =
                    DelosMeasurementValidityContract.classifyCustom(
                            distribution.iqr() / distribution.median(),
                            distribution.mad() / distribution.median());
            double maxToMin = distribution.max() / distribution.min();
            DelosMeasurementValidityContract.Status status = maxToMin > 1.20d
                    ? DelosMeasurementValidityContract.Status.INVALID : decision.status();
            statuses.put(name, status);
            dispersionTsv.append(name).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(decision.iqrToMedian())).append('\t')
                    .append(format(decision.madToMedian())).append('\t')
                    .append(format(maxToMin)).append('\t').append(status).append('\n');

            ExplainCapture analyze = analyzeByVariant.get(name);
            operatorTsv.append(name).append('\t').append(analyze.wallMillis()).append('\t')
                    .append(phase2UPlanShape(analyze.text())).append('\t')
                    .append(phase2ENodeFieldTotal(analyze.text(), "JOIN/JOIN", "nextMillis")).append('\t')
                    .append(phase2EIndexToBaseNextMillis(analyze.text())).append('\t')
                    .append(phase2EIndexScanNextMillis(analyze.text())).append('\t')
                    .append(phase2ENodeFieldTotal(analyze.text(), "SCAN/TABLE_SCAN", "nextMillis"))
                    .append('\t').append(sumPhase2AField(analyze.text(), "openMillis"))
                    .append('\t').append(sumPhase2AField(analyze.text(), "nextMillis"))
                    .append('\t').append(sumPhase2AField(analyze.text(), "opens")).append('\n');
            appendPhase2UEstimates(estimatesTsv, name, analyze.text());
        }
        Files.writeString(reportDirectory.resolve("phase2u-repeated-execution-samples.tsv"),
                samplesTsv.toString(), StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("phase2u-dispersion.tsv"),
                dispersionTsv.toString(), StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("phase2u-operator-summary.tsv"),
                operatorTsv.toString(), StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("phase2u-plan-estimates.tsv"),
                estimatesTsv.toString(), StandardCharsets.UTF_8);
        return statuses;
    }

    static void appendPhase2UEstimates(
            StringBuilder output, String variant, String planText) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*(n\\d+)\\s+(\\S+)[^\\r\\n]*?\\srows=([0-9.Ee+-]+)\\s+cost=([0-9.Ee+-]+)")
                .matcher(planText);
        while (matcher.find()) {
            output.append(variant).append('\t').append(matcher.group(1)).append('\t')
                    .append(matcher.group(2)).append('\t').append(matcher.group(3)).append('\t')
                    .append(matcher.group(4)).append('\n');
        }
    }

    static String phase2USummary(
            boolean physicalScanCost,
            boolean physicalRowLocationCost,
            boolean projectedCurrentRead,
            boolean baseFetchPagePrefetch,
            int warmups,
            int warmupExecutionsPerVisit,
            int measuredRounds,
            int measuredExecutionsPerSample,
            boolean statisticsCatalogStable,
            Map<String, Distribution> distributions,
            DelosMeasurementValidityContract.Status status) {
        double heap1to1 = distributions.get("heap-1to1-natural").median();
        double mvcc1to1 = distributions.get("mvcc-1to1-natural").median();
        double heapFanout = distributions.get("heap-fanout-natural").median();
        double mvccFanout = distributions.get("mvcc-fanout-natural").median();
        return "DelosDB Phase-2U F04 current join decomposition\n"
                + "diagnosticOnly=true\n"
                + "workloads=JOIN_INDEXED_1TO1,JOIN_INDEXED_FANOUT\n"
                + "physicalScanCost=" + physicalScanCost + "\n"
                + "physicalRowLocationCost=" + physicalRowLocationCost + "\n"
                + "projectedCurrentRead=" + projectedCurrentRead + "\n"
                + "baseFetchPagePrefetch=" + baseFetchPagePrefetch + "\n"
                + "warmupsPerVariant=" + warmups + "\n"
                + "warmupExecutionsPerVisit=" + warmupExecutionsPerVisit + "\n"
                + "measuredRoundsPerVariant=" + measuredRounds + "\n"
                + "measuredExecutionsPerSample=" + measuredExecutionsPerSample + "\n"
                + "statisticsCatalogStable=" + statisticsCatalogStable + "\n"
                + phase2URatioSummary("oneToOne", heap1to1, mvcc1to1)
                + phase2URatioSummary("oneToOneBaseRange",
                        distributions.get("heap-1to1-base-range").median(),
                        distributions.get("mvcc-1to1-base-range").median())
                + phase2URatioSummary("oneToOneDimensionScan",
                        distributions.get("heap-1to1-dimension-scan").median(),
                        distributions.get("mvcc-1to1-dimension-scan").median())
                + phase2URatioSummary("fanout", heapFanout, mvccFanout)
                + phase2URatioSummary("fanoutParentRange",
                        distributions.get("heap-fanout-parent-range").median(),
                        distributions.get("mvcc-fanout-parent-range").median())
                + phase2URatioSummary("fanoutChildCovered",
                        distributions.get("heap-fanout-child-covered").median(),
                        distributions.get("mvcc-fanout-child-covered").median())
                + phase2URatioSummary("fanoutChildRowbearing",
                        distributions.get("heap-fanout-child-rowbearing").median(),
                        distributions.get("mvcc-fanout-child-rowbearing").median())
                + "heapOneToOneJoinVsInputSumRatio=" + format(heap1to1 / (
                        distributions.get("heap-1to1-base-range").median()
                                + distributions.get("heap-1to1-dimension-scan").median())) + "\n"
                + "mvccOneToOneJoinVsInputSumRatio=" + format(mvcc1to1 / (
                        distributions.get("mvcc-1to1-base-range").median()
                                + distributions.get("mvcc-1to1-dimension-scan").median())) + "\n"
                + "heapFanoutJoinVsInputSumRatio=" + format(heapFanout / (
                        distributions.get("heap-fanout-parent-range").median()
                                + distributions.get("heap-fanout-child-rowbearing").median())) + "\n"
                + "mvccFanoutJoinVsInputSumRatio=" + format(mvccFanout / (
                        distributions.get("mvcc-fanout-parent-range").median()
                                + distributions.get("mvcc-fanout-child-rowbearing").median())) + "\n"
                + "measurementStatus=" + status + "\n"
                + "classification=EVIDENCE_READY_FOR_F04_CURRENT_PLAN_DECOMPOSITION\n";
    }

    static String phase2URatioSummary(String prefix, double heapMillis, double mvccMillis) {
        return prefix + "HeapMedianMillis=" + format(heapMillis) + "\n"
                + prefix + "MvccMedianMillis=" + format(mvccMillis) + "\n"
                + prefix + "MvccToHeapTimeRatio=" + format(mvccMillis / heapMillis) + "\n"
                + prefix + "Gen2RelativeThroughput=" + format(heapMillis / mvccMillis) + "\n";
    }

    static Path requiredPhase2UPath(String key) {
        String value = System.getProperty(PHASE2U_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2U_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void runPhase2VF04EmbeddedConcurrency() throws Exception {
        Path reportDirectory = requiredPhase2VPath("reportDirectory");
        Path databaseRoot = requiredPhase2VPath("databaseRoot");
        assertPhase2VProductionReadSettings();
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        int rowCount = 10_000;
        int payloadSize = 128;
        int commitBatchSize = 100;
        String jdbcUrl = "jdbc:derby:"
                + databaseRoot.resolve("f04-embedded-concurrency") + ";create=true";
        DelosBenchmarkConfig config =
                new DelosBenchmarkConfig(rowCount, payloadSize, SEED, commitBatchSize);
        Phase2VFixtures fixtures =
                phase2VPrepareFixtures(jdbcUrl, config, rowCount, commitBatchSize);

        String statisticsBefore;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            statisticsBefore =
                    phase2PStatisticsSnapshot(connection, fixtures.statisticsTables());
            connection.rollback();
        }
        Files.writeString(reportDirectory.resolve("phase2v-sysstatistics-before.tsv"),
                statisticsBefore, StandardCharsets.UTF_8);

        LinkedHashMap<String, Phase2UVariant> variants = phase2VNaturalVariants(fixtures);
        LinkedHashMap<String, ExplainCapture> plans =
                phase2VCapturePlans(jdbcUrl, reportDirectory, variants);
        LinkedHashMap<String, Phase2RConcurrencyMeasurement> measurements =
                phase2VMeasureVariants(jdbcUrl, variants);
        phase2VAssertSemantics(measurements);
        phase2VWriteMeasurements(reportDirectory, measurements);

        String statisticsAfter;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            statisticsAfter =
                    phase2PStatisticsSnapshot(connection, fixtures.statisticsTables());
            connection.rollback();
        }
        Files.writeString(reportDirectory.resolve("phase2v-sysstatistics-after.tsv"),
                statisticsAfter, StandardCharsets.UTF_8);
        boolean statisticsCatalogStable = statisticsBefore.equals(statisticsAfter);

        String summary = phase2VSummary(
                statisticsCatalogStable, plans, measurements);
        Files.writeString(
                reportDirectory.resolve("phase2v-f04-embedded-concurrency-summary.txt"),
                summary, StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static void assertPhase2VProductionReadSettings() {
        boolean physicalScanCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalScanCost.enabled", "true"));
        boolean physicalRowLocationCost = Boolean.parseBoolean(System.getProperty(
                "delosdb.experimental.mvccPhysicalRowLocationCost.enabled", "true"));
        boolean projectedCurrentRead = Boolean.getBoolean(
                "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled");
        boolean baseFetchPagePrefetch = Boolean.getBoolean(
                "delosdb.experimental.mvccBaseFetchPagePrefetch");
        if (!physicalScanCost || !physicalRowLocationCost
                || projectedCurrentRead || baseFetchPagePrefetch) {
            throw new IllegalStateException(
                    "Phase-2V requires promoted F04 read settings");
        }
    }

    static Phase2VFixtures phase2VPrepareFixtures(
            String jdbcUrl,
            DelosBenchmarkConfig config,
            int rowCount,
            int commitBatchSize) throws Exception {
        String heapBase;
        String mvccBase;
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            DelosJdbcBenchmarkScenario heap = new DelosJdbcBenchmarkScenario(
                    setup, "phase2v_heap", "", false, config);
            heap.prepare();
            heapBase = heap.tableName();
            prepareJoinDimensionFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareJoinFanoutFixture(setup, heapBase, "", rowCount, commitBatchSize);

            DelosJdbcBenchmarkScenario mvcc = new DelosJdbcBenchmarkScenario(
                    setup, "phase2v_mvcc", " using delos_mvcc", false, config);
            mvcc.prepare();
            mvccBase = mvcc.tableName();
            prepareJoinDimensionFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            prepareJoinFanoutFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);

            List<String> statisticsTables = phase2VStatisticsTables(heapBase, mvccBase);
            for (String table : statisticsTables) {
                phase2BUpdateStatistics(setup, table);
            }
            setup.commit();
            return new Phase2VFixtures(heapBase, mvccBase, statisticsTables);
        }
    }

    static List<String> phase2VStatisticsTables(String heapBase, String mvccBase) {
        return List.of(
                heapBase,
                joinDimensionTableName(heapBase),
                joinFanoutParentTableName(heapBase),
                joinFanoutChildTableName(heapBase),
                mvccBase,
                joinDimensionTableName(mvccBase),
                joinFanoutParentTableName(mvccBase),
                joinFanoutChildTableName(mvccBase));
    }

    static LinkedHashMap<String, Phase2UVariant> phase2VNaturalVariants(
            Phase2VFixtures fixtures) {
        LinkedHashMap<String, Phase2UVariant> variants = new LinkedHashMap<>();
        variants.put("heap-1to1", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_1TO1, fixtures.heapBase()),
                1_000, 1, new int[0]));
        variants.put("mvcc-1to1", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_1TO1, fixtures.mvccBase()),
                1_000, 1, new int[0]));
        variants.put("heap-fanout", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_FANOUT, fixtures.heapBase()),
                1_000, 2, new int[] {1, 100}));
        variants.put("mvcc-fanout", new Phase2UVariant(
                fitnessReadSql(Workload.JOIN_INDEXED_FANOUT, fixtures.mvccBase()),
                1_000, 2, new int[] {1, 100}));
        return variants;
    }

    static LinkedHashMap<String, ExplainCapture> phase2VCapturePlans(
            String jdbcUrl,
            Path reportDirectory,
            Map<String, Phase2UVariant> variants) throws Exception {
        LinkedHashMap<String, ExplainCapture> plans = new LinkedHashMap<>();
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            for (Map.Entry<String, Phase2UVariant> entry : variants.entrySet()) {
                ExplainCapture explain = capturePhase2UExplain(
                        connection, entry.getValue(), false);
                ExplainCapture analyze = capturePhase2UExplain(
                        connection, entry.getValue(), true);
                writePhase2ACapture(
                        reportDirectory, entry.getKey() + "-explain", explain);
                writePhase2ACapture(
                        reportDirectory, entry.getKey() + "-explain-analyze", analyze);
                plans.put(entry.getKey(), analyze);
            }
            connection.rollback();
        }
        return plans;
    }

    static LinkedHashMap<String, Phase2RConcurrencyMeasurement> phase2VMeasureVariants(
            String jdbcUrl,
            Map<String, Phase2UVariant> variants) throws Exception {
        LinkedHashMap<String, Phase2RConcurrencyMeasurement> measurements =
                new LinkedHashMap<>();
        for (Map.Entry<String, Phase2UVariant> entry : variants.entrySet()) {
            for (int clients : List.of(1, 8)) {
                String key = entry.getKey() + "-" + clients + "c";
                measurements.put(
                        key,
                        phase2VMeasureConcurrent(
                                jdbcUrl, entry.getValue(), key, clients));
            }
        }
        return measurements;
    }

    static Phase2RConcurrencyMeasurement phase2VMeasureConcurrent(
            String jdbcUrl,
            Phase2UVariant variant,
            String name,
            int clients) throws Exception {
        int warmupRounds = 3;
        int measuredRounds = 9;
        int executionsPerClient = 16;
        List<Connection> connections = new ArrayList<>();
        List<PreparedStatement> statements = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(clients);
        try {
            for (int client = 0; client < clients; client++) {
                Connection connection = openPhase2AConnection(jdbcUrl);
                connections.add(connection);
                statements.add(connection.prepareStatement(variant.sql()));
            }
            long fingerprint = executePhase2URead(statements.get(0), variant);
            for (int warmup = 0; warmup < warmupRounds; warmup++) {
                phase2VConcurrentRound(
                        executor, statements, variant, fingerprint, executionsPerClient);
            }
            List<Double> throughputs = new ArrayList<>();
            for (int round = 0; round < measuredRounds; round++) {
                throughputs.add(phase2VConcurrentRound(
                        executor, statements, variant, fingerprint, executionsPerClient));
            }
            Distribution distribution = distribution(throughputs);
            DelosMeasurementValidityContract.DispersionDecision decision =
                    DelosMeasurementValidityContract.classifyCustom(
                            distribution.iqr() / distribution.median(),
                            distribution.mad() / distribution.median());
            return new Phase2RConcurrencyMeasurement(
                    fingerprint, throughputs, distribution, decision);
        } finally {
            phase2VCloseConcurrentResources(executor, statements, connections, name);
        }
    }

    static double phase2VConcurrentRound(
            ExecutorService executor,
            List<PreparedStatement> statements,
            Phase2UVariant variant,
            long fingerprint,
            int executionsPerClient) throws Exception {
        int clients = statements.size();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (PreparedStatement statement : statements) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                for (int execution = 0; execution < executionsPerClient; execution++) {
                    long actual = executePhase2URead(statement, variant);
                    if (actual != fingerprint) {
                        throw new IllegalStateException("Phase-2V semantic drift");
                    }
                }
                return fingerprint;
            }));
        }
        if (!ready.await(30L, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Phase-2V clients did not reach start barrier");
        }
        long started = System.nanoTime();
        start.countDown();
        for (Future<Long> future : futures) {
            future.get(120L, TimeUnit.SECONDS);
        }
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0d;
        return (clients * executionsPerClient) / elapsedSeconds;
    }

    static void phase2VCloseConcurrentResources(
            ExecutorService executor,
            List<PreparedStatement> statements,
            List<Connection> connections,
            String name) throws Exception {
        executor.shutdownNow();
        SQLException failure = null;
        for (PreparedStatement statement : statements) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                failure = phase2VAccumulate(failure, closeFailure);
            }
        }
        for (Connection connection : connections) {
            try {
                connection.rollback();
                connection.close();
            } catch (SQLException closeFailure) {
                failure = phase2VAccumulate(failure, closeFailure);
            }
        }
        if (failure != null) {
            throw new SQLException("Phase-2V cleanup failed for " + name, failure);
        }
    }

    static SQLException phase2VAccumulate(
            SQLException current, SQLException additional) {
        if (current == null) {
            return additional;
        }
        current.addSuppressed(additional);
        return current;
    }

    static void phase2VAssertSemantics(
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        phase2VAssertSemanticGroup(measurements, "1to1");
        phase2VAssertSemanticGroup(measurements, "fanout");
    }

    static void phase2VAssertSemanticGroup(
            Map<String, Phase2RConcurrencyMeasurement> measurements, String workload) {
        long expected = measurements.get("heap-" + workload + "-1c").fingerprint();
        for (String provider : List.of("heap", "mvcc")) {
            for (int clients : List.of(1, 8)) {
                String key = provider + "-" + workload + "-" + clients + "c";
                if (measurements.get(key).fingerprint() != expected) {
                    throw new IllegalStateException(
                            "Phase-2V semantic drift for " + key);
                }
            }
        }
    }

    static void phase2VWriteMeasurements(
            Path reportDirectory,
            Map<String, Phase2RConcurrencyMeasurement> measurements) throws IOException {
        StringBuilder samples = new StringBuilder("variant\tround\tthroughputTxPerSec\n");
        StringBuilder dispersion = new StringBuilder(
                "variant\tmedianTxPerSec\tiqrToMedian\tmadToMedian\t"
                        + "maxMinRatio\trunRangeStatus\tstatus\n");
        for (Map.Entry<String, Phase2RConcurrencyMeasurement> entry : measurements.entrySet()) {
            Phase2RConcurrencyMeasurement measurement = entry.getValue();
            for (int index = 0; index < measurement.throughputs().size(); index++) {
                samples.append(entry.getKey()).append('\t').append(index + 1).append('\t')
                        .append(format(measurement.throughputs().get(index))).append('\n');
            }
            Distribution distribution = measurement.distribution();
            double maxMin = distribution.max() / distribution.min();
            dispersion.append(entry.getKey()).append('\t')
                    .append(format(distribution.median())).append('\t')
                    .append(format(distribution.iqr() / distribution.median())).append('\t')
                    .append(format(distribution.mad() / distribution.median())).append('\t')
                    .append(format(maxMin)).append('\t')
                    .append(maxMin <= 1.20d ? "VALID" : "INVALID").append('\t')
                    .append(measurement.decision().status()).append('\n');
        }
        Files.writeString(reportDirectory.resolve("phase2v-repeated-throughput-samples.tsv"),
                samples.toString(), StandardCharsets.UTF_8);
        Files.writeString(reportDirectory.resolve("phase2v-dispersion.tsv"),
                dispersion.toString(), StandardCharsets.UTF_8);
    }

    static String phase2VSummary(
            boolean statisticsCatalogStable,
            Map<String, ExplainCapture> plans,
            Map<String, Phase2RConcurrencyMeasurement> measurements) {
        double heapOne1 = phase2RMedian(measurements, "heap-1to1-1c");
        double heapOne8 = phase2RMedian(measurements, "heap-1to1-8c");
        double mvccOne1 = phase2RMedian(measurements, "mvcc-1to1-1c");
        double mvccOne8 = phase2RMedian(measurements, "mvcc-1to1-8c");
        double heapFan1 = phase2RMedian(measurements, "heap-fanout-1c");
        double heapFan8 = phase2RMedian(measurements, "heap-fanout-8c");
        double mvccFan1 = phase2RMedian(measurements, "mvcc-fanout-1c");
        double mvccFan8 = phase2RMedian(measurements, "mvcc-fanout-8c");
        String measurementStatus = phase2RStatus(measurements).toString();
        String runRangeStatus = phase2RRunRangeStatus(measurements);
        String classification = phase2VClassification(
                statisticsCatalogStable, measurementStatus, runRangeStatus,
                heapOne1, heapOne8, mvccOne1, mvccOne8,
                heapFan1, heapFan8, mvccFan1, mvccFan8);
        return "DelosDB Phase-2V F04 embedded concurrency decomposition\n"
                + "diagnosticOnly=true\n"
                + "workloads=JOIN_INDEXED_1TO1,JOIN_INDEXED_FANOUT\n"
                + "clients=1,8\n"
                + "physicalScanCost=true\n"
                + "physicalRowLocationCost=true\n"
                + "projectedCurrentRead=false\n"
                + "baseFetchPagePrefetch=false\n"
                + "statisticsCatalogStable=" + statisticsCatalogStable + "\n"
                + phase2VPlanSummary(plans)
                + phase2VWorkloadSummary(
                        "oneToOne", heapOne1, heapOne8, mvccOne1, mvccOne8)
                + phase2VWorkloadSummary(
                        "fanout", heapFan1, heapFan8, mvccFan1, mvccFan8)
                + "semanticFingerprintsMatch=true\n"
                + "measurementStatus=" + measurementStatus + "\n"
                + "runRangeStatus=" + runRangeStatus + "\n"
                + "classification=" + classification + "\n";
    }

    static String phase2VPlanSummary(Map<String, ExplainCapture> plans) {
        return "heapOneToOnePlanShape="
                + phase2UPlanShape(plans.get("heap-1to1").text()) + "\n"
                + "mvccOneToOnePlanShape="
                + phase2UPlanShape(plans.get("mvcc-1to1").text()) + "\n"
                + "heapFanoutPlanShape="
                + phase2UPlanShape(plans.get("heap-fanout").text()) + "\n"
                + "mvccFanoutPlanShape="
                + phase2UPlanShape(plans.get("mvcc-fanout").text()) + "\n";
    }

    static String phase2VWorkloadSummary(
            String prefix,
            double heap1,
            double heap8,
            double mvcc1,
            double mvcc8) {
        return prefix + "Heap1cTxPerSec=" + format(heap1) + "\n"
                + prefix + "Heap8cTxPerSec=" + format(heap8) + "\n"
                + prefix + "Mvcc1cTxPerSec=" + format(mvcc1) + "\n"
                + prefix + "Mvcc8cTxPerSec=" + format(mvcc8) + "\n"
                + prefix + "HeapScaling=" + format(heap8 / heap1) + "\n"
                + prefix + "MvccScaling=" + format(mvcc8 / mvcc1) + "\n"
                + prefix + "MvccVsHeap1c=" + format(mvcc1 / heap1) + "\n"
                + prefix + "MvccVsHeap8c=" + format(mvcc8 / heap8) + "\n";
    }

    static String phase2VClassification(
            boolean statisticsCatalogStable,
            String measurementStatus,
            String runRangeStatus,
            double heapOne1,
            double heapOne8,
            double mvccOne1,
            double mvccOne8,
            double heapFan1,
            double heapFan8,
            double mvccFan1,
            double mvccFan8) {
        if (!statisticsCatalogStable) {
            return "INVALID_STATISTICS";
        }
        if ("INVALID".equals(measurementStatus) || "INVALID".equals(runRangeStatus)) {
            return "INVALID_MEASUREMENT";
        }
        boolean oneConcurrency = phase2VConcurrencyLoss(
                heapOne1, heapOne8, mvccOne1, mvccOne8);
        boolean fanConcurrency = phase2VConcurrencyLoss(
                heapFan1, heapFan8, mvccFan1, mvccFan8);
        if (oneConcurrency && fanConcurrency) {
            return "MVCC_F04_CONCURRENCY_PRIMARY";
        }
        if (oneConcurrency) {
            return "MVCC_1TO1_CONCURRENCY_PRIMARY";
        }
        if (fanConcurrency) {
            return "MVCC_FANOUT_CONCURRENCY_PRIMARY";
        }
        return "CONCURRENCY_NOT_PRIMARY";
    }

    static boolean phase2VConcurrencyLoss(
            double heap1,
            double heap8,
            double mvcc1,
            double mvcc8) {
        double heapScaling = heap8 / heap1;
        double mvccScaling = mvcc8 / mvcc1;
        double ratio1 = mvcc1 / heap1;
        double ratio8 = mvcc8 / heap8;
        return mvccScaling < heapScaling * 0.85d && ratio8 < ratio1 * 0.85d;
    }

    static Path requiredPhase2VPath(String key) {
        String value = System.getProperty(PHASE2V_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2V_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static Path requiredPhase2JPath(String key) {
        String value = System.getProperty(PHASE2J_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2J_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static Path requiredPhase2DPath(String key) {
        String value = System.getProperty(PHASE2D_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2D_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static String phase2DForcedNestedLoopSql(String base) {
        String customer = multiJoinCustomerTableName(base);
        String order = multiJoinOrderTableName(base);
        String line = multiJoinLineTableName(base);
        return "select c.id, o.id, l.id from --DERBY-PROPERTIES joinOrder=FIXED\n"
                + customer + " c\n"
                + "join " + order + " o --DERBY-PROPERTIES joinStrategy=NESTEDLOOP\n"
                + "on o.customer_id = c.id\n"
                + "join " + line + " l --DERBY-PROPERTIES joinStrategy=NESTEDLOOP\n"
                + "on l.order_id = o.id\n"
                + "where c.id between ? and ?";
    }

    static String normalizePhase2DPlanShape(
            String planText, String baseName, String storageMode, String primaryKeyIndex) {
        return normalizePhase2APlanShape(planText, baseName, storageMode)
                .replace(primaryKeyIndex, "<PK_INDEX>")
                .replace(baseName + "_MJ_ORDER_C_IDX", "P2D_MJ_ORDER_C_IDX")
                .replace(baseName + "_MJ_LINE_O_IDX", "P2D_MJ_LINE_O_IDX");
    }

    static void runPhase2AF05JoinDecomposition() throws Exception {
        Path reportDirectory = requiredPhase2APath("reportDirectory");
        Path databaseRoot = requiredPhase2APath("databaseRoot");
        deleteRecursively(reportDirectory);
        deleteRecursively(databaseRoot);
        Files.createDirectories(reportDirectory);
        Files.createDirectories(databaseRoot);

        Path database = databaseRoot.resolve("f05-join-3way-selective");
        String heapBase = "P2A_HEAP";
        String mvccBase = "P2A_MVCC";
        int rowCount = 10_000;
        int commitBatchSize = 100;
        int expectedRows = expectedFitnessRows(Workload.JOIN_3WAY_SELECTIVE, rowCount);
        if (expectedRows != 1_200) {
            throw new IllegalStateException(
                    "Phase-2A F05 fixture cardinality drift: expected 1200, actual=" + expectedRows);
        }

        String jdbcUrl = "jdbc:derby:" + database.toAbsolutePath() + ";create=true";
        try (Connection setup = openPhase2AConnection(jdbcUrl)) {
            prepareMultiJoinFixture(setup, heapBase, "", rowCount, commitBatchSize);
            prepareMultiJoinFixture(
                    setup, mvccBase, " using delos_mvcc", rowCount, commitBatchSize);
            setup.commit();
        }

        String heapSql = fitnessReadSql(Workload.JOIN_3WAY_SELECTIVE, heapBase);
        String mvccSql = fitnessReadSql(Workload.JOIN_3WAY_SELECTIVE, mvccBase);
        ExplainCapture heapExplain;
        ExplainCapture mvccExplain;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapExplain = capturePhase2AExplain(connection, heapSql, false);
            mvccExplain = capturePhase2AExplain(connection, mvccSql, false);
            connection.rollback();
        }

        ExplainCapture heapAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapAnalyze = capturePhase2AExplain(connection, heapSql, true);
            connection.rollback();
        }
        ExplainCapture mvccAnalyze;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            mvccAnalyze = capturePhase2AExplain(connection, mvccSql, true);
            connection.rollback();
        }

        long heapFingerprint;
        long mvccFingerprint;
        try (Connection connection = openPhase2AConnection(jdbcUrl)) {
            heapFingerprint = executePhase2AQuery(connection, heapSql, expectedRows);
            mvccFingerprint = executePhase2AQuery(connection, mvccSql, expectedRows);
            connection.rollback();
        }
        if (heapFingerprint != mvccFingerprint) {
            throw new IllegalStateException(
                    "Phase-2A SQL semantic drift: heap=" + heapFingerprint
                            + ", mvcc=" + mvccFingerprint);
        }

        writePhase2ACapture(reportDirectory, "heap-explain", heapExplain);
        writePhase2ACapture(reportDirectory, "mvcc-explain", mvccExplain);
        writePhase2ACapture(reportDirectory, "heap-explain-analyze", heapAnalyze);
        writePhase2ACapture(reportDirectory, "mvcc-explain-analyze", mvccAnalyze);

        String heapShape = normalizePhase2APlanShape(heapExplain.text(), heapBase, "heap");
        String mvccShape = normalizePhase2APlanShape(mvccExplain.text(), mvccBase, "delos_mvcc");
        Files.writeString(
                reportDirectory.resolve("heap-plan-shape-normalized.txt"),
                heapShape,
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("mvcc-plan-shape-normalized.txt"),
                mvccShape,
                StandardCharsets.UTF_8);

        boolean samePhysicalShape = heapShape.equals(mvccShape);
        String[] metricNames = {
            "PAGES_VISITED",
            "ROWS_VISITED",
            "ROWS_QUALIFIED",
            "MVCC_ORDERED_CANDIDATES",
            "MVCC_COVERING_CANDIDATES",
            "MVCC_COVERED_CANDIDATES",
            "MVCC_FALLBACK_CANDIDATES",
            "MVCC_DIRECTORY_PAGE_ACQUISITIONS",
            "MVCC_DIRECTORY_PAGE_BATCH_CANDIDATES",
            "MVCC_DIRECTORY_PAGE_REUSE_HITS",
            "MVCC_DIRECTORY_LOGICAL_FALLBACKS",
            "MVCC_DIRECTORY_HEAD_SUMMARY_CHECKS",
            "MVCC_DIRECTORY_HEAD_SUMMARY_HITS",
            "MVCC_DIRECTORY_HEAD_SUMMARY_FALLBACKS",
            "MVCC_VERSION_PAGE_ACQUISITIONS",
            "MVCC_VERSION_SLOT_FETCHES",
            "MVCC_VISIBILITY_CHECKS",
            "MVCC_VERSION_CHAIN_STEPS",
            "MVCC_VERSION_LOGICAL_FALLBACKS"
        };
        StringBuilder metrics = new StringBuilder("metric\theap\tmvcc\n");
        for (String metricName : metricNames) {
            metrics.append(metricName).append('\t')
                    .append(sumPhase2AMetric(heapAnalyze.text(), metricName)).append('\t')
                    .append(sumPhase2AMetric(mvccAnalyze.text(), metricName)).append('\n');
        }
        Files.writeString(
                reportDirectory.resolve("phase2a-f05-storage-metrics.tsv"),
                metrics.toString(),
                StandardCharsets.UTF_8);

        long heapOpenMillis = sumPhase2AField(heapAnalyze.text(), "openMillis");
        long heapNextMillis = sumPhase2AField(heapAnalyze.text(), "nextMillis");
        long mvccOpenMillis = sumPhase2AField(mvccAnalyze.text(), "openMillis");
        long mvccNextMillis = sumPhase2AField(mvccAnalyze.text(), "nextMillis");
        long heapOpens = sumPhase2AField(heapAnalyze.text(), "opens");
        long mvccOpens = sumPhase2AField(mvccAnalyze.text(), "opens");
        long mvccOrderedCandidates = sumPhase2AMetric(
                mvccAnalyze.text(), "MVCC_ORDERED_CANDIDATES");
        long mvccFallbackCandidates = sumPhase2AMetric(
                mvccAnalyze.text(), "MVCC_FALLBACK_CANDIDATES");
        long mvccDirectoryAcquisitions = sumPhase2AMetric(
                mvccAnalyze.text(), "MVCC_DIRECTORY_PAGE_ACQUISITIONS");
        long mvccVersionPageAcquisitions = sumPhase2AMetric(
                mvccAnalyze.text(), "MVCC_VERSION_PAGE_ACQUISITIONS");
        long mvccVersionSlotFetches = sumPhase2AMetric(
                mvccAnalyze.text(), "MVCC_VERSION_SLOT_FETCHES");

        String classification;
        if (!samePhysicalShape) {
            classification = "PLAN_SHAPE_DIFFERENCE_REQUIRES_OPTIMIZER_COSTING_DECOMPOSITION";
        } else if (mvccOrderedCandidates > 0
                && mvccOpenMillis > Math.max(1L, heapOpenMillis) * 10L) {
            classification = "SAME_PLAN_MVCC_INDEX_OPEN_MATERIALIZATION_DOMINATES";
        } else if (mvccNextMillis > Math.max(1L, heapNextMillis) * 10L) {
            classification = "SAME_PLAN_MVCC_ROW_CONSUMPTION_DOMINATES";
        } else {
            classification = "MIXED_OR_UNRESOLVED_REQUIRES_JFR_SOURCE_CORRELATION";
        }

        String summary = "DelosDB Phase-2A F05 three-way selective join decomposition\n"
                + "rows=" + rowCount + "\n"
                + "expectedResultRows=" + expectedRows + "\n"
                + "semanticFingerprint=" + heapFingerprint + "\n"
                + "samePhysicalPlanShape=" + samePhysicalShape + "\n"
                + "heapExplainAnalyzeWallMillis=" + heapAnalyze.wallMillis() + "\n"
                + "mvccExplainAnalyzeWallMillis=" + mvccAnalyze.wallMillis() + "\n"
                + "heapTotalNodeOpens=" + heapOpens + "\n"
                + "mvccTotalNodeOpens=" + mvccOpens + "\n"
                + "heapTotalOpenMillis=" + heapOpenMillis + "\n"
                + "mvccTotalOpenMillis=" + mvccOpenMillis + "\n"
                + "heapTotalNextMillis=" + heapNextMillis + "\n"
                + "mvccTotalNextMillis=" + mvccNextMillis + "\n"
                + "mvccOrderedCandidates=" + mvccOrderedCandidates + "\n"
                + "mvccFallbackCandidates=" + mvccFallbackCandidates + "\n"
                + "mvccDirectoryPageAcquisitions=" + mvccDirectoryAcquisitions + "\n"
                + "mvccVersionPageAcquisitions=" + mvccVersionPageAcquisitions + "\n"
                + "mvccVersionSlotFetches=" + mvccVersionSlotFetches + "\n"
                + "classification=" + classification + "\n";
        Files.writeString(
                reportDirectory.resolve("phase2a-f05-join-decomposition-summary.txt"),
                summary,
                StandardCharsets.UTF_8);
        Files.writeString(
                reportDirectory.resolve("query-shapes.txt"),
                "Heap SQL:\n" + heapSql + "\n\nMVCC SQL:\n" + mvccSql + "\n",
                StandardCharsets.UTF_8);
        System.out.print(summary);
    }

    static Path requiredPhase2APath(String key) {
        String value = System.getProperty(PHASE2A_PREFIX + key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing -D" + PHASE2A_PREFIX + key);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static long executePhase2AQuery(
            Connection connection, String sql, int expectedRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFitnessRead(statement, Workload.JOIN_3WAY_SELECTIVE);
            return executeFitnessRead(statement, Workload.JOIN_3WAY_SELECTIVE, expectedRows);
        }
    }

    static ExplainCapture capturePhase2AExplain(
            Connection connection, String sql, boolean analyze) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(
                (analyze ? "explain analyze " : "explain ") + sql)) {
            bindFitnessRead(statement, Workload.JOIN_3WAY_SELECTIVE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("EXPLAIN returned no row");
                }
                String text = resultSet.getString(1);
                String json = resultSet.getString(2);
                if (resultSet.next()) {
                    throw new SQLException("EXPLAIN returned more than one row");
                }
                return new ExplainCapture(
                        text,
                        json,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }

    static String normalizePhase2APlanShape(
            String planText, String baseName, String storageMode) {
        int execution = planText.indexOf("EXECUTION schemaVersion=");
        String planOnly = execution >= 0 ? planText.substring(0, execution) : planText;
        return planOnly
                .replace(baseName, "P2A")
                .replace(baseName.toUpperCase(Locale.ROOT), "P2A")
                .replace("storage=" + storageMode, "storage=<STORAGE>")
                .replaceAll("statementId=[^ ]+", "statementId=<ID>")
                .replaceAll(" rows=[^ ]+", " rows=<ESTIMATED_ROWS>")
                .replaceAll(" cost=[^ ]+", " cost=<ESTIMATED_COST>")
                .replaceAll(" reason=[^ ]+", " reason=<DECISION_REASON>");
    }

    static long sumPhase2AMetric(String text, String metricName) {
        Pattern pattern = Pattern.compile(Pattern.quote(metricName) + "=(\\d+)");
        Matcher matcher = pattern.matcher(text);
        long total = 0L;
        while (matcher.find()) {
            total = Math.addExact(total, Long.parseLong(matcher.group(1)));
        }
        return total;
    }
}
