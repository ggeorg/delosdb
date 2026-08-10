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
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Isolated child-JVM worker for one cross-engine JDBC benchmark target and run.
 *
 * <p>The coordinator launches this class with exactly one embedded engine on
 * the class path. This isolation is mandatory for upstream Derby because
 * DelosDB and Derby intentionally share the same public package names.</p>
 */
public final class DelosJdbcCrossEngineWorker {
    private static final String PREFIX = "delosdb.benchmark.crossEngine.";
    private static final long SEED = 0x5DE10DBL;

    private DelosJdbcCrossEngineWorker() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.fromSystemProperties();
        options.validate();
        run(options);
    }

    private static void run(Options options) throws Exception {
        Files.createDirectories(options.reportDirectory());
        List<Measurement> measurements = new ArrayList<>();
        List<Integer> rowCounts = new ArrayList<>(options.rowCounts());
        if ((options.run() & 1) == 0) {
            Collections.reverse(rowCounts);
        }
        for (int rows : rowCounts) {
            DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                    rows,
                    options.payloadSize(),
                    SEED,
                    Math.min(options.fixtureCommitBatchSize(), rows));
            for (TransactionSpec spec : specsForRun(options)) {
                measurements.add(measureSpec(options, config, spec));
            }
        }
        writeCsv(options, measurements);
    }

    private static Measurement measureSpec(
            Options options,
            DelosBenchmarkConfig config,
            TransactionSpec spec) throws Exception {
        String specId = spec.workload().name().toLowerCase(Locale.ROOT)
                + "-" + spec.outcome().name().toLowerCase(Locale.ROOT)
                + "-w" + spec.operationsPerTransaction();
        Path database = options.databaseRoot()
                .resolve(options.target().id())
                .resolve("run-" + options.run())
                .resolve("rows-" + config.rowCount())
                .resolve(specId);

        return withFreshDatabase(options.target(), database, connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            String product = csvSafe(metadata.getDatabaseProductName());
            String productVersion = csvSafe(metadata.getDatabaseProductVersion());
            String driverVersion = csvSafe(metadata.getDriverVersion());
            DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(
                    connection,
                    options.target().id(),
                    options.target().createTableSuffix(),
                    false,
                    config);
            scenario.prepare();

            try (TransactionWorkload workload = prepareWorkload(connection, scenario, spec)) {
                Long expectedSemantic = null;
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    IntervalRun interval = runInterval(
                            connection,
                            workload,
                            spec,
                            options.transactionsPerInterval());
                    expectedSemantic = requireSameSemantic(
                            expectedSemantic,
                            interval.semanticFingerprint(),
                            options,
                            spec,
                            "warmup " + warmup);
                }

                long elapsedNanos = 0L;
                Long measuredSemantic = expectedSemantic;
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
                            options,
                            spec,
                            "measured iteration " + iteration);
                }
                if (measuredSemantic == null) {
                    throw new IllegalStateException("Cross-engine benchmark produced no semantic fingerprint");
                }

                long measuredTransactions = Math.multiplyExact(
                        (long) options.transactionsPerInterval(), options.iterations());
                long measuredOperations = Math.multiplyExact(
                        measuredTransactions, spec.operationsPerTransaction());
                return new Measurement(
                        options.target().id(),
                        product,
                        productVersion,
                        driverVersion,
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
                        options.run());
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
            rollbackAfterFailure(connection, failure);
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
            case UNCHANGED_UPDATE -> new UnchangedUpdateWorkload(
                    connection,
                    scenario.tableName(),
                    spec.operationsPerTransaction());
            case DELETE_REINSERT -> new PreparedOperationWorkload(
                    connection,
                    scenario.prepareOperation(DelosBenchmarkOperation.DELETE_REINSERT),
                    spec.operationsPerTransaction());
        };
    }

    private static List<TransactionSpec> specsForRun(Options options) {
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
                specs.add(new TransactionSpec(
                        DelosBenchmarkTransactionWorkload.UNCHANGED_UPDATE,
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
        if ((options.run() & 1) == 0) {
            Collections.reverse(specs);
        }
        return List.copyOf(specs);
    }

    private static Long requireSameSemantic(
            Long expected,
            long actual,
            Options options,
            TransactionSpec spec,
            String stage) {
        if (expected == null) {
            return actual;
        }
        if (expected.longValue() != actual) {
            throw new IllegalStateException("Cross-engine transaction semantic drift for "
                    + options.target().id() + ' ' + spec + " run=" + options.run()
                    + " during " + stage + ": expected=" + expected + ", actual=" + actual);
        }
        return expected;
    }

    private static void writeCsv(Options options, List<Measurement> values) throws IOException {
        Path output = options.reportDirectory().resolve(
                options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(
                "target,product,productVersion,driverVersion,workload,outcome,operationsPerTransaction,"
                        + "transactionsPerInterval,rowCount,payloadSize,fixtureCommitBatchSize,warmups,"
                        + "iterations,measuredTransactions,measuredOperations,elapsedNanos,"
                        + "transactionsPerSecond,averageTransactionLatencyNanos,semanticFingerprint,run\n");
        for (Measurement value : values) {
            out.append(value.target()).append(',')
                    .append(value.product()).append(',')
                    .append(value.productVersion()).append(',')
                    .append(value.driverVersion()).append(',')
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
                    .append(String.format(Locale.ROOT, "%.6f", value.transactionsPerSecond())).append(',')
                    .append(String.format(Locale.ROOT, "%.6f", value.averageTransactionLatencyNanos())).append(',')
                    .append(value.semanticFingerprint()).append(',')
                    .append(value.run()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    private static <T> T withFreshDatabase(
            Target target,
            Path database,
            ConnectionWork<T> work) throws Exception {
        deleteRecursively(database);
        Files.createDirectories(database.getParent());
        if (target == Target.H2) {
            Files.createDirectories(database);
        }

        Connection connection = null;
        T result = null;
        Throwable failure = null;
        try {
            connection = DriverManager.getConnection(target.jdbcUrl(database));
            result = work.execute(connection);
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        if (connection != null) {
            failure = rollbackOpenConnection(connection, failure);
            failure = closeConnection(connection, failure);
        }
        if (target.isDerby() && Files.exists(database)) {
            failure = shutdownDerby(database, failure);
        }
        try {
            deleteRecursively(database);
        } catch (Throwable cleanupFailure) {
            failure = preserve(failure, cleanupFailure);
        }
        if (failure != null) {
            throwFailure(failure);
        }
        return result;
    }

    private static Throwable rollbackOpenConnection(Connection connection, Throwable primaryFailure) {
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                connection.rollback();
            }
        } catch (Throwable cleanupFailure) {
            return preserve(primaryFailure, cleanupFailure);
        }
        return primaryFailure;
    }

    private static Throwable closeConnection(Connection connection, Throwable primaryFailure) {
        try {
            connection.close();
        } catch (Throwable cleanupFailure) {
            return preserve(primaryFailure, cleanupFailure);
        }
        return primaryFailure;
    }

    private static Throwable shutdownDerby(Path database, Throwable primaryFailure) {
        try {
            DriverManager.getConnection("jdbc:derby:" + database.toAbsolutePath() + ";shutdown=true");
            return preserve(primaryFailure, new IllegalStateException(
                    "Embedded Derby shutdown completed without SQLState 08006: " + database));
        } catch (SQLException expectedShutdown) {
            if ("08006".equals(expectedShutdown.getSQLState())) {
                return primaryFailure;
            }
            return preserve(primaryFailure, expectedShutdown);
        } catch (Throwable cleanupFailure) {
            return preserve(primaryFailure, cleanupFailure);
        }
    }

    private static Throwable preserve(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Unexpected cross-engine benchmark failure", failure);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static void rollbackAfterFailure(Connection connection, Throwable primaryFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    private static String csvSafe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static List<Integer> integerList(String property, String defaultValue) {
        String raw = System.getProperty(property, defaultValue);
        List<Integer> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            values.add(Integer.parseInt(token.trim()));
        }
        return List.copyOf(values);
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
        private final DelosBenchmarkResult expected;

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
                    throw new IllegalStateException("Prepared cross-engine workload semantic drift: expected="
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

    private static final class UnchangedUpdateWorkload implements TransactionWorkload {
        private final Connection connection;
        private final int[] ids;
        private final int[] baselineQuantities;
        private final PreparedStatement update;
        private final PreparedStatement select;

        private UnchangedUpdateWorkload(
                Connection connection,
                String table,
                int operationsPerTransaction) throws SQLException {
            this.connection = connection;
            this.ids = new int[operationsPerTransaction];
            this.baselineQuantities = new int[operationsPerTransaction];
            PreparedStatement localUpdate = null;
            PreparedStatement localSelect = null;
            try {
                localUpdate = connection.prepareStatement(
                        "update " + table + " set quantity = quantity where id = ?");
                localSelect = connection.prepareStatement(
                        "select quantity from " + table + " where id = ?");
                this.update = localUpdate;
                this.select = localSelect;
                for (int index = 0; index < operationsPerTransaction; index++) {
                    ids[index] = index + 1;
                    baselineQuantities[index] = quantity(ids[index]);
                }
                connection.rollback();
            } catch (SQLException failure) {
                closeAfterFailure(failure, localSelect, localUpdate);
                throw failure;
            }
        }

        @Override
        public long executeOperations() throws SQLException {
            long fingerprint = 1L;
            for (int id : ids) {
                update.setInt(1, id);
                int updated = update.executeUpdate();
                if (updated != 1) {
                    throw new SQLException(
                            "Unchanged cross-engine update did not affect one row: id=" + id);
                }
                fingerprint = mix(mix(fingerprint, id), updated);
            }
            return fingerprint;
        }

        @Override
        public long verifyAndRestore(
                DelosBenchmarkTransactionOutcome outcome,
                int transactionsPerInterval) throws SQLException {
            long fingerprint = 1L;
            for (int index = 0; index < ids.length; index++) {
                int actual = quantity(ids[index]);
                if (actual != baselineQuantities[index]) {
                    throw new IllegalStateException(
                            "Unchanged cross-engine update modified id=" + ids[index]
                                    + ": expected=" + baselineQuantities[index]
                                    + ", actual=" + actual + ", outcome=" + outcome);
                }
                fingerprint = mix(mix(fingerprint, ids[index]), actual);
            }
            connection.rollback();
            return fingerprint;
        }

        private int quantity(int id) throws SQLException {
            select.setInt(1, id);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Unchanged cross-engine target row is missing: id=" + id);
                }
                int quantity = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Unchanged cross-engine query returned duplicate id=" + id);
                }
                return quantity;
            }
        }

        @Override
        public void close() throws SQLException {
            closeStatements(select, update);
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
                    throw new SQLException(
                            "Indexed cross-engine update did not affect one row: id=" + id);
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
                    throw new IllegalStateException(
                            "Indexed cross-engine state mismatch for id=" + ids[index]
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
                        throw new SQLException(
                                "Indexed cross-engine restoration did not affect one row: id=" + ids[index]);
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
                    throw new SQLException("Indexed cross-engine target row is missing: id=" + id);
                }
                int quantity = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Indexed cross-engine query returned duplicate id=" + id);
                }
                return quantity;
            }
        }

        @Override
        public void close() throws SQLException {
            closeStatements(restore, select, increment);
        }
    }

    private static void closeAfterFailure(Throwable failure, PreparedStatement... statements) {
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

    enum Target {
        DELOS_HEAP("delos_heap", "", true),
        DELOS_MVCC("delos_mvcc", " using delos_mvcc", true),
        UPSTREAM_DERBY("upstream_derby", "", true),
        H2("h2", "", false);

        private final String id;
        private final String createTableSuffix;
        private final boolean derby;

        Target(String id, String createTableSuffix, boolean derby) {
            this.id = id;
            this.createTableSuffix = createTableSuffix;
            this.derby = derby;
        }

        String id() {
            return id;
        }

        String createTableSuffix() {
            return createTableSuffix;
        }

        boolean isDerby() {
            return derby;
        }

        String jdbcUrl(Path database) {
            String path = database.toAbsolutePath().normalize().toString();
            if (this == H2) {
                return "jdbc:h2:file:" + database.resolve("database").toAbsolutePath().normalize()
                        + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
            }
            return "jdbc:derby:" + path + ";create=true";
        }

        static Target parse(String value) {
            for (Target target : values()) {
                if (target.id.equalsIgnoreCase(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException("Unknown cross-engine target: " + value);
        }
    }

    private record IntervalRun(long elapsedNanos, long semanticFingerprint) {
    }

    private record TransactionSpec(
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
        private TransactionSpec {
            Objects.requireNonNull(workload, "workload");
            Objects.requireNonNull(outcome, "outcome");
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

    private record Measurement(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction,
            int transactionsPerInterval,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long elapsedNanos,
            double transactionsPerSecond,
            double averageTransactionLatencyNanos,
            long semanticFingerprint,
            int run) {
    }

    private record Options(
            Target target,
            int run,
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            List<Integer> readWidths,
            List<Integer> writeWidths,
            int transactionsPerInterval,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations) {
        static Options fromSystemProperties() {
            return new Options(
                    Target.parse(System.getProperty(PREFIX + "target")),
                    Integer.parseInt(System.getProperty(PREFIX + "run")),
                    Path.of(System.getProperty(PREFIX + "databaseRoot")),
                    Path.of(System.getProperty(PREFIX + "reportDirectory")),
                    integerList(PREFIX + "rows", "1000"),
                    integerList(PREFIX + "readWidths", "1,10"),
                    integerList(PREFIX + "writeWidths", "1,10"),
                    Integer.parseInt(System.getProperty(PREFIX + "cycles", "10")),
                    Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                    Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                    Integer.parseInt(System.getProperty(PREFIX + "warmups", "1")),
                    Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")));
        }

        void validate() {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(databaseRoot, "databaseRoot");
            Objects.requireNonNull(reportDirectory, "reportDirectory");
            if (run < 1) {
                throw new IllegalArgumentException("run must be positive");
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
                throw new IllegalArgumentException("cycles must be between 1 and 10000");
            }
            if (payloadSize < 16 || payloadSize > 4096) {
                throw new IllegalArgumentException("payload must be between 16 and 4096");
            }
            if (fixtureCommitBatchSize < 1) {
                throw new IllegalArgumentException("fixtureBatch must be positive");
            }
            if (warmups < 0 || warmups > 100) {
                throw new IllegalArgumentException("warmups must be between 0 and 100");
            }
            if (iterations < 1 || iterations > 100) {
                throw new IllegalArgumentException("iterations must be between 1 and 100");
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

    @FunctionalInterface
    private interface ConnectionWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
