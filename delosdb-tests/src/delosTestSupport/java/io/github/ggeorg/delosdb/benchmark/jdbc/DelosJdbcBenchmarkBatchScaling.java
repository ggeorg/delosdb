/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.io.IOException;
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
 * Provider-neutral execution-batch scaling benchmark.
 *
 * <p>Each measured interval executes one reused prepared read operation
 * {@code batchSize} times inside one transaction. Rollback is deliberately
 * outside the measured interval so this benchmark isolates statement
 * execution throughput. Transaction-end costs are measured by the separate
 * phase-isolated baseline.</p>
 */
public final class DelosJdbcBenchmarkBatchScaling {
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcBenchmarkBatchScaling() {
    }

    public static List<DelosBenchmarkBatchMeasurement> run(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<Integer> batchSizes,
            List<DelosBenchmarkOperation> operations,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) throws Exception {
        Options options = new Options(
                databaseRoot,
                reportDirectory,
                List.copyOf(rowCounts),
                List.copyOf(batchSizes),
                List.copyOf(operations),
                payloadSize,
                fixtureCommitBatchSize,
                warmups,
                iterations,
                runs);
        options.validate();
        DelosBenchmarkSupport.prepareOutput(options.databaseRoot(), options.reportDirectory());

        List<DelosBenchmarkBatchMeasurement> measurements = new ArrayList<>();
        Map<SemanticKey, BatchSemantic> expectedSemantics = new HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : options.rowCounts()) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows,
                        options.payloadSize(),
                        SEED,
                        Math.min(options.fixtureCommitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : DelosBenchmarkProvider.values()) {
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
                .comparingInt(DelosBenchmarkBatchMeasurement::rowCount)
                .thenComparing(DelosBenchmarkBatchMeasurement::provider)
                .thenComparing(DelosBenchmarkBatchMeasurement::operation)
                .thenComparingInt(DelosBenchmarkBatchMeasurement::batchSize)
                .thenComparingInt(DelosBenchmarkBatchMeasurement::run));
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static List<DelosBenchmarkBatchMeasurement> runProvider(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, BatchSemantic> expectedSemantics) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id()
                + "-" + config.rowCount() + "-run" + run);
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            List<DelosBenchmarkBatchMeasurement> result = new ArrayList<>();
            DelosJdbcBenchmarkScenario scenario =
                    new DelosJdbcBenchmarkScenario(connection, provider, config);
            scenario.prepare();
            for (DelosBenchmarkOperation operation : options.operations()) {
                result.addAll(measureOperation(
                        options,
                        config,
                        provider,
                        run,
                        expectedSemantics,
                        connection,
                        scenario,
                        operation));
            }
            return List.copyOf(result);
        });
    }

    private static List<DelosBenchmarkBatchMeasurement> measureOperation(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, BatchSemantic> expectedSemantics,
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation) throws SQLException {
        List<DelosBenchmarkBatchMeasurement> result = new ArrayList<>();
        try (DelosJdbcBenchmarkScenario.PreparedOperation reusable = scenario.prepareOperation(operation)) {
            DelosBenchmarkResult expected = reusable.execute();
            connection.rollback();

            for (int batchSize : batchSizesForRun(options.batchSizes(), run)) {
                long expectedFingerprint = repeatedFingerprint(expected, batchSize);
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    BatchRun warmupRun = runBatchAndRollback(
                            connection,
                            reusable,
                            batchSize,
                            expectedFingerprint);
                    requireBatchSemantic(
                            expected,
                            warmupRun,
                            provider,
                            operation,
                            batchSize,
                            run,
                            "warmup");
                }

                long elapsedNanos = 0;
                long batchFingerprint = expectedFingerprint;
                for (int iteration = 0; iteration < options.iterations(); iteration++) {
                    BatchRun measured = runBatchAndRollback(
                            connection,
                            reusable,
                            batchSize,
                            expectedFingerprint);
                    requireBatchSemantic(
                            expected,
                            measured,
                            provider,
                            operation,
                            batchSize,
                            run,
                            "measured iteration " + iteration);
                    elapsedNanos = Math.addExact(elapsedNanos, measured.elapsedNanos());
                    batchFingerprint = measured.fingerprint();
                }

                long measuredOperations = Math.multiplyExact((long) batchSize, options.iterations());
                BatchSemantic semantic = new BatchSemantic(
                        expected.rowCount(),
                        expected.checksum(),
                        batchFingerprint);
                SemanticKey key = new SemanticKey(operation, config.rowCount(), batchSize);
                BatchSemantic prior = expectedSemantics.putIfAbsent(key, semantic);
                if (prior != null && !prior.equals(semantic)) {
                    throw new IllegalStateException("Non-reproducible batch semantics for " + key
                            + ": expected=" + prior + ", actual=" + semantic
                            + ", provider=" + provider + ", run=" + run);
                }

                result.add(new DelosBenchmarkBatchMeasurement(
                        provider,
                        operation,
                        DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS,
                        operation.transactionKind(),
                        batchSize,
                        config.rowCount(),
                        config.payloadSize(),
                        config.commitBatchSize(),
                        options.warmups(),
                        options.iterations(),
                        measuredOperations,
                        elapsedNanos,
                        measuredOperations * 1_000_000_000.0 / elapsedNanos,
                        (double) elapsedNanos / measuredOperations,
                        expected.rowCount(),
                        expected.checksum(),
                        batchFingerprint,
                        run));
            }
        }
        return List.copyOf(result);
    }

    private static BatchRun runBatchAndRollback(
            Connection connection,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable,
            int batchSize,
            long expectedFingerprint) throws SQLException {
        try {
            long fingerprint = 1;
            DelosBenchmarkResult last = null;
            long started = System.nanoTime();
            for (int index = 0; index < batchSize; index++) {
                last = reusable.execute();
                fingerprint = mix(fingerprint, last);
            }
            long elapsedNanos = System.nanoTime() - started;
            if (fingerprint != expectedFingerprint) {
                throw new IllegalStateException("Batch semantic fingerprint mismatch: expected="
                        + expectedFingerprint + ", actual=" + fingerprint + ", batchSize=" + batchSize);
            }
            connection.rollback();
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
            int batchSize,
            int run,
            String stage) {
        if (actual.lastResult() == null || !expected.equals(actual.lastResult())) {
            throw new IllegalStateException("Batch semantic drift for " + provider + ' ' + operation
                    + " batchSize=" + batchSize + " run=" + run + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual.lastResult());
        }
    }

    private static long repeatedFingerprint(DelosBenchmarkResult result, int repetitions) {
        long fingerprint = 1;
        for (int index = 0; index < repetitions; index++) {
            fingerprint = mix(fingerprint, result);
        }
        return fingerprint;
    }

    private static long mix(long fingerprint, DelosBenchmarkResult result) {
        long mixed = 31 * fingerprint + result.rowCount();
        return 31 * mixed + result.checksum();
    }

    private static List<Integer> batchSizesForRun(List<Integer> batchSizes, int run) {
        List<Integer> ordered = new ArrayList<>(batchSizes);
        if ((run & 1) == 0) {
            Collections.reverse(ordered);
        }
        return List.copyOf(ordered);
    }

    private static void writeReports(
            Options options,
            List<DelosBenchmarkBatchMeasurement> measurements) throws IOException {
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("batch-scaling-results.csv"),
                csv(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("batch-scaling-results.json"),
                json(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("batch-scaling-summary.txt"),
                summary(options, measurements));
    }

    private static String csv(List<DelosBenchmarkBatchMeasurement> values) {
        StringBuilder out = new StringBuilder(
                "provider,operation,statementMode,transactionKind,batchSize,rowCount,payloadSize,"
                        + "fixtureCommitBatchSize,warmups,iterations,measuredOperations,elapsedNanos,"
                        + "throughputPerSecond,averageLatencyNanos,semanticRowCount,semanticChecksum,"
                        + "batchFingerprint,run\n");
        for (DelosBenchmarkBatchMeasurement value : values) {
            out.append(value.provider().id()).append(',')
                    .append(value.operation()).append(',')
                    .append(value.statementMode()).append(',')
                    .append(value.transactionKind()).append(',')
                    .append(value.batchSize()).append(',')
                    .append(value.rowCount()).append(',')
                    .append(value.payloadSize()).append(',')
                    .append(value.fixtureCommitBatchSize()).append(',')
                    .append(value.warmups()).append(',')
                    .append(value.iterations()).append(',')
                    .append(value.measuredOperations()).append(',')
                    .append(value.elapsedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.throughputPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos())).append(',')
                    .append(value.semanticRowCount()).append(',')
                    .append(value.semanticChecksum()).append(',')
                    .append(value.batchFingerprint()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosBenchmarkBatchMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            DelosBenchmarkBatchMeasurement value = values.get(index);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"operation\":\"").append(value.operation())
                    .append("\",\"statementMode\":\"").append(value.statementMode())
                    .append("\",\"transactionKind\":\"").append(value.transactionKind())
                    .append("\",\"batchSize\":").append(value.batchSize())
                    .append(",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"fixtureCommitBatchSize\":").append(value.fixtureCommitBatchSize())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"measuredOperations\":").append(value.measuredOperations())
                    .append(",\"elapsedNanos\":").append(value.elapsedNanos())
                    .append(",\"throughputPerSecond\":")
                    .append(String.format(Locale.ROOT, "%.6f", value.throughputPerSecond()))
                    .append(",\"averageLatencyNanos\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos()))
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
            List<DelosBenchmarkBatchMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC execution-batch scaling baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Rows: ").append(options.rowCounts()).append('\n')
                .append("Payload: ").append(options.payloadSize()).append('\n')
                .append("Fixture commit batch: ").append(options.fixtureCommitBatchSize()).append('\n')
                .append("Operations: ").append(options.operations()).append('\n')
                .append("Execution batch sizes: ").append(options.batchSizes()).append('\n')
                .append("Statement mode: ")
                .append(DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS).append('\n')
                .append("Transaction scope: one read transaction per timing interval\n")
                .append("Rollback outside timing interval: true\n")
                .append("Batch-size order alternates by run: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (DelosBenchmarkBatchMeasurement value : values) {
            out.append(String.format(Locale.ROOT,
                    "%7d %-4s %-28s batch=%-6d samples=%-8d run=%d rate=%12.3f avg-ns=%12.3f "
                            + "rows=%d checksum=%d fingerprint=%d%n",
                    value.rowCount(),
                    value.provider().id(),
                    value.operation(),
                    value.batchSize(),
                    value.measuredOperations(),
                    value.run(),
                    value.throughputPerSecond(),
                    value.averageLatencyNanos(),
                    value.semanticRowCount(),
                    value.semanticChecksum(),
                    value.batchFingerprint()));
        }
        return out.toString();
    }

    private record SemanticKey(
            DelosBenchmarkOperation operation,
            int rowCount,
            int batchSize) {
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
            List<Integer> batchSizes,
            List<DelosBenchmarkOperation> operations,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) {
        void validate() {
            if (databaseRoot == null || reportDirectory == null) {
                throw new IllegalArgumentException("Database and report roots are required");
            }
            if (rowCounts == null || rowCounts.isEmpty()
                    || rowCounts.stream().anyMatch(value -> value == null || value <= 0)) {
                throw new IllegalArgumentException("Positive row counts are required");
            }
            if (batchSizes == null || batchSizes.isEmpty()
                    || batchSizes.stream().anyMatch(value -> value == null || value <= 0)) {
                throw new IllegalArgumentException("Positive execution batch sizes are required");
            }
            if (operations == null || operations.isEmpty()) {
                throw new IllegalArgumentException("At least one read operation is required");
            }
            for (DelosBenchmarkOperation operation : operations) {
                if (operation == null || operation.transactionKind() != DelosBenchmarkTransactionKind.READ) {
                    throw new IllegalArgumentException(
                            "Execution-batch scaling currently accepts read operations only: " + operation);
                }
            }
            if (payloadSize <= 0 || fixtureCommitBatchSize <= 0
                    || warmups < 0 || iterations <= 0 || runs <= 0) {
                throw new IllegalArgumentException(
                        "Benchmark dimensions must be positive and warmups must not be negative");
            }
        }
    }
}
