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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Four-engine JDBC concurrency comparison with deterministic semantic verification. */
public final class DelosJdbcCrossEngineConcurrency {
    private static final String PREFIX = "delosdb.benchmark.crossEngineConcurrency.";
    private static final long SEED = 0x5DE10DBL;
    private static final String CSV_HEADER =
            "target,product,productVersion,driverVersion,workload,clients,operationsPerTransaction,"
                    + "transactionsPerClient,rowCount,payloadSize,fixtureCommitBatchSize,warmups,iterations,"
                    + "measuredTransactions,measuredOperations,retryableRollbacks,elapsedNanos,transactionsPerSecond,"
                    + "averageTransactionLatencyNanos,semanticFingerprint,run";

    private DelosJdbcCrossEngineConcurrency() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.fromSystemProperties();
        options.validate();
        if (args.length == 1 && "worker".equals(args[0])) {
            runWorker(options);
        } else if (args.length == 0) {
            runCoordinator(options);
        } else {
            throw new IllegalArgumentException("Expected no argument or exactly 'worker'");
        }
    }

    private static void runCoordinator(Options options) throws Exception {
        if (!"false".equals(System.getProperty(PREFIX + "sane"))) {
            throw new IllegalStateException(
                    "Cross-engine concurrency comparison requires -Pdelosdb.sane=false");
        }
        deleteRecursively(options.reportDirectory());
        deleteRecursively(options.databaseRoot());
        Files.createDirectories(options.reportDirectory().resolve("workers"));
        Files.createDirectories(options.reportDirectory().resolve("logs"));
        Files.createDirectories(options.databaseRoot());

        for (int run = 1; run <= options.runs(); run++) {
            List<Target> targets = new ArrayList<>(List.of(Target.values()));
            if (((run - 1) & 2) != 0) {
                Collections.reverse(targets);
            }
            for (Target target : targets) {
                launchWorker(options, target, run);
            }
        }

        List<Row> rows = loadRows(options);
        validateRows(options, rows);
        writeMergedCsv(options, rows);
        writeRatioCsv(options, rows);
        writeScalingCsv(options, rows);
        writeDispersionCsv(options, rows);
        writeSummary(options, rows);
    }

    private static void launchWorker(Options options, Target target, int run) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toString());
        command.add("-Xms" + options.childHeap());
        command.add("-Xmx" + options.childHeap());
        command.add("-XX:+AlwaysPreTouch");
        command.add("-cp");
        command.add(options.benchmarkClasses() + java.io.File.pathSeparator + options.classpath(target));
        addProperty(command, "target", target.id());
        addProperty(command, "run", run);
        addProperty(command, "databaseRoot", options.databaseRoot());
        addProperty(command, "reportDirectory", options.reportDirectory().resolve("workers"));
        addProperty(command, "rows", options.rows());
        addProperty(command, "clients", options.clients());
        addProperty(command, "widths", options.widths());
        addProperty(command, "transactionsPerClient", options.transactionsPerClient());
        addProperty(command, "payload", options.payload());
        addProperty(command, "fixtureBatch", options.fixtureBatch());
        addProperty(command, "warmups", options.warmups());
        addProperty(command, "iterations", options.iterations());
        addProperty(command, "caseTimeoutSeconds", options.caseTimeoutSeconds());
        command.add(DelosJdbcCrossEngineConcurrency.class.getName());
        command.add("worker");

        Path log = options.reportDirectory().resolve("logs")
                .resolve(String.format(Locale.ROOT, "%02d-%s.log", run, target.id()));
        Process process = new ProcessBuilder(command)
                .directory(options.projectDirectory().toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        int status = process.waitFor();
        if (status != 0) {
            List<String> lines = Files.exists(log) ? Files.readAllLines(log) : List.of();
            int from = Math.max(0, lines.size() - 40);
            throw new IllegalStateException("Concurrency worker failed: target=" + target.id()
                    + ", run=" + run + ", exit=" + status + ", log=" + log
                    + (lines.isEmpty() ? "" : "\n" + String.join("\n", lines.subList(from, lines.size()))));
        }
    }

    private static void addProperty(List<String> command, String name, Object value) {
        command.add("-D" + PREFIX + name + '=' + value);
    }

    private static void runWorker(Options options) throws Exception {
        Files.createDirectories(options.reportDirectory());
        List<Measurement> measurements = new ArrayList<>();
        List<Spec> specs = specsForRun(options);
        for (int rows : ordered(options.rowCounts(), options.run())) {
            DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                    rows, options.payload(), SEED, Math.min(options.fixtureBatch(), rows));
            for (Spec spec : specs) {
                measurements.add(measureSpec(options, config, spec));
            }
        }
        writeWorkerCsv(options, measurements);
    }

    private static Measurement measureSpec(Options options, DelosBenchmarkConfig config, Spec spec)
            throws Exception {
        String specId = spec.workload().name().toLowerCase(Locale.ROOT)
                + "-c" + spec.clients() + "-w" + spec.operationsPerTransaction();
        Path database = options.databaseRoot().resolve(options.target().id())
                .resolve("run-" + options.run()).resolve("rows-" + config.rowCount()).resolve(specId);

        deleteRecursively(database);
        Files.createDirectories(database.getParent());
        if (options.target() == Target.H2) {
            Files.createDirectories(database);
        }

        Throwable failure = null;
        Measurement measurement = null;
        try (Connection verifier = DriverManager.getConnection(options.target().jdbcUrl(database))) {
            DatabaseMetaData metadata = verifier.getMetaData();
            String product = csvSafe(metadata.getDatabaseProductName());
            String productVersion = csvSafe(metadata.getDatabaseProductVersion());
            String driverVersion = csvSafe(metadata.getDriverVersion());
            DelosJdbcBenchmarkScenario scenario = new DelosJdbcBenchmarkScenario(
                    verifier, options.target().id(), options.target().createTableSuffix(), false, config);
            scenario.prepare();
            try (ConcurrentCase concurrentCase = new ConcurrentCase(
                    options, spec, database, verifier, scenario.tableName(), config.rowCount())) {
                Long expectedSemantic = null;
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    Interval interval = concurrentCase.runInterval();
                    expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                            "warmup " + warmup);
                }
                long elapsed = 0L;
                long retryableRollbacks = 0L;
                Long measuredSemantic = expectedSemantic;
                for (int iteration = 0; iteration < options.iterations(); iteration++) {
                    Interval interval = concurrentCase.runInterval();
                    elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                    retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                    measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                            "measured iteration " + iteration);
                }
                long measuredTransactions = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), options.transactionsPerClient()),
                        options.iterations());
                long measuredOperations = Math.multiplyExact(
                        measuredTransactions, spec.operationsPerTransaction());
                measurement = new Measurement(
                        options.target().id(), product, productVersion, driverVersion,
                        spec.workload(), spec.clients(), spec.operationsPerTransaction(),
                        options.transactionsPerClient(), config.rowCount(), config.payloadSize(),
                        config.commitBatchSize(), options.warmups(), options.iterations(),
                        measuredTransactions, measuredOperations, retryableRollbacks, elapsed,
                        measuredTransactions * 1_000_000_000.0 / elapsed,
                        (double) elapsed / measuredTransactions,
                        Objects.requireNonNull(measuredSemantic), options.run());
            }
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        if (options.target().isDerby() && Files.exists(database)) {
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
        return measurement;
    }

    private static final class ConcurrentCase implements AutoCloseable {
        private final Options options;
        private final Spec spec;
        private final Connection verifier;
        private final String table;
        private final int[] ids;
        private final int[] baseline;
        private final List<Client> clients;
        private final ExecutorService executor;

        private ConcurrentCase(
                Options options,
                Spec spec,
                Path database,
                Connection verifier,
                String table,
                int rowCount) throws SQLException {
            this.options = options;
            this.spec = spec;
            this.verifier = verifier;
            this.table = table;
            this.ids = targetIds(spec, rowCount);
            this.baseline = new int[ids.length];
            for (int index = 0; index < ids.length; index++) {
                baseline[index] = quantity(verifier, table, ids[index]);
            }
            verifier.rollback();
            this.clients = new ArrayList<>(spec.clients());
            try {
                for (int client = 0; client < spec.clients(); client++) {
                    Connection connection = DriverManager.getConnection(options.target().jdbcUrl(database));
                    connection.setAutoCommit(false);
                    clients.add(new Client(connection, table, spec, client, baseline[0]));
                }
            } catch (SQLException failure) {
                closeClients(failure);
                throw failure;
            }
            this.executor = Executors.newFixedThreadPool(spec.clients());
        }

        private Interval runInterval() throws Exception {
            CountDownLatch ready = new CountDownLatch(spec.clients());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ClientRun>> futures = new ArrayList<>(spec.clients());
            for (Client client : clients) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency start barrier timed out");
                    }
                    return client.runTransactions(options.transactionsPerClient(), spec.operationsPerTransaction());
                }));
            }
            if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                start.countDown();
                throw new IllegalStateException("concurrency readiness barrier timed out");
            }
            long started = System.nanoTime();
            start.countDown();
            long executionFingerprint = 1L;
            long retryableRollbacks = 0L;
            Throwable failure = null;
            for (Future<ClientRun> future : futures) {
                try {
                    ClientRun clientRun = future.get(options.caseTimeoutSeconds(), TimeUnit.SECONDS);
                    executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                    retryableRollbacks = Math.addExact(retryableRollbacks, clientRun.retryableRollbacks());
                } catch (Throwable clientFailure) {
                    failure = preserve(failure, clientFailure);
                }
            }
            long elapsed = System.nanoTime() - started;
            if (failure != null) {
                throwFailure(failure);
            }
            long stateFingerprint = verifyAndRestore();
            return new Interval(elapsed, mix(executionFingerprint, stateFingerprint), retryableRollbacks);
        }

        private long verifyAndRestore() throws SQLException {
            long fingerprint = mix(spec.clients(), spec.operationsPerTransaction());
            int increment = Math.multiplyExact(options.transactionsPerClient(), spec.operationsPerTransaction());
            for (int index = 0; index < ids.length; index++) {
                int expected = baseline[index];
                if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE) {
                    expected += increment;
                } else if (spec.workload() == Workload.CONTENDED_INDEXED_UPDATE) {
                    expected += Math.multiplyExact(spec.clients(), increment);
                }
                int actual = quantity(verifier, table, ids[index]);
                if (actual != expected) {
                    throw new IllegalStateException("Concurrent semantic drift for " + spec
                            + ", id=" + ids[index] + ": expected=" + expected + ", actual=" + actual);
                }
                fingerprint = mix(mix(fingerprint, ids[index]), actual);
            }
            if (spec.workload() != Workload.PRIMARY_KEY_READ) {
                try (PreparedStatement restore = verifier.prepareStatement(
                        "update " + table + " set quantity = ? where id = ?")) {
                    for (int index = 0; index < ids.length; index++) {
                        restore.setInt(1, baseline[index]);
                        restore.setInt(2, ids[index]);
                        if (restore.executeUpdate() != 1) {
                            throw new SQLException("Concurrent restore did not affect one row: id=" + ids[index]);
                        }
                    }
                }
                verifier.commit();
            } else {
                verifier.rollback();
            }
            return fingerprint;
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            closeClients(null);
        }

        private void closeClients(Throwable primary) throws SQLException {
            SQLException first = null;
            for (Client client : clients) {
                try {
                    client.close();
                } catch (SQLException failure) {
                    if (first == null) {
                        first = failure;
                    } else {
                        first.addSuppressed(failure);
                    }
                }
            }
            if (first != null) {
                if (primary != null) {
                    primary.addSuppressed(first);
                } else {
                    throw first;
                }
            }
        }
    }

    private static final class Client implements AutoCloseable {
        private final Connection connection;
        private final Workload workload;
        private final int id;
        private final int expectedReadQuantity;
        private final PreparedStatement read;
        private final PreparedStatement update;

        private Client(
                Connection connection, String table, Spec spec, int client, int expectedReadQuantity)
                throws SQLException {
            this.connection = connection;
            this.workload = spec.workload();
            this.id = workload == Workload.DISJOINT_INDEXED_UPDATE ? client + 1 : 1;
            this.expectedReadQuantity = expectedReadQuantity;
            PreparedStatement localRead = null;
            PreparedStatement localUpdate = null;
            try {
                if (workload == Workload.PRIMARY_KEY_READ) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else {
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                }
                this.read = localRead;
                this.update = localUpdate;
            } catch (SQLException failure) {
                closeStatement(localUpdate, failure);
                closeStatement(localRead, failure);
                throw failure;
            }
        }

        private ClientRun runTransactions(int transactions, int operationsPerTransaction) throws SQLException {
            long fingerprint = 1L;
            long retryableRollbacks = 0L;
            for (int transaction = 0; transaction < transactions; transaction++) {
                int attempts = 0;
                while (true) {
                    long transactionFingerprint = 1L;
                    try {
                        for (int operation = 0; operation < operationsPerTransaction; operation++) {
                            if (workload == Workload.PRIMARY_KEY_READ) {
                                read.setInt(1, id);
                                try (ResultSet resultSet = read.executeQuery()) {
                                    if (!resultSet.next()) {
                                        throw new SQLException("Concurrent read row missing: id=" + id);
                                    }
                                    int quantity = resultSet.getInt(1);
                                    if (quantity != expectedReadQuantity) {
                                        throw new SQLException("Concurrent read value changed: id=" + id
                                                + ", expected=" + expectedReadQuantity + ", actual=" + quantity);
                                    }
                                    transactionFingerprint = mix(transactionFingerprint, quantity);
                                    if (resultSet.next()) {
                                        throw new SQLException("Concurrent read returned duplicate id=" + id);
                                    }
                                }
                            } else {
                                update.setInt(1, id);
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException("Concurrent update did not affect one row: id=" + id);
                                }
                            }
                            transactionFingerprint = mix(transactionFingerprint, id);
                        }
                        connection.commit();
                        fingerprint = mix(fingerprint, transactionFingerprint);
                        break;
                    } catch (SQLException failure) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                            throw failure;
                        }
                        if (!isRetryableRollback(failure) || ++attempts >= 1000) {
                            throw failure;
                        }
                        retryableRollbacks++;
                    }
                }
            }
            return new ClientRun(fingerprint, retryableRollbacks);
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            try {
                if (!connection.isClosed() && !connection.getAutoCommit()) {
                    connection.rollback();
                }
            } catch (SQLException rollbackFailure) {
                failure = rollbackFailure;
            }
            try {
                if (read != null) {
                    read.close();
                }
            } catch (SQLException closeFailure) {
                failure = closeFailure;
            }
            try {
                if (update != null) {
                    update.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static int[] targetIds(Spec spec, int rowCount) {
        if (spec.workload() == Workload.DISJOINT_INDEXED_UPDATE) {
            if (spec.clients() > rowCount) {
                throw new IllegalArgumentException(
                        "disjoint concurrency clients exceed fixture rows: clients="
                                + spec.clients() + ", rows=" + rowCount);
            }
            int[] ids = new int[spec.clients()];
            for (int index = 0; index < ids.length; index++) {
                ids[index] = 1 + (int) (((long) index * rowCount) / ids.length);
            }
            return ids;
        }
        return new int[]{1};
    }

    private static int quantity(Connection connection, String table, int id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Concurrent verification row missing: id=" + id);
                }
                int quantity = resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Concurrent verification returned duplicate id=" + id);
                }
                return quantity;
            }
        }
    }

    private static List<Spec> specsForRun(Options options) {
        List<Spec> specs = new ArrayList<>();
        for (int width : options.widthValues()) {
            for (int clients : options.clientValues()) {
                for (Workload workload : Workload.values()) {
                    specs.add(new Spec(workload, clients, width));
                }
            }
        }
        int phase = (options.run() - 1) & 3;
        if (phase == 1 || phase == 2) {
            Collections.reverse(specs);
        }
        return List.copyOf(specs);
    }

    private static List<Integer> ordered(List<Integer> values, int run) {
        List<Integer> ordered = new ArrayList<>(values);
        if (((run - 1) & 1) != 0) {
            Collections.reverse(ordered);
        }
        return ordered;
    }

    private static Long sameSemantic(Long expected, long actual, Spec spec, String stage) {
        if (expected != null && expected.longValue() != actual) {
            throw new IllegalStateException("Concurrent semantic drift for " + spec + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return actual;
    }

    private static void writeWorkerCsv(Options options, List<Measurement> values) throws IOException {
        Path output = options.reportDirectory().resolve(options.target().id() + "-run-" + options.run() + ".csv");
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Measurement value : values) {
            out.append(value.csv()).append('\n');
        }
        Files.writeString(output, out.toString(), StandardCharsets.UTF_8);
    }

    private static List<Row> loadRows(Options options) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (Target target : Target.values()) {
                Path file = options.reportDirectory().resolve("workers")
                        .resolve(target.id() + "-run-" + run + ".csv");
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (lines.isEmpty() || !CSV_HEADER.equals(lines.getFirst())) {
                    throw new IllegalStateException("Unexpected concurrency CSV header: " + file);
                }
                for (int index = 1; index < lines.size(); index++) {
                    if (!lines.get(index).isBlank()) {
                        rows.add(Row.parse(lines.get(index)));
                    }
                }
            }
        }
        rows.sort(Comparator.comparingInt(Row::rowCount)
                .thenComparing(Row::workload)
                .thenComparingInt(Row::operationsPerTransaction)
                .thenComparingInt(Row::clients)
                .thenComparing(Row::target)
                .thenComparingInt(Row::run));
        return List.copyOf(rows);
    }

    private static void validateRows(Options options, List<Row> rows) {
        int expected = Target.values().length * options.runs() * options.rowCounts().size()
                * options.clientValues().size() * options.widthValues().size() * Workload.values().length;
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "Concurrency measurement count mismatch: expected=" + expected + ", actual=" + rows.size());
        }
        Map<ShapeKey, Long> semantics = new HashMap<>();
        for (Row row : rows) {
            ShapeKey key = row.shape();
            Long prior = semantics.putIfAbsent(key, row.semanticFingerprint());
            if (prior != null && prior.longValue() != row.semanticFingerprint()) {
                throw new IllegalStateException("Cross-engine concurrency semantic mismatch for " + key
                        + ": expected=" + prior + ", actual=" + row.semanticFingerprint()
                        + ", target=" + row.target() + ", run=" + row.run());
            }
        }
    }

    private static void writeMergedCsv(Options options, List<Row> rows) throws IOException {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Row row : rows) {
            out.append(row.csv()).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-results.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeRatioCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(rows);
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,delosHeapMedianTps,delosMvccMedianTps,"
                        + "upstreamDerbyMedianTps,h2MedianTps,delosHeapToDerby,delosMvccToDerby,"
                        + "delosHeapToH2,delosMvccToH2\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            EnumMap<Target, Double> values = entry.getValue();
            double heap = require(values, Target.DELOS_HEAP, key);
            double mvcc = require(values, Target.DELOS_MVCC, key);
            double derby = require(values, Target.UPSTREAM_DERBY, key);
            double h2 = require(values, Target.H2, key);
            out.append(key.csv()).append(',')
                    .append(format(heap)).append(',').append(format(mvcc)).append(',')
                    .append(format(derby)).append(',').append(format(h2)).append(',')
                    .append(format(heap / derby)).append(',').append(format(mvcc / derby)).append(',')
                    .append(format(heap / h2)).append(',').append(format(mvcc / h2)).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-ratios.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeScalingCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(rows);
        Map<BaselineKey, EnumMap<Target, Double>> baselines = new HashMap<>();
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            if (entry.getKey().clients() == 1) {
                baselines.put(entry.getKey().baselineKey(), entry.getValue());
            }
        }
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,target,medianTransactionsPerSecond,"
                        + "speedupFromOneClient,parallelEfficiency\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            EnumMap<Target, Double> baseline = baselines.get(key.baselineKey());
            if (baseline == null) {
                throw new IllegalStateException("Missing one-client concurrency baseline for " + key);
            }
            for (Target target : Target.values()) {
                double tps = require(entry.getValue(), target, key);
                double one = require(baseline, target, key);
                double speedup = tps / one;
                out.append(key.csv()).append(',').append(target.id()).append(',')
                        .append(format(tps)).append(',').append(format(speedup)).append(',')
                        .append(format(speedup / key.clients())).append('\n');
            }
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-scaling.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeDispersionCsv(Options options, List<Row> rows) throws IOException {
        Map<ShapeTargetKey, List<Double>> values = new HashMap<>();
        for (Row row : rows) {
            values.computeIfAbsent(new ShapeTargetKey(row.shape(), Target.parse(row.target())),
                    ignored -> new ArrayList<>()).add(row.transactionsPerSecond());
        }
        List<ShapeTargetKey> keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.comparing((ShapeTargetKey key) -> key.shape().rowCount())
                .thenComparing(key -> key.shape().workload())
                .thenComparingInt(key -> key.shape().operationsPerTransaction())
                .thenComparingInt(key -> key.shape().clients())
                .thenComparing(ShapeTargetKey::target));
        StringBuilder out = new StringBuilder(
                "rowCount,workload,clients,operationsPerTransaction,target,runs,medianTps,q1Tps,q3Tps,"
                        + "iqrTps,madTps,minTps,maxTps,iqrToMedian,madToMedian,maxToMin\n");
        for (ShapeTargetKey key : keys) {
            Distribution distribution = distribution(values.get(key));
            out.append(key.shape().csv()).append(',').append(key.target().id()).append(',')
                    .append(distribution.count()).append(',')
                    .append(format(distribution.median())).append(',')
                    .append(format(distribution.q1())).append(',').append(format(distribution.q3())).append(',')
                    .append(format(distribution.iqr())).append(',').append(format(distribution.mad())).append(',')
                    .append(format(distribution.min())).append(',').append(format(distribution.max())).append(',')
                    .append(format(distribution.iqr() / distribution.median())).append(',')
                    .append(format(distribution.mad() / distribution.median())).append(',')
                    .append(format(distribution.max() / distribution.min())).append('\n');
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-dispersion.csv"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeSummary(Options options, List<Row> rows) throws IOException {
        Map<ShapeKey, EnumMap<Target, Double>> medians = medianThroughput(rows);
        StringBuilder out = new StringBuilder();
        out.append("DelosDB JDBC four-engine concurrency comparison\n")
                .append("Rows: ").append(options.rows()).append('\n')
                .append("Clients: ").append(options.clients()).append('\n')
                .append("Operations per transaction: ").append(options.widths()).append('\n')
                .append("Transactions per client/interval: ").append(options.transactionsPerClient()).append('\n')
                .append("Workloads: PRIMARY_KEY_READ, DISJOINT_INDEXED_UPDATE, CONTENDED_INDEXED_UPDATE\n")
                .append("Each client owns one JDBC connection and reused prepared statement.\n")
                .append("Disjoint-update client rows are evenly spread across the fixture.\n")
                .append("Timed interval: synchronized client execution through final commit.\n")
                .append("Semantic verification/restoration outside timed interval: true\n")
                .append("Fresh database per target/run/matrix cell: true\n")
                .append("Target and matrix order orthogonalized across four-run blocks: true\n")
                .append("Warmups: ").append(options.warmups()).append('\n')
                .append("Iterations: ").append(options.iterations()).append('\n')
                .append("Runs: ").append(options.runs()).append("\n\n");
        for (Map.Entry<ShapeKey, EnumMap<Target, Double>> entry : medians.entrySet()) {
            ShapeKey key = entry.getKey();
            out.append(String.format(Locale.ROOT,
                    "%7d %-25s clients=%-2d ops/tx=%-2d heap=%11.2f mvcc=%11.2f derby=%11.2f h2=%11.2f tx/s%n",
                    key.rowCount(), key.workload(), key.clients(), key.operationsPerTransaction(),
                    require(entry.getValue(), Target.DELOS_HEAP, key),
                    require(entry.getValue(), Target.DELOS_MVCC, key),
                    require(entry.getValue(), Target.UPSTREAM_DERBY, key),
                    require(entry.getValue(), Target.H2, key)));
        }
        Files.writeString(options.reportDirectory().resolve("cross-engine-concurrency-summary.txt"),
                out.toString(), StandardCharsets.UTF_8);
    }

    private static Map<ShapeKey, EnumMap<Target, Double>> medianThroughput(List<Row> rows) {
        Map<ShapeTargetKey, List<Double>> grouped = new HashMap<>();
        for (Row row : rows) {
            grouped.computeIfAbsent(new ShapeTargetKey(row.shape(), Target.parse(row.target())),
                    ignored -> new ArrayList<>()).add(row.transactionsPerSecond());
        }
        List<ShapeKey> shapes = grouped.keySet().stream().map(ShapeTargetKey::shape).distinct().sorted(
                Comparator.comparingInt(ShapeKey::rowCount).thenComparing(ShapeKey::workload)
                        .thenComparingInt(ShapeKey::operationsPerTransaction)
                        .thenComparingInt(ShapeKey::clients)).toList();
        Map<ShapeKey, EnumMap<Target, Double>> result = new java.util.LinkedHashMap<>();
        for (ShapeKey shape : shapes) {
            EnumMap<Target, Double> values = new EnumMap<>(Target.class);
            for (Target target : Target.values()) {
                List<Double> samples = grouped.get(new ShapeTargetKey(shape, target));
                if (samples != null) {
                    values.put(target, median(samples));
                }
            }
            result.put(shape, values);
        }
        return result;
    }

    private static double require(EnumMap<Target, Double> values, Target target, Object key) {
        Double value = values.get(target);
        if (value == null) {
            throw new IllegalStateException("Missing " + target.id() + " result for " + key);
        }
        return value;
    }

    private static Distribution distribution(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double median = medianSorted(sorted);
        double q1 = percentile(sorted, 0.25);
        double q3 = percentile(sorted, 0.75);
        List<Double> deviations = sorted.stream().map(value -> Math.abs(value - median)).sorted().toList();
        return new Distribution(sorted.size(), median, q1, q3, q3 - q1,
                medianSorted(deviations), sorted.getFirst(), sorted.getLast());
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return medianSorted(sorted);
    }

    private static double medianSorted(List<Double> sorted) {
        int size = sorted.size();
        return (size & 1) == 0
                ? (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0
                : sorted.get(size / 2);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double index = percentile * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = index - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private static Throwable shutdownDerby(Path database, Throwable primary) {
        try {
            DriverManager.getConnection("jdbc:derby:" + database.toAbsolutePath() + ";shutdown=true");
            return preserve(primary, new IllegalStateException(
                    "Embedded Derby shutdown completed without SQLState 08006: " + database));
        } catch (SQLException expected) {
            return "08006".equals(expected.getSQLState()) ? primary : preserve(primary, expected);
        } catch (Throwable failure) {
            return preserve(primary, failure);
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
        throw new IllegalStateException("Unexpected concurrency benchmark failure", failure);
    }

    private static boolean isRetryableRollback(SQLException failure) {
        String sqlState = failure.getSQLState();
        return sqlState != null && sqlState.startsWith("40");
    }

    private static void closeStatement(PreparedStatement statement, Throwable primary) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
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

    private static String csvSafe(String value) {
        return value == null ? "" : value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    private static List<Integer> integerList(String raw) {
        List<Integer> values = new ArrayList<>();
        for (String token : raw.split(",")) {
            values.add(Integer.parseInt(token.trim()));
        }
        return List.copyOf(values);
    }

    private enum Workload {
        PRIMARY_KEY_READ,
        DISJOINT_INDEXED_UPDATE,
        CONTENDED_INDEXED_UPDATE
    }

    private enum Target {
        DELOS_HEAP("delos_heap", ""),
        DELOS_MVCC("delos_mvcc", " using delos_mvcc"),
        UPSTREAM_DERBY("upstream_derby", ""),
        H2("h2", "");

        private final String id;
        private final String createTableSuffix;

        Target(String id, String createTableSuffix) {
            this.id = id;
            this.createTableSuffix = createTableSuffix;
        }

        String id() {
            return id;
        }

        String createTableSuffix() {
            return createTableSuffix;
        }

        boolean isDerby() {
            return this != H2;
        }

        String jdbcUrl(Path database) {
            if (this == H2) {
                return "jdbc:h2:file:" + database.resolve("database").toAbsolutePath().normalize()
                        + ";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
            }
            return "jdbc:derby:" + database.toAbsolutePath().normalize() + ";create=true";
        }

        static Target parse(String value) {
            for (Target target : values()) {
                if (target.id.equalsIgnoreCase(value)) {
                    return target;
                }
            }
            throw new IllegalArgumentException("Unknown concurrency target: " + value);
        }
    }

    private record Spec(Workload workload, int clients, int operationsPerTransaction) {
    }

    private record ClientRun(long fingerprint, long retryableRollbacks) {
    }

    private record Interval(long elapsedNanos, long semanticFingerprint, long retryableRollbacks) {
    }

    private record Measurement(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int transactionsPerClient,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long retryableRollbacks,
            long elapsedNanos,
            double transactionsPerSecond,
            double averageTransactionLatencyNanos,
            long semanticFingerprint,
            int run) {
        String csv() {
            return String.join(",", target, product, productVersion, driverVersion, workload.name(),
                    Integer.toString(clients), Integer.toString(operationsPerTransaction),
                    Integer.toString(transactionsPerClient), Integer.toString(rowCount),
                    Integer.toString(payloadSize), Integer.toString(fixtureCommitBatchSize),
                    Integer.toString(warmups), Integer.toString(iterations),
                    Long.toString(measuredTransactions), Long.toString(measuredOperations),
                    Long.toString(retryableRollbacks), Long.toString(elapsedNanos), format(transactionsPerSecond),
                    format(averageTransactionLatencyNanos), Long.toString(semanticFingerprint),
                    Integer.toString(run));
        }
    }

    private record Row(
            String target,
            String product,
            String productVersion,
            String driverVersion,
            Workload workload,
            int clients,
            int operationsPerTransaction,
            int transactionsPerClient,
            int rowCount,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            long measuredTransactions,
            long measuredOperations,
            long retryableRollbacks,
            long elapsedNanos,
            double transactionsPerSecond,
            double averageTransactionLatencyNanos,
            long semanticFingerprint,
            int run) {
        static Row parse(String line) {
            String[] fields = line.split(",", -1);
            if (fields.length != 21) {
                throw new IllegalArgumentException(
                        "Expected 21 concurrency CSV fields, found " + fields.length + ": " + line);
            }
            return new Row(fields[0], fields[1], fields[2], fields[3], Workload.valueOf(fields[4]),
                    Integer.parseInt(fields[5]), Integer.parseInt(fields[6]), Integer.parseInt(fields[7]),
                    Integer.parseInt(fields[8]), Integer.parseInt(fields[9]), Integer.parseInt(fields[10]),
                    Integer.parseInt(fields[11]), Integer.parseInt(fields[12]), Long.parseLong(fields[13]),
                    Long.parseLong(fields[14]), Long.parseLong(fields[15]), Long.parseLong(fields[16]),
                    Double.parseDouble(fields[17]), Double.parseDouble(fields[18]), Long.parseLong(fields[19]),
                    Integer.parseInt(fields[20]));
        }

        ShapeKey shape() {
            return new ShapeKey(rowCount, workload, clients, operationsPerTransaction);
        }

        String csv() {
            return new Measurement(target, product, productVersion, driverVersion, workload, clients,
                    operationsPerTransaction, transactionsPerClient, rowCount, payloadSize,
                    fixtureCommitBatchSize, warmups, iterations, measuredTransactions, measuredOperations,
                    retryableRollbacks, elapsedNanos, transactionsPerSecond, averageTransactionLatencyNanos,
                    semanticFingerprint, run).csv();
        }
    }

    private record ShapeKey(int rowCount, Workload workload, int clients, int operationsPerTransaction) {
        String csv() {
            return rowCount + "," + workload + "," + clients + "," + operationsPerTransaction;
        }

        BaselineKey baselineKey() {
            return new BaselineKey(rowCount, workload, operationsPerTransaction);
        }
    }

    private record BaselineKey(int rowCount, Workload workload, int operationsPerTransaction) {
    }

    private record ShapeTargetKey(ShapeKey shape, Target target) {
    }

    private record Distribution(
            int count, double median, double q1, double q3, double iqr, double mad, double min, double max) {
    }

    private record Options(
            Path projectDirectory,
            Path javaExecutable,
            String benchmarkClasses,
            String delosClasspath,
            String upstreamDerbyClasspath,
            String h2Classpath,
            Path databaseRoot,
            Path reportDirectory,
            String rows,
            String clients,
            String widths,
            int transactionsPerClient,
            int payload,
            int fixtureBatch,
            int warmups,
            int iterations,
            int runs,
            int caseTimeoutSeconds,
            String childHeap,
            Target target,
            int run) {
        static Options fromSystemProperties() {
            String targetValue = System.getProperty(PREFIX + "target");
            return new Options(
                    path(PREFIX + "projectDirectory", "."),
                    path(PREFIX + "javaExecutable", Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                    System.getProperty(PREFIX + "benchmarkClasses", "."),
                    System.getProperty(PREFIX + "delosClasspath", "."),
                    System.getProperty(PREFIX + "upstreamDerbyClasspath", "."),
                    System.getProperty(PREFIX + "h2Classpath", "."),
                    path(PREFIX + "databaseRoot", "build/tmp/delos-jdbc-cross-engine-concurrency"),
                    path(PREFIX + "reportDirectory", "build/reports/delosdb/benchmarks/cross-engine-concurrency"),
                    System.getProperty(PREFIX + "rows", "10000"),
                    System.getProperty(PREFIX + "clients", "1,2,4,8"),
                    System.getProperty(PREFIX + "widths", "1,10"),
                    Integer.parseInt(System.getProperty(PREFIX + "transactionsPerClient", "50")),
                    Integer.parseInt(System.getProperty(PREFIX + "payload", "128")),
                    Integer.parseInt(System.getProperty(PREFIX + "fixtureBatch", "100")),
                    Integer.parseInt(System.getProperty(PREFIX + "warmups", "2")),
                    Integer.parseInt(System.getProperty(PREFIX + "iterations", "3")),
                    Integer.parseInt(System.getProperty(PREFIX + "runs", "4")),
                    Integer.parseInt(System.getProperty(PREFIX + "caseTimeoutSeconds", "120")),
                    System.getProperty(PREFIX + "childHeap", "1g"),
                    targetValue == null ? null : Target.parse(targetValue),
                    Integer.parseInt(System.getProperty(PREFIX + "run", "0")));
        }

        void validate() {
            if (!Files.isRegularFile(javaExecutable)) {
                throw new IllegalArgumentException("Java executable does not exist: " + javaExecutable);
            }
            parsePositive(rows, "rows", 100);
            parsePositive(clients, "clients", 1);
            parsePositive(widths, "widths", 1);
            int maxClients = clientValues().stream().mapToInt(Integer::intValue).max().orElseThrow();
            int minRows = rowCounts().stream().mapToInt(Integer::intValue).min().orElseThrow();
            if (maxClients > minRows) {
                throw new IllegalArgumentException("clients cannot exceed rows");
            }
            if (!clientValues().contains(1)) {
                throw new IllegalArgumentException("clients must include 1 for scaling ratios");
            }
            if (transactionsPerClient < 1 || payload < 16 || fixtureBatch < 1 || warmups < 0
                    || iterations < 1 || caseTimeoutSeconds < 1) {
                throw new IllegalArgumentException("Invalid concurrency benchmark numeric option");
            }
            if (runs < 4 || (runs & 3) != 0) {
                throw new IllegalArgumentException("runs must be a multiple of 4 for orthogonal order");
            }
            if (childHeap.isBlank()) {
                throw new IllegalArgumentException("childHeap is required");
            }
            if (target != null && run < 1) {
                throw new IllegalArgumentException("worker run must be positive");
            }
        }

        List<Integer> rowCounts() {
            return integerList(rows);
        }

        List<Integer> clientValues() {
            return integerList(clients);
        }

        List<Integer> widthValues() {
            return integerList(widths);
        }

        String classpath(Target value) {
            return switch (value) {
                case DELOS_HEAP, DELOS_MVCC -> delosClasspath;
                case UPSTREAM_DERBY -> upstreamDerbyClasspath;
                case H2 -> h2Classpath;
            };
        }

        private static Path path(String property, String defaultValue) {
            return Path.of(System.getProperty(property, defaultValue));
        }

        private static void parsePositive(String raw, String name, int minimum) {
            for (int value : integerList(raw)) {
                if (value < minimum) {
                    throw new IllegalArgumentException(name + " values must be at least " + minimum + ": " + value);
                }
            }
        }
    }
}
