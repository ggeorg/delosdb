/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider-neutral JDBC row-count scaling benchmark.
 *
 * <p>The benchmark uses a configured row budget to reduce the number of
 * operations as fixture size grows. This keeps each timing interval useful
 * without multiplying an already row-proportional implementation cost into
 * multi-hour runs. Prepared read statements are reused, and rollback occurs
 * outside the measured interval.</p>
 */
public final class DelosJdbcBenchmarkRowScaling {
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcBenchmarkRowScaling() {
    }

    public static List<DelosBenchmarkRowScalingMeasurement> run(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<DelosBenchmarkOperation> operations,
            int payloadSize,
            int fixtureCommitBatchSize,
            long targetRowsPerInterval,
            int maxOperationsPerInterval,
            int warmups,
            int iterations,
            int runs) throws Exception {
        Options options = new Options(
                databaseRoot,
                reportDirectory,
                List.copyOf(rowCounts),
                List.copyOf(operations),
                payloadSize,
                fixtureCommitBatchSize,
                targetRowsPerInterval,
                maxOperationsPerInterval,
                warmups,
                iterations,
                runs);
        options.validate();
        DelosBenchmarkSupport.prepareOutput(options.databaseRoot(), options.reportDirectory());

        List<DelosBenchmarkRowScalingMeasurement> measurements = new ArrayList<>();
        Map<SemanticKey, BatchSemantic> expectedSemantics = new HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : rowCountsForRun(options.rowCounts(), run)) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows,
                        options.payloadSize(),
                        SEED,
                        Math.min(options.fixtureCommitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : providersForRun(run)) {
                    measurements.addAll(runProvider(
                            options,
                            config,
                            provider,
                            run,
                            expectedSemantics));
                }
            }
        }

        measurements.sort(Comparator
                .comparingInt(DelosBenchmarkRowScalingMeasurement::rowCount)
                .thenComparing(DelosBenchmarkRowScalingMeasurement::provider)
                .thenComparing(DelosBenchmarkRowScalingMeasurement::operation)
                .thenComparingInt(DelosBenchmarkRowScalingMeasurement::run));
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static List<DelosBenchmarkRowScalingMeasurement> runProvider(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, BatchSemantic> expectedSemantics) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id()
                + "-" + config.rowCount() + "-run" + run);
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            List<DelosBenchmarkRowScalingMeasurement> result = new ArrayList<>();
            DelosJdbcBenchmarkScenario scenario =
                    new DelosJdbcBenchmarkScenario(connection, provider, config);
            long fixtureStarted = System.nanoTime();
            scenario.prepare();
            long fixturePrepareNanos = System.nanoTime() - fixtureStarted;
            long databaseBytesAfterFixture = directoryBytes(database);
            int operationsPerInterval = operationsPerInterval(options, config.rowCount());
            for (DelosBenchmarkOperation operation : operationsForRun(options.operations(), run)) {
                result.add(measureOperation(
                        options,
                        config,
                        provider,
                        run,
                        expectedSemantics,
                        connection,
                        scenario,
                        operation,
                        operationsPerInterval,
                        fixturePrepareNanos,
                        databaseBytesAfterFixture));
            }
            return List.copyOf(result);
        });
    }

    private static DelosBenchmarkRowScalingMeasurement measureOperation(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, BatchSemantic> expectedSemantics,
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            int operationsPerInterval,
            long fixturePrepareNanos,
            long databaseBytesAfterFixture) throws SQLException {
        try (DelosJdbcBenchmarkScenario.PreparedOperation reusable = scenario.prepareOperation(operation)) {
            DelosBenchmarkResult expected = reusable.execute();
            connection.rollback();
            long expectedFingerprint = repeatedFingerprint(expected, operationsPerInterval);

            for (int warmup = 0; warmup < options.warmups(); warmup++) {
                BatchRun warmupRun = runBatchAndRollback(
                        connection,
                        reusable,
                        operationsPerInterval,
                        expectedFingerprint);
                requireBatchSemantic(
                        expected,
                        warmupRun,
                        provider,
                        operation,
                        config.rowCount(),
                        run,
                        "warmup");
            }

            long elapsedNanos = 0L;
            long batchFingerprint = expectedFingerprint;
            for (int iteration = 0; iteration < options.iterations(); iteration++) {
                BatchRun measured = runBatchAndRollback(
                        connection,
                        reusable,
                        operationsPerInterval,
                        expectedFingerprint);
                requireBatchSemantic(
                        expected,
                        measured,
                        provider,
                        operation,
                        config.rowCount(),
                        run,
                        "measured iteration " + iteration);
                elapsedNanos = Math.addExact(elapsedNanos, measured.elapsedNanos());
                batchFingerprint = measured.fingerprint();
            }

            long measuredOperations = Math.multiplyExact(
                    (long) operationsPerInterval,
                    options.iterations());
            BatchSemantic semantic = new BatchSemantic(
                    expected.rowCount(),
                    expected.checksum(),
                    batchFingerprint);
            SemanticKey key = new SemanticKey(operation, config.rowCount(), operationsPerInterval);
            BatchSemantic prior = expectedSemantics.putIfAbsent(key, semantic);
            if (prior != null && !prior.equals(semantic)) {
                throw new IllegalStateException("Non-reproducible row-scaling semantics for " + key
                        + ": expected=" + prior + ", actual=" + semantic
                        + ", provider=" + provider + ", run=" + run);
            }

            double averageLatencyNanos = (double) elapsedNanos / measuredOperations;
            return new DelosBenchmarkRowScalingMeasurement(
                    provider,
                    operation,
                    DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS,
                    operation.transactionKind(),
                    config.rowCount(),
                    config.payloadSize(),
                    config.commitBatchSize(),
                    fixturePrepareNanos,
                    databaseBytesAfterFixture,
                    options.targetRowsPerInterval(),
                    options.maxOperationsPerInterval(),
                    operationsPerInterval,
                    options.warmups(),
                    options.iterations(),
                    measuredOperations,
                    elapsedNanos,
                    measuredOperations * 1_000_000_000.0 / elapsedNanos,
                    averageLatencyNanos,
                    averageLatencyNanos / config.rowCount(),
                    expected.rowCount(),
                    expected.checksum(),
                    batchFingerprint,
                    run);
        }
    }

    private static BatchRun runBatchAndRollback(
            Connection connection,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable,
            int operationsPerInterval,
            long expectedFingerprint) throws SQLException {
        try {
            long fingerprint = 1L;
            DelosBenchmarkResult last = null;
            long started = System.nanoTime();
            for (int index = 0; index < operationsPerInterval; index++) {
                last = reusable.execute();
                fingerprint = mixResult(fingerprint, last);
            }
            long elapsedNanos = System.nanoTime() - started;
            connection.rollback();
            if (last == null) {
                throw new IllegalStateException("Row-scaling interval did not execute an operation");
            }
            if (fingerprint != expectedFingerprint) {
                throw new IllegalStateException("Row-scaling fingerprint mismatch: expected="
                        + expectedFingerprint + ", actual=" + fingerprint);
            }
            return new BatchRun(elapsedNanos, fingerprint, last);
        } catch (SQLException | RuntimeException failure) {
            DelosBenchmarkSupport.rollbackAfterFailure(connection, failure);
            throw failure;
        }
    }

    private static void requireBatchSemantic(
            DelosBenchmarkResult expected,
            BatchRun actual,
            DelosBenchmarkProvider provider,
            DelosBenchmarkOperation operation,
            int rowCount,
            int run,
            String stage) {
        if (!expected.equals(actual.lastResult())) {
            throw new IllegalStateException("Row-scaling result mismatch for " + provider + ' '
                    + operation + " rows=" + rowCount + " run=" + run + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual.lastResult());
        }
    }

    private static int operationsPerInterval(Options options, int rowCount) {
        long byBudget = options.targetRowsPerInterval() / rowCount;
        long bounded = Math.max(1L, Math.min(options.maxOperationsPerInterval(), byBudget));
        return Math.toIntExact(bounded);
    }

    private static long repeatedFingerprint(DelosBenchmarkResult result, int repetitions) {
        long fingerprint = 1L;
        for (int index = 0; index < repetitions; index++) {
            fingerprint = mixResult(fingerprint, result);
        }
        return fingerprint;
    }

    private static long mixResult(long fingerprint, DelosBenchmarkResult result) {
        return 31L * (31L * fingerprint + result.rowCount()) + result.checksum();
    }

    private static List<Integer> rowCountsForRun(List<Integer> rowCounts, int run) {
        List<Integer> ordered = new ArrayList<>(rowCounts);
        if ((run & 1) == 0) {
            Collections.reverse(ordered);
        }
        return List.copyOf(ordered);
    }

    private static List<DelosBenchmarkProvider> providersForRun(int run) {
        List<DelosBenchmarkProvider> ordered = new ArrayList<>(List.of(DelosBenchmarkProvider.values()));
        if ((run & 1) == 0) {
            Collections.reverse(ordered);
        }
        return List.copyOf(ordered);
    }

    private static List<DelosBenchmarkOperation> operationsForRun(
            List<DelosBenchmarkOperation> operations,
            int run) {
        List<DelosBenchmarkOperation> ordered = new ArrayList<>(operations);
        if ((run & 1) == 0) {
            Collections.reverse(ordered);
        }
        return List.copyOf(ordered);
    }

    private static long directoryBytes(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            long total = 0L;
            for (Path candidate : paths.filter(Files::isRegularFile).toList()) {
                total = Math.addExact(total, Files.size(candidate));
            }
            return total;
        }
    }

    private static void writeReports(
            Options options,
            List<DelosBenchmarkRowScalingMeasurement> measurements) throws IOException {
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("row-scaling-results.csv"),
                csv(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("row-scaling-results.json"),
                json(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("row-scaling-summary.txt"),
                summary(options, measurements));
    }

    private static String csv(List<DelosBenchmarkRowScalingMeasurement> values) {
        StringBuilder out = new StringBuilder(
                "provider,operation,statementMode,transactionKind,rowCount,payloadSize,"
                        + "fixtureCommitBatchSize,fixturePrepareNanos,databaseBytesAfterFixture,"
                        + "targetRowsPerInterval,maxOperationsPerInterval,operationsPerInterval,"
                        + "warmups,iterations,measuredOperations,elapsedNanos,throughputPerSecond,"
                        + "averageLatencyNanos,averageLatencyPerConfiguredRowNanos,semanticRowCount,"
                        + "semanticChecksum,batchFingerprint,run\n");
        for (DelosBenchmarkRowScalingMeasurement value : values) {
            out.append(value.provider().id()).append(',')
                    .append(value.operation()).append(',')
                    .append(value.statementMode()).append(',')
                    .append(value.transactionKind()).append(',')
                    .append(value.rowCount()).append(',')
                    .append(value.payloadSize()).append(',')
                    .append(value.fixtureCommitBatchSize()).append(',')
                    .append(value.fixturePrepareNanos()).append(',')
                    .append(value.databaseBytesAfterFixture()).append(',')
                    .append(value.targetRowsPerInterval()).append(',')
                    .append(value.maxOperationsPerInterval()).append(',')
                    .append(value.operationsPerInterval()).append(',')
                    .append(value.warmups()).append(',')
                    .append(value.iterations()).append(',')
                    .append(value.measuredOperations()).append(',')
                    .append(value.elapsedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.throughputPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos())).append(',')
                    .append(String.format(Locale.ROOT, "%.6f",
                            value.averageLatencyPerConfiguredRowNanos())).append(',')
                    .append(value.semanticRowCount()).append(',')
                    .append(value.semanticChecksum()).append(',')
                    .append(value.batchFingerprint()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosBenchmarkRowScalingMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            DelosBenchmarkRowScalingMeasurement value = values.get(index);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"operation\":\"").append(value.operation())
                    .append("\",\"statementMode\":\"").append(value.statementMode())
                    .append("\",\"transactionKind\":\"").append(value.transactionKind())
                    .append("\",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"fixtureCommitBatchSize\":").append(value.fixtureCommitBatchSize())
                    .append(",\"fixturePrepareNanos\":").append(value.fixturePrepareNanos())
                    .append(",\"databaseBytesAfterFixture\":").append(value.databaseBytesAfterFixture())
                    .append(",\"targetRowsPerInterval\":").append(value.targetRowsPerInterval())
                    .append(",\"maxOperationsPerInterval\":").append(value.maxOperationsPerInterval())
                    .append(",\"operationsPerInterval\":").append(value.operationsPerInterval())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"measuredOperations\":").append(value.measuredOperations())
                    .append(",\"elapsedNanos\":").append(value.elapsedNanos())
                    .append(",\"throughputPerSecond\":")
                    .append(String.format(Locale.ROOT, "%.6f", value.throughputPerSecond()))
                    .append(",\"averageLatencyNanos\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos()))
                    .append(",\"averageLatencyPerConfiguredRowNanos\":")
                    .append(String.format(Locale.ROOT, "%.6f",
                            value.averageLatencyPerConfiguredRowNanos()))
                    .append(",\"semanticRowCount\":").append(value.semanticRowCount())
                    .append(",\"semanticChecksum\":").append(value.semanticChecksum())
                    .append(",\"batchFingerprint\":").append(value.batchFingerprint())
                    .append(",\"run\":").append(value.run()).append('}');
            if (index + 1 < values.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        return out.append("]\n").toString();
    }

    private static String summary(
            Options options,
            List<DelosBenchmarkRowScalingMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC row-count scaling baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Rows: ").append(options.rowCounts()).append('\n')
                .append("Payload: ").append(options.payloadSize()).append('\n')
                .append("Fixture commit batch: ").append(options.fixtureCommitBatchSize()).append('\n')
                .append("Operations: ").append(options.operations()).append('\n')
                .append("Target configured rows per timing interval: ")
                .append(options.targetRowsPerInterval()).append('\n')
                .append("Maximum operations per timing interval: ")
                .append(options.maxOperationsPerInterval()).append('\n')
                .append("Adaptive operation count: max(1, min(max operations, target rows / row count))\n")
                .append("Statement mode: ")
                .append(DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS).append('\n')
                .append("Transaction scope: one read transaction per timing interval\n")
                .append("Rollback outside timing interval: true\n")
                .append("Fixture preparation outside timing interval: true\n")
                .append("Row, provider, and operation order alternate by run: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (DelosBenchmarkRowScalingMeasurement value : values) {
            out.append(String.format(Locale.ROOT,
                    "%7d %-4s %-28s ops=%-4d samples=%-5d run=%d rate=%11.3f "
                            + "avg-ns=%14.3f ns/config-row=%10.3f fixture-ms=%12.3f db-bytes=%d "
                            + "rows=%d checksum=%d fingerprint=%d%n",
                    value.rowCount(),
                    value.provider().id(),
                    value.operation(),
                    value.operationsPerInterval(),
                    value.measuredOperations(),
                    value.run(),
                    value.throughputPerSecond(),
                    value.averageLatencyNanos(),
                    value.averageLatencyPerConfiguredRowNanos(),
                    value.fixturePrepareNanos() / 1_000_000.0,
                    value.databaseBytesAfterFixture(),
                    value.semanticRowCount(),
                    value.semanticChecksum(),
                    value.batchFingerprint()));
        }
        return out.toString();
    }

    private record SemanticKey(
            DelosBenchmarkOperation operation,
            int rowCount,
            int operationsPerInterval) {
    }

    private record BatchSemantic(
            long rowCount,
            long checksum,
            long fingerprint) {
    }

    private record BatchRun(
            long elapsedNanos,
            long fingerprint,
            DelosBenchmarkResult lastResult) {
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<DelosBenchmarkOperation> operations,
            int payloadSize,
            int fixtureCommitBatchSize,
            long targetRowsPerInterval,
            int maxOperationsPerInterval,
            int warmups,
            int iterations,
            int runs) {
        void validate() {
            if (databaseRoot == null || reportDirectory == null) {
                throw new IllegalArgumentException("Database and report roots are required");
            }
            if (rowCounts == null || rowCounts.isEmpty()
                    || rowCounts.stream().anyMatch(value -> value == null || value < 100)) {
                throw new IllegalArgumentException("Row counts of at least 100 are required");
            }
            if (operations == null || operations.isEmpty()) {
                throw new IllegalArgumentException("At least one read operation is required");
            }
            for (DelosBenchmarkOperation operation : operations) {
                if (operation == null || operation.transactionKind() != DelosBenchmarkTransactionKind.READ) {
                    throw new IllegalArgumentException(
                            "Row-count scaling accepts read operations only: " + operation);
                }
            }
            if (payloadSize <= 0 || fixtureCommitBatchSize <= 0
                    || targetRowsPerInterval <= 0L || maxOperationsPerInterval <= 0
                    || warmups < 0 || iterations <= 0 || runs <= 0) {
                throw new IllegalArgumentException(
                        "Benchmark dimensions must be positive and warmups must not be negative");
            }
        }
    }
}
