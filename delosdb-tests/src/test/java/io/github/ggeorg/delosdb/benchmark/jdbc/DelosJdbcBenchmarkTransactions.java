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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * Provider-neutral full-transaction throughput benchmark.
 *
 * <p>Each timing interval contains a configurable number of complete JDBC
 * transaction cycles. Statement execution and commit or rollback are inside
 * the interval. Semantic verification and restoration are deliberately
 * outside the interval. This complements the phase-isolated baseline by
 * measuring whole transaction shapes without one timer read per cycle.</p>
 */
public final class DelosJdbcBenchmarkTransactions {
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcBenchmarkTransactions() {
    }

    public static List<DelosBenchmarkTransactionMeasurement> run(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<Integer> readWidths,
            List<Integer> writeWidths,
            int transactionsPerInterval,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) throws Exception {
        Options options = new Options(
                databaseRoot,
                reportDirectory,
                List.copyOf(rowCounts),
                List.copyOf(readWidths),
                List.copyOf(writeWidths),
                transactionsPerInterval,
                payloadSize,
                fixtureCommitBatchSize,
                warmups,
                iterations,
                runs);
        options.validate();
        DelosBenchmarkSupport.prepareOutput(options.databaseRoot(), options.reportDirectory());

        List<DelosBenchmarkTransactionMeasurement> measurements = new ArrayList<>();
        Map<SemanticKey, Long> expectedSemantics = new HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : options.rowCounts()) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows,
                        options.payloadSize(),
                        SEED,
                        Math.min(options.fixtureCommitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : providersForRun(run)) {
                    for (TransactionSpec spec : specsForRun(options, run)) {
                        measurements.add(measureSpec(
                                options,
                                config,
                                provider,
                                spec,
                                run,
                                expectedSemantics));
                    }
                }
            }
        }

        measurements.sort(Comparator
                .comparingInt(DelosBenchmarkTransactionMeasurement::rowCount)
                .thenComparing(DelosBenchmarkTransactionMeasurement::provider)
                .thenComparing(DelosBenchmarkTransactionMeasurement::workload)
                .thenComparing(DelosBenchmarkTransactionMeasurement::outcome)
                .thenComparingInt(DelosBenchmarkTransactionMeasurement::operationsPerTransaction)
                .thenComparingInt(DelosBenchmarkTransactionMeasurement::run));
        writeReports(options, measurements);
        return List.copyOf(measurements);
    }

    private static DelosBenchmarkTransactionMeasurement measureSpec(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            TransactionSpec spec,
            int run,
            Map<SemanticKey, Long> expectedSemantics) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id()
                + "-" + config.rowCount()
                + "-" + spec.workload().name().toLowerCase(Locale.ROOT)
                + "-" + spec.outcome().name().toLowerCase(Locale.ROOT)
                + "-w" + spec.operationsPerTransaction()
                + "-run" + run);
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            DelosJdbcBenchmarkScenario scenario =
                    new DelosJdbcBenchmarkScenario(connection, provider, config);
            scenario.prepare();
            try (TransactionWorkload workload = prepareWorkload(connection, scenario, spec)) {
                    Long warmupSemantic = null;
                    for (int warmup = 0; warmup < options.warmups(); warmup++) {
                        IntervalRun interval = runInterval(
                                connection,
                                workload,
                                spec,
                                options.transactionsPerInterval());
                        warmupSemantic = requireSameSemantic(
                                warmupSemantic,
                                interval.semanticFingerprint(),
                                provider,
                                spec,
                                run,
                                "warmup " + warmup);
                    }

                    long elapsedNanos = 0L;
                    Long measuredSemantic = warmupSemantic;
                    for (int iteration = 0; iteration < options.iterations(); iteration++) {
                        IntervalRun interval = runInterval(
                                connection,
                                workload,
                                spec,
                                options.transactionsPerInterval());
                        elapsedNanos = Math.addExact(elapsedNanos, interval.elapsedNanos());
                        measuredSemantic = requireSameSemantic(
                                measuredSemantic,
                                interval.semanticFingerprint(),
                                provider,
                                spec,
                                run,
                                "measured iteration " + iteration);
                    }
                    if (measuredSemantic == null) {
                        throw new IllegalStateException("Transaction benchmark produced no semantic fingerprint");
                    }

                    SemanticKey semanticKey = new SemanticKey(
                            config.rowCount(),
                            spec.workload(),
                            spec.outcome(),
                            spec.operationsPerTransaction());
                    Long prior = expectedSemantics.putIfAbsent(semanticKey, measuredSemantic);
                    if (prior != null && prior.longValue() != measuredSemantic.longValue()) {
                        throw new IllegalStateException("Non-reproducible transaction semantics for "
                                + semanticKey + ": expected=" + prior + ", actual=" + measuredSemantic
                                + ", provider=" + provider + ", run=" + run);
                    }

                    long measuredTransactions = Math.multiplyExact(
                            (long) options.transactionsPerInterval(), options.iterations());
                    long measuredOperations = Math.multiplyExact(
                            measuredTransactions, spec.operationsPerTransaction());
                    return new DelosBenchmarkTransactionMeasurement(
                            provider,
                            spec.workload(),
                            spec.outcome(),
                            spec.operationsPerTransaction(),
                            options.transactionsPerInterval(),
                            config.rowCount(),
                            config.payloadSize(),
                            config.commitBatchSize(),
                            options.warmups(),
                            options.iterations(),
                            measuredTransactions,
                            measuredOperations,
                            elapsedNanos,
                            measuredTransactions * 1_000_000_000.0 / elapsedNanos,
                            (double) elapsedNanos / measuredTransactions,
                            measuredSemantic,
                            run);
            }
        });
    }

    private static IntervalRun runInterval(
            Connection connection,
            TransactionWorkload workload,
            TransactionSpec spec,
            int transactionsPerInterval) throws SQLException {
        try {
            long executionFingerprint = 1L;
            long started = System.nanoTime();
            for (int transaction = 0; transaction < transactionsPerInterval; transaction++) {
                long transactionFingerprint = workload.executeOperations();
                if (spec.outcome() == DelosBenchmarkTransactionOutcome.COMMIT) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
                executionFingerprint = mix(executionFingerprint, transactionFingerprint);
            }
            long elapsedNanos = System.nanoTime() - started;
            long stateFingerprint = workload.verifyAndRestore(
                    spec.outcome(), transactionsPerInterval);
            return new IntervalRun(
                    elapsedNanos,
                    mix(mix(executionFingerprint, stateFingerprint), spec.outcome().ordinal()));
        } catch (SQLException | RuntimeException failure) {
            DelosBenchmarkSupport.rollbackAfterFailure(connection, failure);
            throw failure;
        }
    }

    private static TransactionWorkload prepareWorkload(
            Connection connection,
            DelosJdbcBenchmarkScenario scenario,
            TransactionSpec spec) throws SQLException {
        return switch (spec.workload()) {
            case EMPTY -> new EmptyWorkload();
            case PRIMARY_KEY_READ -> new PreparedOperationWorkload(
                    connection,
                    scenario.prepareOperation(DelosBenchmarkOperation.PRIMARY_KEY_LOOKUP),
                    spec.operationsPerTransaction());
            case INDEXED_UPDATE -> new IndexedUpdateWorkload(
                    connection,
                    scenario.tableName(),
                    spec.operationsPerTransaction());
            case DELETE_REINSERT -> new PreparedOperationWorkload(
                    connection,
                    scenario.prepareOperation(DelosBenchmarkOperation.DELETE_REINSERT),
                    spec.operationsPerTransaction());
        };
    }

    private static List<TransactionSpec> specsForRun(Options options, int run) {
        List<TransactionSpec> specs = new ArrayList<>();
        for (DelosBenchmarkTransactionOutcome outcome : DelosBenchmarkTransactionOutcome.values()) {
            specs.add(new TransactionSpec(DelosBenchmarkTransactionWorkload.EMPTY, outcome, 0));
        }
        for (int width : options.readWidths()) {
            for (DelosBenchmarkTransactionOutcome outcome : DelosBenchmarkTransactionOutcome.values()) {
                specs.add(new TransactionSpec(
                        DelosBenchmarkTransactionWorkload.PRIMARY_KEY_READ,
                        outcome,
                        width));
            }
        }
        for (int width : options.writeWidths()) {
            for (DelosBenchmarkTransactionOutcome outcome : DelosBenchmarkTransactionOutcome.values()) {
                specs.add(new TransactionSpec(
                        DelosBenchmarkTransactionWorkload.INDEXED_UPDATE,
                        outcome,
                        width));
            }
        }
        for (DelosBenchmarkTransactionOutcome outcome : DelosBenchmarkTransactionOutcome.values()) {
            specs.add(new TransactionSpec(
                    DelosBenchmarkTransactionWorkload.DELETE_REINSERT,
                    outcome,
                    1));
        }
        if ((run & 1) == 0) {
            Collections.reverse(specs);
        }
        return List.copyOf(specs);
    }

    private static List<DelosBenchmarkProvider> providersForRun(int run) {
        List<DelosBenchmarkProvider> providers = new ArrayList<>(
                List.of(DelosBenchmarkProvider.values()));
        if ((run & 1) == 0) {
            Collections.reverse(providers);
        }
        return List.copyOf(providers);
    }

    private static Long requireSameSemantic(
            Long expected,
            long actual,
            DelosBenchmarkProvider provider,
            TransactionSpec spec,
            int run,
            String stage) {
        if (expected == null) {
            return actual;
        }
        if (expected.longValue() != actual) {
            throw new IllegalStateException("Transaction semantic drift for " + provider + ' ' + spec
                    + " run=" + run + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return expected;
    }

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    private static void writeReports(
            Options options,
            List<DelosBenchmarkTransactionMeasurement> measurements) throws IOException {
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("transaction-results.csv"),
                csv(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("transaction-results.json"),
                json(measurements));
        DelosBenchmarkSupport.writeUtf8(options.reportDirectory().resolve("transaction-summary.txt"),
                summary(options, measurements));
    }

    private static String csv(List<DelosBenchmarkTransactionMeasurement> values) {
        StringBuilder out = new StringBuilder(
                "provider,workload,outcome,operationsPerTransaction,transactionsPerInterval,rowCount,"
                        + "payloadSize,fixtureCommitBatchSize,warmups,iterations,measuredTransactions,"
                        + "measuredOperations,elapsedNanos,transactionsPerSecond,"
                        + "averageTransactionLatencyNanos,semanticFingerprint,run\n");
        for (DelosBenchmarkTransactionMeasurement value : values) {
            out.append(value.provider().id()).append(',')
                    .append(value.workload()).append(',')
                    .append(value.outcome()).append(',')
                    .append(value.operationsPerTransaction()).append(',')
                    .append(value.transactionsPerInterval()).append(',')
                    .append(value.rowCount()).append(',')
                    .append(value.payloadSize()).append(',')
                    .append(value.fixtureCommitBatchSize()).append(',')
                    .append(value.warmups()).append(',')
                    .append(value.iterations()).append(',')
                    .append(value.measuredTransactions()).append(',')
                    .append(value.measuredOperations()).append(',')
                    .append(value.elapsedNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.transactionsPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", value.averageTransactionLatencyNanos())).append(',')
                    .append(value.semanticFingerprint()).append(',')
                    .append(value.run()).append('\n');
        }
        return out.toString();
    }

    private static String json(List<DelosBenchmarkTransactionMeasurement> values) {
        StringBuilder out = new StringBuilder("[\n");
        for (int index = 0; index < values.size(); index++) {
            DelosBenchmarkTransactionMeasurement value = values.get(index);
            out.append("  {\"provider\":\"").append(value.provider().id())
                    .append("\",\"workload\":\"").append(value.workload())
                    .append("\",\"outcome\":\"").append(value.outcome())
                    .append("\",\"operationsPerTransaction\":").append(value.operationsPerTransaction())
                    .append(",\"transactionsPerInterval\":").append(value.transactionsPerInterval())
                    .append(",\"rowCount\":").append(value.rowCount())
                    .append(",\"payloadSize\":").append(value.payloadSize())
                    .append(",\"fixtureCommitBatchSize\":").append(value.fixtureCommitBatchSize())
                    .append(",\"warmups\":").append(value.warmups())
                    .append(",\"iterations\":").append(value.iterations())
                    .append(",\"measuredTransactions\":").append(value.measuredTransactions())
                    .append(",\"measuredOperations\":").append(value.measuredOperations())
                    .append(",\"elapsedNanos\":").append(value.elapsedNanos())
                    .append(",\"transactionsPerSecond\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.transactionsPerSecond()))
                    .append(",\"averageTransactionLatencyNanos\":")
                    .append(String.format(Locale.ROOT, "%.3f", value.averageTransactionLatencyNanos()))
                    .append(",\"semanticFingerprint\":").append(value.semanticFingerprint())
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
            List<DelosBenchmarkTransactionMeasurement> values) {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC explicit-transaction baseline\n")
                .append("Generated: ").append(Instant.now()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("Rows: ").append(options.rowCounts()).append('\n')
                .append("Payload: ").append(options.payloadSize()).append('\n')
                .append("Fixture commit batch: ").append(options.fixtureCommitBatchSize()).append('\n')
                .append("Read widths: ").append(options.readWidths()).append('\n')
                .append("Indexed-update widths: ").append(options.writeWidths()).append('\n')
                .append("Transactions per timing interval: ")
                .append(options.transactionsPerInterval()).append('\n')
                .append("Statement mode: REUSED_ACROSS_TRANSACTIONS\n")
                .append("Measured interval: operation execution plus transaction end\n")
                .append("Semantic verification/restoration outside timing interval: true\n")
                .append("Provider and transaction-shape order alternates by run: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (DelosBenchmarkTransactionMeasurement value : values) {
            out.append(String.format(Locale.ROOT,
                    "%7d %-4s %-17s %-8s ops/tx=%-4d tx/interval=%-4d run=%d "
                            + "rate=%12.3f avg-tx-ns=%12.3f fingerprint=%d%n",
                    value.rowCount(),
                    value.provider().id(),
                    value.workload(),
                    value.outcome(),
                    value.operationsPerTransaction(),
                    value.transactionsPerInterval(),
                    value.run(),
                    value.transactionsPerSecond(),
                    value.averageTransactionLatencyNanos(),
                    value.semanticFingerprint()));
        }
        return out.toString();
    }

    private interface TransactionWorkload extends AutoCloseable {
        long executeOperations() throws SQLException;

        long verifyAndRestore(
                DelosBenchmarkTransactionOutcome outcome,
                int transactionsPerInterval) throws SQLException;

        @Override
        void close() throws SQLException;
    }

    private static final class EmptyWorkload implements TransactionWorkload {
        @Override
        public long executeOperations() {
            return 0L;
        }

        @Override
        public long verifyAndRestore(
                DelosBenchmarkTransactionOutcome outcome,
                int transactionsPerInterval) {
            return mix(transactionsPerInterval, outcome.ordinal());
        }

        @Override
        public void close() {
        }
    }

    private static final class PreparedOperationWorkload implements TransactionWorkload {
        private final Connection connection;
        private final DelosJdbcBenchmarkScenario.PreparedOperation operation;
        private final int operationsPerTransaction;
        private DelosBenchmarkResult expected;

        private PreparedOperationWorkload(
                Connection connection,
                DelosJdbcBenchmarkScenario.PreparedOperation operation,
                int operationsPerTransaction) throws SQLException {
            this.connection = connection;
            this.operation = operation;
            this.operationsPerTransaction = operationsPerTransaction;
            this.expected = operation.execute();
            connection.rollback();
        }

        @Override
        public long executeOperations() throws SQLException {
            long fingerprint = 1L;
            for (int index = 0; index < operationsPerTransaction; index++) {
                DelosBenchmarkResult actual = operation.execute();
                if (!expected.equals(actual)) {
                    throw new IllegalStateException("Prepared transaction workload semantic drift: expected="
                            + expected + ", actual=" + actual + ", operationIndex=" + index);
                }
                fingerprint = mix(mix(fingerprint, actual.rowCount()), actual.checksum());
            }
            return fingerprint;
        }

        @Override
        public long verifyAndRestore(
                DelosBenchmarkTransactionOutcome outcome,
                int transactionsPerInterval) throws SQLException {
            connection.rollback();
            return mix(mix(expected.rowCount(), expected.checksum()),
                    (long) transactionsPerInterval * operationsPerTransaction);
        }

        @Override
        public void close() throws SQLException {
            operation.close();
        }
    }

    private static final class IndexedUpdateWorkload implements TransactionWorkload {
        private final Connection connection;
        private final int[] ids;
        private final int[] baselineQuantities;
        private final PreparedStatement increment;
        private final PreparedStatement select;
        private final PreparedStatement restore;

        private IndexedUpdateWorkload(
                Connection connection,
                String table,
                int operationsPerTransaction) throws SQLException {
            this.connection = connection;
            this.ids = new int[operationsPerTransaction];
            this.baselineQuantities = new int[operationsPerTransaction];
            PreparedStatement localIncrement = null;
            PreparedStatement localSelect = null;
            PreparedStatement localRestore = null;
            try {
                localIncrement = connection.prepareStatement(
                        "update " + table + " set quantity = quantity + 1 where id = ?");
                localSelect = connection.prepareStatement(
                        "select quantity from " + table + " where id = ?");
                localRestore = connection.prepareStatement(
                        "update " + table + " set quantity = ? where id = ?");
                this.increment = localIncrement;
                this.select = localSelect;
                this.restore = localRestore;
                for (int index = 0; index < operationsPerTransaction; index++) {
                    ids[index] = index + 1;
                    baselineQuantities[index] = quantity(ids[index]);
                }
                connection.rollback();
            } catch (SQLException failure) {
                closeAfterFailure(failure, localRestore, localSelect, localIncrement);
                throw failure;
            }
        }

        @Override
        public long executeOperations() throws SQLException {
            long fingerprint = 1L;
            for (int id : ids) {
                increment.setInt(1, id);
                int updated = increment.executeUpdate();
                if (updated != 1) {
                    throw new SQLException("Indexed transaction update did not affect one row: id=" + id);
                }
                fingerprint = mix(mix(fingerprint, id), updated);
            }
            return fingerprint;
        }

        @Override
        public long verifyAndRestore(
                DelosBenchmarkTransactionOutcome outcome,
                int transactionsPerInterval) throws SQLException {
            int committedDelta = outcome == DelosBenchmarkTransactionOutcome.COMMIT
                    ? transactionsPerInterval
                    : 0;
            long fingerprint = 1L;
            for (int index = 0; index < ids.length; index++) {
                int actual = quantity(ids[index]);
                int expectedQuantity = baselineQuantities[index] + committedDelta;
                if (actual != expectedQuantity) {
                    throw new IllegalStateException("Indexed transaction state mismatch for id=" + ids[index]
                            + ": expected=" + expectedQuantity + ", actual=" + actual
                            + ", outcome=" + outcome);
                }
                fingerprint = mix(mix(fingerprint, ids[index]), actual);
            }

            if (outcome == DelosBenchmarkTransactionOutcome.COMMIT) {
                for (int index = 0; index < ids.length; index++) {
                    restore.setInt(1, baselineQuantities[index]);
                    restore.setInt(2, ids[index]);
                    if (restore.executeUpdate() != 1) {
                        throw new SQLException("Indexed transaction restoration did not affect one row: id="
                                + ids[index]);
                    }
                }
                connection.commit();
            } else {
                connection.rollback();
            }
            return fingerprint;
        }

        private int quantity(int id) throws SQLException {
            select.setInt(1, id);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Indexed transaction target row is missing: id=" + id);
                }
                int quantity = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Indexed transaction target query returned duplicate id=" + id);
                }
                return quantity;
            }
        }

        @Override
        public void close() throws SQLException {
            closeStatements(restore, select, increment);
        }
    }

    private static void closeAfterFailure(
            Throwable failure,
            PreparedStatement... statements) {
        try {
            closeStatements(statements);
        } catch (SQLException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeStatements(PreparedStatement... statements) throws SQLException {
        SQLException failure = null;
        for (PreparedStatement statement : statements) {
            if (statement == null) {
                continue;
            }
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record IntervalRun(long elapsedNanos, long semanticFingerprint) {
    }

    private record TransactionSpec(
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
        private TransactionSpec {
            if (operationsPerTransaction < 0) {
                throw new IllegalArgumentException("operationsPerTransaction must not be negative");
            }
            if (workload == DelosBenchmarkTransactionWorkload.EMPTY
                    && operationsPerTransaction != 0) {
                throw new IllegalArgumentException("EMPTY transactions must have zero operations");
            }
            if (workload != DelosBenchmarkTransactionWorkload.EMPTY
                    && operationsPerTransaction == 0) {
                throw new IllegalArgumentException(workload + " transactions must have operations");
            }
        }
    }

    private record SemanticKey(
            int rowCount,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<Integer> readWidths,
            List<Integer> writeWidths,
            int transactionsPerInterval,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) {
        private void validate() {
            if (databaseRoot == null || reportDirectory == null) {
                throw new IllegalArgumentException("databaseRoot and reportDirectory are required");
            }
            requireNonEmptyPositive(rowCounts, "rowCounts");
            requireNonEmptyPositive(readWidths, "readWidths");
            requireNonEmptyPositive(writeWidths, "writeWidths");
            for (int rows : rowCounts) {
                if (rows < 100) {
                    throw new IllegalArgumentException("rowCounts must contain values of at least 100");
                }
                for (int width : writeWidths) {
                    if (width > rows) {
                        throw new IllegalArgumentException(
                                "write width " + width + " exceeds row count " + rows);
                    }
                }
            }
            if (transactionsPerInterval < 1 || transactionsPerInterval > 10_000) {
                throw new IllegalArgumentException(
                        "transactionsPerInterval must be between 1 and 10000");
            }
            if (payloadSize < 16 || payloadSize > 4096) {
                throw new IllegalArgumentException("payloadSize must be between 16 and 4096");
            }
            if (fixtureCommitBatchSize < 1) {
                throw new IllegalArgumentException("fixtureCommitBatchSize must be positive");
            }
            if (warmups < 0 || warmups > 100) {
                throw new IllegalArgumentException("warmups must be between 0 and 100");
            }
            if (iterations < 1 || iterations > 100) {
                throw new IllegalArgumentException("iterations must be between 1 and 100");
            }
            if (runs < 1 || runs > 100) {
                throw new IllegalArgumentException("runs must be between 1 and 100");
            }
        }

        private static void requireNonEmptyPositive(List<Integer> values, String name) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            if (values.stream().anyMatch(value -> value == null || value < 1)) {
                throw new IllegalArgumentException(name + " must contain only positive values");
            }
        }
    }
}
