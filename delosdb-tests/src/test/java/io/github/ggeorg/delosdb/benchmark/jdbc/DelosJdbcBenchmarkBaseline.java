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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Provider-neutral JDBC baseline measurement engine used by the JUnit benchmark task. */
public final class DelosJdbcBenchmarkBaseline {
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcBenchmarkBaseline() {
    }

    public static List<DelosBenchmarkMeasurement> run(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            int payloadSize,
            int commitBatchSize,
            int readOperationsPerTransaction,
            int warmups,
            int iterations,
            int runs) throws Exception {
        Options options = new Options(
                databaseRoot,
                reportDirectory,
                List.copyOf(rowCounts),
                payloadSize,
                commitBatchSize,
                readOperationsPerTransaction,
                warmups,
                iterations,
                runs);
        options.validate();
        Files.createDirectories(options.reportDirectory());
        deleteRecursively(options.databaseRoot());

        List<DelosBenchmarkMeasurement> measurements = new ArrayList<>();
        Map<SemanticKey, DelosBenchmarkResult> firstRun = new java.util.HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : options.rowCounts()) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows, options.payloadSize(), SEED, Math.min(options.commitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : DelosBenchmarkProvider.values()) {
                    measurements.addAll(runProvider(options, config, provider, run, firstRun));
                }
            }
        }

        measurements.sort(Comparator
                .comparingInt(DelosBenchmarkMeasurement::rowCount)
                .thenComparing(DelosBenchmarkMeasurement::provider)
                .thenComparing(DelosBenchmarkMeasurement::operation)
                .thenComparing(DelosBenchmarkMeasurement::statementMode)
                .thenComparing(DelosBenchmarkMeasurement::phase)
                .thenComparing(DelosBenchmarkMeasurement::sampleScope)
                .thenComparingInt(DelosBenchmarkMeasurement::run));
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static List<DelosBenchmarkMeasurement> runProvider(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, DelosBenchmarkResult> firstRun) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id() + "-" + config.rowCount() + "-run" + run);
        deleteRecursively(database);
        List<DelosBenchmarkMeasurement> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database + ";create=true")) {
            try {
                DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(connection, provider, config);
                scenario.prepare();
                for (DelosBenchmarkOperation operation : DelosBenchmarkOperation.values()) {
                    for (DelosBenchmarkStatementMode statementMode : statementModesForRun(run)) {
                        result.addAll(measureOperation(
                                options,
                                config,
                                provider,
                                run,
                                firstRun,
                                connection,
                                scenario,
                                operation,
                                statementMode));
                    }
                }
            } finally {
                if (!connection.getAutoCommit()) {
                    connection.rollback();
                }
            }
        } finally {
            deleteRecursively(database);
        }
        return result;
    }

    private static List<DelosBenchmarkStatementMode> statementModesForRun(int run) {
        if ((run & 1) == 0) {
            return List.of(
                    DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS,
                    DelosBenchmarkStatementMode.FRESH_PER_OPERATION);
        }
        return List.of(
                DelosBenchmarkStatementMode.FRESH_PER_OPERATION,
                DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS);
    }

    private static List<DelosBenchmarkMeasurement> measureOperation(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, DelosBenchmarkResult> firstRun,
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode) throws SQLException {
        int operationsPerTransaction = operation.transactionKind() == DelosBenchmarkTransactionKind.READ
                ? options.readOperationsPerTransaction()
                : 1;
        if (statementMode.reusesStatement()) {
            try (DelosJdbcBenchmarkScenario.PreparedOperation reusable = scenario.prepareOperation(operation)) {
                return measureOperationCycles(
                        options,
                        config,
                        provider,
                        run,
                        firstRun,
                        connection,
                        scenario,
                        operation,
                        statementMode,
                        operationsPerTransaction,
                        reusable);
            }
        }
        return measureOperationCycles(
                options,
                config,
                provider,
                run,
                firstRun,
                connection,
                scenario,
                operation,
                statementMode,
                operationsPerTransaction,
                null);
    }

    private static List<DelosBenchmarkMeasurement> measureOperationCycles(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            int run,
            Map<SemanticKey, DelosBenchmarkResult> firstRun,
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            int operationsPerTransaction,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable) throws SQLException {
        for (int warmup = 0; warmup < options.warmups(); warmup++) {
            runRollbackCycle(
                    connection, scenario, operation, statementMode, operationsPerTransaction, reusable);
        }
        for (int warmup = 0; warmup < options.warmups(); warmup++) {
            runCommitCycle(
                    connection, scenario, operation, statementMode, operationsPerTransaction, reusable);
        }

        long firstPrepareNanos = 0;
        long firstExecuteNanos = 0;
        long repeatedPrepareNanos = 0;
        long repeatedExecuteNanos = 0;
        long rollbackNanos = 0;
        DelosBenchmarkResult semantic = null;
        for (int iteration = 0; iteration < options.iterations(); iteration++) {
            RollbackCycle cycle = runRollbackCycle(
                    connection, scenario, operation, statementMode, operationsPerTransaction, reusable);
            firstPrepareNanos += cycle.batch().firstPrepareNanos();
            firstExecuteNanos += cycle.batch().firstExecuteNanos();
            repeatedPrepareNanos += cycle.batch().repeatedPrepareNanos();
            repeatedExecuteNanos += cycle.batch().repeatedExecuteNanos();
            rollbackNanos += cycle.rollbackNanos();
            semantic = requireSameSemantic(
                    semantic,
                    cycle.batch().semantic(),
                    provider,
                    operation,
                    statementMode,
                    run,
                    "rollback transaction");
        }

        long commitNanos = 0;
        for (int iteration = 0; iteration < options.iterations(); iteration++) {
            CommitCycle cycle = runCommitCycle(
                    connection, scenario, operation, statementMode, operationsPerTransaction, reusable);
            commitNanos += cycle.commitNanos();
            semantic = requireSameSemantic(
                    semantic,
                    cycle.semantic(),
                    provider,
                    operation,
                    statementMode,
                    run,
                    "commit transaction");
        }

        SemanticKey key = new SemanticKey(provider, operation, config.rowCount());
        DelosBenchmarkResult prior = firstRun.putIfAbsent(key, semantic);
        if (prior != null && !prior.equals(semantic)) {
            throw new IllegalStateException("Non-reproducible benchmark semantics for " + key
                    + " in " + statementMode + ": first=" + prior + ", run" + run + '=' + semantic);
        }

        List<DelosBenchmarkMeasurement> result = new ArrayList<>();
        long transactionSamples = options.iterations();
        if (statementMode.measuresPreparePerOperation()) {
            result.add(measurement(
                    options,
                    config,
                    provider,
                    operation,
                    statementMode,
                    DelosBenchmarkPhase.PREPARE,
                    DelosBenchmarkSampleScope.FIRST_OPERATION,
                    DelosBenchmarkMeasurementUnit.OPERATION,
                    operationsPerTransaction,
                    transactionSamples,
                    firstPrepareNanos,
                    semantic,
                    run));
        }
        result.add(measurement(
                options,
                config,
                provider,
                operation,
                statementMode,
                DelosBenchmarkPhase.EXECUTE,
                DelosBenchmarkSampleScope.FIRST_OPERATION,
                DelosBenchmarkMeasurementUnit.OPERATION,
                operationsPerTransaction,
                transactionSamples,
                firstExecuteNanos,
                semantic,
                run));
        if (operationsPerTransaction > 1) {
            long repeatedSamples = Math.multiplyExact(
                    transactionSamples, operationsPerTransaction - 1L);
            if (statementMode.measuresPreparePerOperation()) {
                result.add(measurement(
                        options,
                        config,
                        provider,
                        operation,
                        statementMode,
                        DelosBenchmarkPhase.PREPARE,
                        DelosBenchmarkSampleScope.REPEATED_OPERATIONS,
                        DelosBenchmarkMeasurementUnit.OPERATION,
                        operationsPerTransaction,
                        repeatedSamples,
                        repeatedPrepareNanos,
                        semantic,
                        run));
            }
            result.add(measurement(
                    options,
                    config,
                    provider,
                    operation,
                    statementMode,
                    DelosBenchmarkPhase.EXECUTE,
                    DelosBenchmarkSampleScope.REPEATED_OPERATIONS,
                    DelosBenchmarkMeasurementUnit.OPERATION,
                    operationsPerTransaction,
                    repeatedSamples,
                    repeatedExecuteNanos,
                    semantic,
                    run));
        }
        result.add(measurement(
                options,
                config,
                provider,
                operation,
                statementMode,
                DelosBenchmarkPhase.COMMIT,
                DelosBenchmarkSampleScope.TRANSACTION_END,
                DelosBenchmarkMeasurementUnit.TRANSACTION,
                operationsPerTransaction,
                transactionSamples,
                commitNanos,
                semantic,
                run));
        result.add(measurement(
                options,
                config,
                provider,
                operation,
                statementMode,
                DelosBenchmarkPhase.ROLLBACK,
                DelosBenchmarkSampleScope.TRANSACTION_END,
                DelosBenchmarkMeasurementUnit.TRANSACTION,
                operationsPerTransaction,
                transactionSamples,
                rollbackNanos,
                semantic,
                run));
        return List.copyOf(result);
    }

    private static RollbackCycle runRollbackCycle(
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            int operationsPerTransaction,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable) throws SQLException {
        try {
            OperationBatch batch = runOperationBatch(
                    scenario, operation, statementMode, operationsPerTransaction, reusable);
            long started = System.nanoTime();
            connection.rollback();
            return new RollbackCycle(batch, System.nanoTime() - started);
        } catch (SQLException | RuntimeException failure) {
            rollbackAfterFailure(connection, failure);
            throw failure;
        }
    }

    private static CommitCycle runCommitCycle(
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            int operationsPerTransaction,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable) throws SQLException {
        try {
            OperationBatch batch = runOperationBatch(
                    scenario, operation, statementMode, operationsPerTransaction, reusable);
            long started = System.nanoTime();
            connection.commit();
            long commitNanos = System.nanoTime() - started;
            scenario.restoreAfterCommittedOperation(operation);
            return new CommitCycle(commitNanos, batch.semantic());
        } catch (SQLException | RuntimeException failure) {
            rollbackAfterFailure(connection, failure);
            throw failure;
        }
    }

    private static OperationBatch runOperationBatch(
            DelosJdbcBenchmarkScenario scenario,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            int operationsPerTransaction,
            DelosJdbcBenchmarkScenario.PreparedOperation reusable) throws SQLException {
        if (statementMode.reusesStatement() != (reusable != null)) {
            throw new IllegalArgumentException("Statement mode and reusable operation disagree");
        }
        long firstPrepareNanos = 0;
        long firstExecuteNanos = 0;
        long repeatedPrepareNanos = 0;
        long repeatedExecuteNanos = 0;
        DelosBenchmarkResult semantic = null;
        DelosJdbcBenchmarkScenario.PreparedOperation prepared = reusable;
        try {
            for (int operationIndex = 0; operationIndex < operationsPerTransaction; operationIndex++) {
                long prepareNanos = 0;
                if (statementMode.measuresPreparePerOperation()) {
                    long started = System.nanoTime();
                    prepared = scenario.prepareOperation(operation);
                    prepareNanos = System.nanoTime() - started;
                }

                long started = System.nanoTime();
                DelosBenchmarkResult actual = prepared.execute();
                long executeNanos = System.nanoTime() - started;

                if (statementMode.measuresPreparePerOperation()) {
                    prepared.close();
                    prepared = null;
                }

                semantic = requireSameBatchSemantic(semantic, actual, operation, operationIndex);
                if (operationIndex == 0) {
                    firstPrepareNanos = prepareNanos;
                    firstExecuteNanos = executeNanos;
                } else {
                    repeatedPrepareNanos += prepareNanos;
                    repeatedExecuteNanos += executeNanos;
                }
            }
            return new OperationBatch(
                    firstPrepareNanos,
                    firstExecuteNanos,
                    repeatedPrepareNanos,
                    repeatedExecuteNanos,
                    semantic);
        } catch (SQLException | RuntimeException failure) {
            if (statementMode.measuresPreparePerOperation()) {
                closeAfterFailure(prepared, failure);
            }
            throw failure;
        }
    }

    private static DelosBenchmarkResult requireSameBatchSemantic(
            DelosBenchmarkResult expected,
            DelosBenchmarkResult actual,
            DelosBenchmarkOperation operation,
            int operationIndex) {
        if (expected == null) {
            return actual;
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Benchmark semantic drift inside one "
                    + operation.transactionKind() + " transaction for " + operation
                    + " at operation index " + operationIndex + ": expected=" + expected + ", actual=" + actual);
        }
        return expected;
    }

    private static DelosBenchmarkResult requireSameSemantic(
            DelosBenchmarkResult expected,
            DelosBenchmarkResult actual,
            DelosBenchmarkProvider provider,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            int run,
            String transactionEnd) {
        if (expected == null) {
            return actual;
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Benchmark semantic drift for " + provider + ' ' + operation
                    + ' ' + statementMode + " during run " + run + ' ' + transactionEnd
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return expected;
    }

    private static DelosBenchmarkMeasurement measurement(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            DelosBenchmarkOperation operation,
            DelosBenchmarkStatementMode statementMode,
            DelosBenchmarkPhase phase,
            DelosBenchmarkSampleScope sampleScope,
            DelosBenchmarkMeasurementUnit measurementUnit,
            int operationsPerTransaction,
            long measuredUnits,
            long elapsedNanos,
            DelosBenchmarkResult semantic,
            int run) {
        return new DelosBenchmarkMeasurement(
                provider,
                operation,
                statementMode,
                operation.transactionKind(),
                phase,
                sampleScope,
                measurementUnit,
                operationsPerTransaction,
                config.rowCount(),
                config.payloadSize(),
                config.commitBatchSize(),
                options.warmups(),
                options.iterations(),
                measuredUnits,
                elapsedNanos,
                measuredUnits * 1_000_000_000.0 / elapsedNanos,
                (double) elapsedNanos / measuredUnits,
                semantic.rowCount(),
                semantic.checksum(),
                run);
    }

    private static void closeAfterFailure(
            DelosJdbcBenchmarkScenario.PreparedOperation prepared,
            Throwable failure) {
        if (prepared == null) {
            return;
        }
        try {
            prepared.close();
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void rollbackAfterFailure(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void writeReports(Options options, List<DelosBenchmarkMeasurement> measurements)
            throws IOException {
        Files.writeString(options.reportDirectory().resolve("benchmark-results.csv"),
                csv(measurements), StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("benchmark-results.json"),
                json(measurements), StandardCharsets.UTF_8);
        Files.writeString(options.reportDirectory().resolve("benchmark-summary.txt"),
                summary(options, measurements), StandardCharsets.UTF_8);
    }

    private static String csv(List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder(
                "provider,operation,statementMode,transactionKind,phase,sampleScope,measurementUnit,"
                        + "operationsPerTransaction,"
                        + "rowCount,payloadSize,commitBatchSize,warmups,iterations,measuredUnits,elapsedNanos,"
                        + "throughputPerSecond,averageLatencyNanos,semanticRowCount,checksum,run\n");
        for (DelosBenchmarkMeasurement value : values) {
            out.append(value.provider().id()).append(',').append(value.operation()).append(',')
                    .append(value.statementMode()).append(',').append(value.transactionKind()).append(',')
                    .append(value.phase()).append(',')
                    .append(value.sampleScope()).append(',').append(value.measurementUnit()).append(',')
                    .append(value.operationsPerTransaction()).append(',').append(value.rowCount()).append(',')
                    .append(value.payloadSize()).append(',').append(value.commitBatchSize()).append(',')
                    .append(value.warmups()).append(',').append(value.iterations()).append(',')
                    .append(value.measuredUnits()).append(',').append(value.elapsedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.throughputPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos())).append(',')
                    .append(value.semanticRowCount()).append(',').append(value.checksum()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < values.size(); i++) {
            DelosBenchmarkMeasurement value = values.get(i);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"operation\":\"").append(value.operation())
                    .append("\",\"statementMode\":\"").append(value.statementMode())
                    .append("\",\"transactionKind\":\"").append(value.transactionKind())
                    .append("\",\"phase\":\"").append(value.phase())
                    .append("\",\"sampleScope\":\"").append(value.sampleScope())
                    .append("\",\"measurementUnit\":\"").append(value.measurementUnit())
                    .append("\",\"operationsPerTransaction\":").append(value.operationsPerTransaction())
                    .append(",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"commitBatchSize\":").append(value.commitBatchSize())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"measuredUnits\":").append(value.measuredUnits())
                    .append(",\"elapsedNanos\":").append(value.elapsedNanos())
                    .append(",\"throughputPerSecond\":")
                    .append(String.format(Locale.ROOT, "%.6f", value.throughputPerSecond()))
                    .append(",\"averageLatencyNanos\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.averageLatencyNanos()))
                    .append(",\"semanticRowCount\":").append(value.semanticRowCount())
                    .append(",\"checksum\":").append(value.checksum())
                    .append(",\"run\":").append(value.run()).append('}');
            if (i + 1 < values.size()) {
                out.append(',');
            }
            out.append('\n');
        }
        return out.append("]\n").toString();
    }

    private static String summary(Options options, List<DelosBenchmarkMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC prepared-statement lifecycle baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Rows: ").append(options.rowCounts()).append('\n')
                .append("Payload: ").append(options.payloadSize()).append('\n')
                .append("Fixture commit batch: ").append(options.commitBatchSize()).append('\n')
                .append("Read operations per transaction: ")
                .append(options.readOperationsPerTransaction()).append('\n')
                .append("Statement modes: ")
                .append(List.of(DelosBenchmarkStatementMode.values())).append('\n')
                .append("Statement mode order alternates by run: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (DelosBenchmarkMeasurement value : values) {
            out.append(String.format(Locale.ROOT,
                    "%7d %-4s %-5s %-28s %-28s %-8s %-19s ops/tx=%-4d unit=%-11s samples=%-6d "
                            + "run=%d rate=%12.3f avg-ns=%12.3f rows=%d checksum=%d%n",
                    value.rowCount(),
                    value.provider().id(),
                    value.transactionKind(),
                    value.operation(),
                    value.statementMode(),
                    value.phase(),
                    value.sampleScope(),
                    value.operationsPerTransaction(),
                    value.measurementUnit(),
                    value.measuredUnits(),
                    value.run(),
                    value.throughputPerSecond(),
                    value.averageLatencyNanos(),
                    value.semanticRowCount(),
                    value.checksum()));
        }
        return out.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private record SemanticKey(DelosBenchmarkProvider provider, DelosBenchmarkOperation operation, int rowCount) {
    }

    private record OperationBatch(
            long firstPrepareNanos,
            long firstExecuteNanos,
            long repeatedPrepareNanos,
            long repeatedExecuteNanos,
            DelosBenchmarkResult semantic) {
    }

    private record RollbackCycle(OperationBatch batch, long rollbackNanos) {
    }

    private record CommitCycle(long commitNanos, DelosBenchmarkResult semantic) {
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            int payloadSize,
            int commitBatchSize,
            int readOperationsPerTransaction,
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
            if (payloadSize <= 0 || commitBatchSize <= 0 || readOperationsPerTransaction < 2
                    || warmups < 0 || iterations <= 0 || runs <= 0) {
                throw new IllegalArgumentException(
                        "Benchmark dimensions must be positive, read operations per transaction must be at least two,"
                                + " and warmups must not be negative");
            }
        }
    }
}
