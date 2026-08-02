/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Public-JDBC concurrent commit benchmark with JDK JFR contention and file-I/O evidence.
 *
 * <p>This is an external measurement lane. It changes no database setting and
 * imports no Derby or DelosDB implementation API.</p>
 */
public final class DelosConcurrentCommitBenchmark {
    private static final String FILE_WRITE_EVENT = "jdk.FileWrite";
    private static final String MONITOR_ENTER_EVENT = "jdk.JavaMonitorEnter";
    private static final String THREAD_PARK_EVENT = "jdk.ThreadPark";
    private static final String GC_PAUSE_EVENT = "jdk.GCPhasePause";
    private static final String TABLE_PREFIX = "DELOS_CC_";
    private static final long INSERT_WARMUP_BASE = 1_000_000_000L;
    private static final long INSERT_MEASUREMENT_BASE = 2_000_000_000L;

    private DelosConcurrentCommitBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.fromSystemProperties();
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
        Files.createDirectories(config.outputDirectory());
        Files.createDirectories(config.databaseRoot());

        List<Result> results = new ArrayList<>();
        for (Topology topology : config.topologies()) {
            for (Operation operation : config.operations()) {
                for (int rowsPerTransaction : config.rowsPerTransaction()) {
                    for (int writers : config.writers()) {
                        Scenario scenario = new Scenario(topology, operation, writers, rowsPerTransaction);
                        Result result = runScenario(config, scenario);
                        results.add(result);
                        System.out.println(result.humanLine());
                    }
                }
            }
        }

        writeCsv(config.outputDirectory().resolve("results.csv"), results);
        writeJson(config.outputDirectory().resolve("results.json"), config, results);
        writeHuman(config.outputDirectory().resolve("human.txt"), config, results);
    }

    private static Result runScenario(Config config, Scenario scenario) throws Exception {
        Path scenarioRoot = Files.createTempDirectory(
                config.databaseRoot(),
                scenario.fileStem() + '-');
        try (ScenarioEnvironment environment = ScenarioEnvironment.create(scenarioRoot, scenario)) {
            if (scenario.operation() == Operation.UPDATE) {
                environment.seedUpdateRows();
            }
            if (config.warmupTransactionsPerWriter() > 0) {
                runRound(
                        environment,
                        scenario,
                        config.warmupTransactionsPerWriter(),
                        INSERT_WARMUP_BASE);
            }

            Path recordingFile = config.outputDirectory().resolve(scenario.fileStem() + ".jfr");
            Files.deleteIfExists(recordingFile);
            RoundResult round;
            try (Recording recording = new Recording()) {
                enableCurrentJfrEvents(recording);
                recording.start();
                round = runRound(
                        environment,
                        scenario,
                        config.transactionsPerWriter(),
                        INSERT_MEASUREMENT_BASE);
                recording.stop();
                recording.dump(recordingFile);
            }

            environment.verify(
                    config.warmupTransactionsPerWriter(),
                    config.transactionsPerWriter());
            JfrMetrics jfr = readJfrMetrics(recordingFile);
            if (!config.keepJfr()) {
                Files.deleteIfExists(recordingFile);
            }
            return Result.from(scenario, round, jfr);
        } finally {
            deleteRecursively(scenarioRoot);
        }
    }

    private static RoundResult runRound(
            ScenarioEnvironment environment,
            Scenario scenario,
            int transactionsPerWriter,
            long insertBase) throws Exception {
        int writers = scenario.writers();
        long[][] latencies = new long[writers][transactionsPerWriter];
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        List<Future<?>> futures = new ArrayList<>(writers);
        try {
            for (int writer = 0; writer < writers; writer++) {
                int writerId = writer;
                futures.add(executor.submit(() -> runWriter(
                        environment,
                        scenario,
                        writerId,
                        transactionsPerWriter,
                        insertBase,
                        latencies[writerId],
                        ready,
                        start,
                        failure)));
            }
            if (!ready.await(30L, TimeUnit.SECONDS)) {
                Throwable workerFailure = failure.get();
                if (workerFailure != null) {
                    throw new IllegalStateException(
                            "concurrent commit worker failed during setup for " + scenario,
                            workerFailure);
                }
                throw new IllegalStateException("writers did not become ready for " + scenario);
            }
            Throwable setupFailure = failure.get();
            if (setupFailure != null) {
                throw new IllegalStateException(
                        "concurrent commit worker failed during setup for " + scenario,
                        setupFailure);
            }
            long started = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            long elapsed = System.nanoTime() - started;
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                throw new IllegalStateException("concurrent commit worker failed for " + scenario, workerFailure);
            }
            long[] flattened = Arrays.stream(latencies).flatMapToLong(Arrays::stream).toArray();
            return new RoundResult(flattened, elapsed);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30L, TimeUnit.SECONDS);
        }
    }

    private static void runWriter(
            ScenarioEnvironment environment,
            Scenario scenario,
            int writerId,
            int transactionCount,
            long insertBase,
            long[] latencies,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> firstFailure) {
        boolean setupSignalled = false;
        try (Connection connection = DriverManager.getConnection(environment.jdbcUrl(writerId))) {
            connection.setAutoCommit(false);
            String table = environment.tableName(writerId);
            String sql = scenario.operation() == Operation.INSERT
                    ? "insert into " + table + " (id, owner_id, value, payload) values (?, ?, ?, ?)"
                    : "update " + table + " set value = ?, payload = ? where id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ready.countDown();
                setupSignalled = true;
                start.await();
                for (int transaction = 0; transaction < transactionCount; transaction++) {
                    prepareBatch(statement, scenario, writerId, transaction, insertBase);
                    int[] counts = statement.executeBatch();
                    requireUpdatedRows(counts, scenario.rowsPerTransaction());
                    long commitStarted = System.nanoTime();
                    connection.commit();
                    latencies[transaction] = System.nanoTime() - commitStarted;
                }
            } catch (Throwable failure) {
                rollbackSuppressing(connection, failure);
                firstFailure.compareAndSet(null, failure);
                throw unchecked("writer " + writerId + " failed", failure);
            }
        } catch (Throwable failure) {
            firstFailure.compareAndSet(null, failure);
            if (!setupSignalled) {
                ready.countDown();
            }
            throw unchecked("writer " + writerId + " failed", failure);
        }
    }

    private static void prepareBatch(
            PreparedStatement statement,
            Scenario scenario,
            int writerId,
            int transaction,
            long insertBase) throws SQLException {
        statement.clearBatch();
        for (int row = 0; row < scenario.rowsPerTransaction(); row++) {
            if (scenario.operation() == Operation.INSERT) {
                long id = insertId(insertBase, writerId, transaction, scenario.rowsPerTransaction(), row);
                statement.setLong(1, id);
                statement.setInt(2, writerId);
                statement.setInt(3, transaction);
                statement.setString(4, payload(writerId, transaction, row));
            } else {
                statement.setInt(1, transaction + 1);
                statement.setString(2, payload(writerId, transaction, row));
                statement.setLong(3, updateId(writerId, row));
            }
            statement.addBatch();
        }
    }

    private static void requireUpdatedRows(int[] counts, int expected) {
        if (counts.length != expected) {
            throw new IllegalStateException("expected " + expected + " batch results, found " + counts.length);
        }
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("unexpected batch update count: " + count);
            }
        }
    }

    private static long insertId(long phaseBase, int writerId, int transaction, int rows, int row) {
        return phaseBase
                + writerId * 10_000_000L
                + transaction * (long) rows
                + row;
    }

    private static long updateId(int writerId, int row) {
        return writerId * 100_000L + row + 1L;
    }

    private static String payload(int writerId, int transaction, int row) {
        return "writer=" + writerId + ";tx=" + transaction + ";row=" + row;
    }

    private static void enableCurrentJfrEvents(Recording recording) {
        recording.enable(FILE_WRITE_EVENT).withThreshold(Duration.ZERO);
        recording.enable(MONITOR_ENTER_EVENT).withThreshold(Duration.ZERO);
        recording.enable(THREAD_PARK_EVENT).withThreshold(Duration.ZERO);
        recording.enable(GC_PAUSE_EVENT).withThreshold(Duration.ZERO);
    }

    private static JfrMetrics readJfrMetrics(Path recordingFile) throws IOException {
        long fileWriteCount = 0L;
        long fileWriteBytes = 0L;
        long fileWriteNanos = 0L;
        long monitorEnterCount = 0L;
        long monitorEnterNanos = 0L;
        long threadParkCount = 0L;
        long threadParkNanos = 0L;
        long gcPauseCount = 0L;
        long gcPauseNanos = 0L;
        try (RecordingFile recording = new RecordingFile(recordingFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String eventName = event.getEventType().getName();
                switch (eventName) {
                    case FILE_WRITE_EVENT -> {
                        fileWriteCount++;
                        fileWriteNanos += event.getDuration().toNanos();
                        if (event.hasField("bytesWritten")) {
                            fileWriteBytes += event.getLong("bytesWritten");
                        }
                    }
                    case MONITOR_ENTER_EVENT -> {
                        monitorEnterCount++;
                        monitorEnterNanos += event.getDuration().toNanos();
                    }
                    case THREAD_PARK_EVENT -> {
                        threadParkCount++;
                        threadParkNanos += event.getDuration().toNanos();
                    }
                    case GC_PAUSE_EVENT -> {
                        gcPauseCount++;
                        gcPauseNanos += event.getDuration().toNanos();
                    }
                    default -> {
                        // Only explicitly enabled events are summarized.
                    }
                }
            }
        }
        return new JfrMetrics(
                fileWriteCount,
                fileWriteBytes,
                fileWriteNanos,
                monitorEnterCount,
                monitorEnterNanos,
                threadParkCount,
                threadParkNanos,
                gcPauseCount,
                gcPauseNanos);
    }

    private static void writeCsv(Path path, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println(Result.csvHeader());
            for (Result result : results) {
                writer.println(result.csvLine());
            }
        }
    }

    private static void writeJson(Path path, Config config, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println("{");
            writer.println("  \"generatedAt\": \"" + jsonEscape(Instant.now().toString()) + "\",");
            writer.println("  \"javaVersion\": \"" + jsonEscape(System.getProperty("java.version")) + "\",");
            writer.println("  \"transactionsPerWriter\": " + config.transactionsPerWriter() + ',');
            writer.println("  \"warmupTransactionsPerWriter\": "
                    + config.warmupTransactionsPerWriter() + ',');
            writer.println("  \"databaseRoot\": \""
                    + jsonEscape(config.databaseRoot().toString()) + "\",");
            writer.println("  \"results\": [");
            for (int index = 0; index < results.size(); index++) {
                writer.print(results.get(index).json("    "));
                writer.println(index + 1 == results.size() ? "" : ",");
            }
            writer.println("  ]");
            writer.println("}");
        }
    }

    private static void writeHuman(Path path, Config config, List<Result> results) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.println("DelosDB concurrent commit benchmark");
            writer.println("Java: " + System.getProperty("java.version"));
            writer.println("Transactions per writer: " + config.transactionsPerWriter());
            writer.println("Warmup transactions per writer: " + config.warmupTransactionsPerWriter());
            writer.println("Database root: " + config.databaseRoot());
            writer.println();
            for (Result result : results) {
                writer.println(result.humanLine());
            }
        }
    }

    private static RuntimeException unchecked(String message, Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new IllegalStateException(message, failure);
    }

    private static void rollbackSuppressing(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    enum Topology {
        SAME_TABLE("same-table"),
        DIFFERENT_TABLES("different-tables"),
        DIFFERENT_DATABASES("different-databases");

        private final String propertyValue;

        Topology(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        String propertyValue() {
            return propertyValue;
        }

        static Topology parse(String value) {
            for (Topology topology : values()) {
                if (topology.propertyValue.equals(value)) {
                    return topology;
                }
            }
            throw new IllegalArgumentException("unsupported concurrent commit topology: " + value);
        }
    }

    enum Operation {
        INSERT("insert"),
        UPDATE("update");

        private final String propertyValue;

        Operation(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        String propertyValue() {
            return propertyValue;
        }

        static Operation parse(String value) {
            for (Operation operation : values()) {
                if (operation.propertyValue.equals(value)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("unsupported concurrent commit operation: " + value);
        }
    }

    record Scenario(Topology topology, Operation operation, int writers, int rowsPerTransaction) {
        Scenario {
            Objects.requireNonNull(topology, "topology");
            Objects.requireNonNull(operation, "operation");
            if (writers < 1) {
                throw new IllegalArgumentException("writers must be positive: " + writers);
            }
            if (rowsPerTransaction < 1) {
                throw new IllegalArgumentException("rowsPerTransaction must be positive: " + rowsPerTransaction);
            }
        }

        String fileStem() {
            return topology.propertyValue() + '-' + operation.propertyValue()
                    + "-w" + writers + "-r" + rowsPerTransaction;
        }
    }

    record Config(
            List<Integer> writers,
            List<Topology> topologies,
            List<Operation> operations,
            List<Integer> rowsPerTransaction,
            int transactionsPerWriter,
            int warmupTransactionsPerWriter,
            Path outputDirectory,
            Path databaseRoot,
            boolean keepJfr) {
        static Config fromSystemProperties() {
            return new Config(
                    positiveIntegers("delosdb.concurrentCommit.writers", "1,2,4,8,16"),
                    strings("delosdb.concurrentCommit.topologies",
                            "same-table,different-tables,different-databases").stream()
                            .map(Topology::parse)
                            .toList(),
                    strings("delosdb.concurrentCommit.operations", "insert,update").stream()
                            .map(Operation::parse)
                            .toList(),
                    positiveIntegers("delosdb.concurrentCommit.rowsPerTransaction", "1,8"),
                    positiveInteger("delosdb.concurrentCommit.transactionsPerWriter", 20),
                    nonNegativeInteger("delosdb.concurrentCommit.warmupTransactionsPerWriter", 2),
                    Path.of(System.getProperty(
                            "delosdb.concurrentCommit.outputDirectory",
                            "build/reports/concurrent-commit")).toAbsolutePath(),
                    Path.of(System.getProperty(
                            "delosdb.concurrentCommit.databaseRoot",
                            "build/concurrent-commit-databases")).toAbsolutePath(),
                    booleanProperty("delosdb.concurrentCommit.keepJfr", true));
        }

        private static List<Integer> positiveIntegers(String name, String defaults) {
            List<Integer> values = strings(name, defaults).stream()
                    .map(value -> parseInteger(name, value))
                    .toList();
            for (int value : values) {
                if (value < 1) {
                    throw new IllegalArgumentException(name + " values must be positive: " + value);
                }
            }
            return values;
        }

        private static int positiveInteger(String name, int defaultValue) {
            int value = parseInteger(name, System.getProperty(name, Integer.toString(defaultValue)));
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be positive: " + value);
            }
            return value;
        }

        private static int nonNegativeInteger(String name, int defaultValue) {
            int value = parseInteger(name, System.getProperty(name, Integer.toString(defaultValue)));
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative: " + value);
            }
            return value;
        }

        private static boolean booleanProperty(String name, boolean defaultValue) {
            String value = System.getProperty(name, Boolean.toString(defaultValue)).trim();
            if ("true".equalsIgnoreCase(value)) {
                return true;
            }
            if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false: " + value);
        }

        private static int parseInteger(String name, String value) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(name + " must contain integers: " + value, failure);
            }
        }

        private static List<String> strings(String name, String defaults) {
            String raw = System.getProperty(name, defaults);
            Set<String> values = new LinkedHashSet<>();
            for (String value : raw.split(",")) {
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty()) {
                    values.add(normalized);
                }
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException(name + " must contain at least one value");
            }
            return List.copyOf(values);
        }
    }

    static final class ScenarioEnvironment implements AutoCloseable {
        private final Scenario scenario;
        private final List<String> jdbcUrls;

        private ScenarioEnvironment(Scenario scenario, List<String> jdbcUrls) {
            this.scenario = scenario;
            this.jdbcUrls = jdbcUrls;
        }

        static ScenarioEnvironment create(Path root, Scenario scenario) throws SQLException {
            int databaseCount = scenario.topology() == Topology.DIFFERENT_DATABASES
                    ? scenario.writers()
                    : 1;
            List<String> urls = new ArrayList<>(databaseCount);
            for (int database = 0; database < databaseCount; database++) {
                String databaseName = root.resolve("database-" + database).toAbsolutePath().toString();
                String url = "jdbc:derby:" + databaseName;
                urls.add(url);
                try (Connection connection = DriverManager.getConnection(url + ";create=true")) {
                    connection.setAutoCommit(false);
                    int tableCount = scenario.topology() == Topology.DIFFERENT_TABLES
                            ? scenario.writers()
                            : 1;
                    for (int table = 0; table < tableCount; table++) {
                        createTable(connection, TABLE_PREFIX + table);
                    }
                    connection.commit();
                }
            }
            return new ScenarioEnvironment(scenario, List.copyOf(urls));
        }

        String jdbcUrl(int writerId) {
            return scenario.topology() == Topology.DIFFERENT_DATABASES
                    ? jdbcUrls.get(writerId)
                    : jdbcUrls.get(0);
        }

        String tableName(int writerId) {
            return scenario.topology() == Topology.DIFFERENT_TABLES
                    ? TABLE_PREFIX + writerId
                    : TABLE_PREFIX + 0;
        }

        void seedUpdateRows() throws SQLException {
            for (int writer = 0; writer < scenario.writers(); writer++) {
                try (Connection connection = DriverManager.getConnection(jdbcUrl(writer))) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into " + tableName(writer)
                                    + " (id, owner_id, value, payload) values (?, ?, ?, ?)")) {
                        for (int row = 0; row < scenario.rowsPerTransaction(); row++) {
                            insert.setLong(1, updateId(writer, row));
                            insert.setInt(2, writer);
                            insert.setInt(3, 0);
                            insert.setString(4, "seed");
                            insert.addBatch();
                        }
                        requireUpdatedRows(insert.executeBatch(), scenario.rowsPerTransaction());
                    }
                    connection.commit();
                }
            }
        }

        void verify(int warmupTransactions, int measuredTransactions) throws SQLException {
            long actual = 0L;
            for (int database = 0; database < jdbcUrls.size(); database++) {
                try (Connection connection = DriverManager.getConnection(jdbcUrls.get(database))) {
                    int tableCount = scenario.topology() == Topology.DIFFERENT_TABLES
                            ? scenario.writers()
                            : 1;
                    for (int table = 0; table < tableCount; table++) {
                        String tableName = TABLE_PREFIX + table;
                        try (Statement statement = connection.createStatement();
                             ResultSet rows = statement.executeQuery(
                                     "select count(*) from " + tableName)) {
                            if (!rows.next()) {
                                throw new IllegalStateException("count query returned no row");
                            }
                            actual += rows.getLong(1);
                        }
                        if (scenario.operation() == Operation.UPDATE) {
                            verifyUpdatedRows(connection, tableName, measuredTransactions);
                        }
                    }
                }
            }
            long expected = scenario.operation() == Operation.INSERT
                    ? (long) scenario.writers()
                            * (warmupTransactions + measuredTransactions)
                            * scenario.rowsPerTransaction()
                    : (long) scenario.writers() * scenario.rowsPerTransaction();
            if (actual != expected) {
                throw new IllegalStateException("semantic row-count mismatch for " + scenario
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }

        private void verifyUpdatedRows(
                Connection connection,
                String tableName,
                int measuredTransactions) throws SQLException {
            int verified = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select id, owner_id, value, payload from " + tableName)) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    int writer = rows.getInt(2);
                    int row = Math.toIntExact(id - writer * 100_000L - 1L);
                    if (writer < 0 || writer >= scenario.writers()
                            || row < 0 || row >= scenario.rowsPerTransaction()) {
                        throw new IllegalStateException("unexpected update fixture identity: id="
                                + id + ", writer=" + writer + ", row=" + row);
                    }
                    int value = rows.getInt(3);
                    String payload = rows.getString(4);
                    String expectedPayload = payload(writer, measuredTransactions - 1, row);
                    if (value != measuredTransactions || !expectedPayload.equals(payload)) {
                        throw new IllegalStateException("update fixture mismatch for id " + id
                                + ": value=" + value + ", payload=" + payload
                                + ", expectedValue=" + measuredTransactions
                                + ", expectedPayload=" + expectedPayload);
                    }
                    verified++;
                }
            }
            int expected = scenario.topology() == Topology.SAME_TABLE
                    ? scenario.writers() * scenario.rowsPerTransaction()
                    : scenario.rowsPerTransaction();
            if (verified != expected) {
                throw new IllegalStateException("update fixture verification count mismatch for "
                        + tableName + ": expected=" + expected + ", actual=" + verified);
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            for (String url : jdbcUrls) {
                try {
                    DriverManager.getConnection(url + ";shutdown=true");
                } catch (SQLException expectedShutdown) {
                    if (!"08006".equals(expectedShutdown.getSQLState())) {
                        if (failure == null) {
                            failure = expectedShutdown;
                        } else {
                            failure.addSuppressed(expectedShutdown);
                        }
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static void createTable(Connection connection, String table) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("create table " + table
                        + " (id bigint not null primary key, owner_id int not null,"
                        + " value int not null, payload varchar(128) not null) using delos_mvcc");
            }
        }
    }

    record RoundResult(long[] commitLatenciesNanos, long elapsedNanos) {
        RoundResult {
            commitLatenciesNanos = commitLatenciesNanos.clone();
            if (elapsedNanos <= 0L) {
                throw new IllegalArgumentException("elapsedNanos must be positive");
            }
        }
    }

    record JfrMetrics(
            long fileWriteCount,
            long fileWriteBytes,
            long fileWriteNanos,
            long monitorEnterCount,
            long monitorEnterNanos,
            long threadParkCount,
            long threadParkNanos,
            long gcPauseCount,
            long gcPauseNanos) {
    }

    record Result(
            Scenario scenario,
            long commits,
            double commitsPerSecond,
            long averageCommitNanos,
            long p50CommitNanos,
            long p95CommitNanos,
            long p99CommitNanos,
            long maxCommitNanos,
            JfrMetrics jfr) {
        static Result from(Scenario scenario, RoundResult round, JfrMetrics jfr) {
            long[] sorted = round.commitLatenciesNanos().clone();
            Arrays.sort(sorted);
            long commits = sorted.length;
            long totalCommitNanos = 0L;
            for (long latency : sorted) {
                totalCommitNanos += latency;
            }
            double seconds = round.elapsedNanos() / 1_000_000_000.0d;
            return new Result(
                    scenario,
                    commits,
                    commits / seconds,
                    commits == 0L ? 0L : totalCommitNanos / commits,
                    percentile(sorted, 0.50d),
                    percentile(sorted, 0.95d),
                    percentile(sorted, 0.99d),
                    sorted.length == 0 ? 0L : sorted[sorted.length - 1],
                    jfr);
        }

        static String csvHeader() {
            return "topology,operation,writers,rowsPerTransaction,commits,commitsPerSecond,"
                    + "avgCommitMicros,p50CommitMicros,p95CommitMicros,p99CommitMicros,maxCommitMicros,"
                    + "fileWriteEvents,fileWriteBytes,fileWriteMicros,"
                    + "monitorEnterEvents,monitorEnterMicros,threadParkEvents,threadParkMicros,"
                    + "gcPauseEvents,gcPauseMicros";
        }

        String csvLine() {
            return String.join(",",
                    scenario.topology().propertyValue(),
                    scenario.operation().propertyValue(),
                    Integer.toString(scenario.writers()),
                    Integer.toString(scenario.rowsPerTransaction()),
                    Long.toString(commits),
                    decimal(commitsPerSecond),
                    decimal(micros(averageCommitNanos)),
                    decimal(micros(p50CommitNanos)),
                    decimal(micros(p95CommitNanos)),
                    decimal(micros(p99CommitNanos)),
                    decimal(micros(maxCommitNanos)),
                    Long.toString(jfr.fileWriteCount()),
                    Long.toString(jfr.fileWriteBytes()),
                    decimal(micros(jfr.fileWriteNanos())),
                    Long.toString(jfr.monitorEnterCount()),
                    decimal(micros(jfr.monitorEnterNanos())),
                    Long.toString(jfr.threadParkCount()),
                    decimal(micros(jfr.threadParkNanos())),
                    Long.toString(jfr.gcPauseCount()),
                    decimal(micros(jfr.gcPauseNanos())));
        }

        String humanLine() {
            return scenario.fileStem()
                    + " commits/s=" + decimal(commitsPerSecond)
                    + " avg=" + decimal(micros(averageCommitNanos)) + "us"
                    + " p95=" + decimal(micros(p95CommitNanos)) + "us"
                    + " p99=" + decimal(micros(p99CommitNanos)) + "us"
                    + " fileWrites=" + jfr.fileWriteCount()
                    + " fileBytes=" + jfr.fileWriteBytes()
                    + " monitorWait=" + decimal(micros(jfr.monitorEnterNanos())) + "us"
                    + " park=" + decimal(micros(jfr.threadParkNanos())) + "us"
                    + " gcPause=" + decimal(micros(jfr.gcPauseNanos())) + "us";
        }

        String json(String indent) {
            String next = indent + "  ";
            return indent + "{\n"
                    + next + "\"topology\": \"" + scenario.topology().propertyValue() + "\",\n"
                    + next + "\"operation\": \"" + scenario.operation().propertyValue() + "\",\n"
                    + next + "\"writers\": " + scenario.writers() + ",\n"
                    + next + "\"rowsPerTransaction\": " + scenario.rowsPerTransaction() + ",\n"
                    + next + "\"commits\": " + commits + ",\n"
                    + next + "\"commitsPerSecond\": " + decimal(commitsPerSecond) + ",\n"
                    + next + "\"averageCommitNanos\": " + averageCommitNanos + ",\n"
                    + next + "\"p50CommitNanos\": " + p50CommitNanos + ",\n"
                    + next + "\"p95CommitNanos\": " + p95CommitNanos + ",\n"
                    + next + "\"p99CommitNanos\": " + p99CommitNanos + ",\n"
                    + next + "\"maxCommitNanos\": " + maxCommitNanos + ",\n"
                    + next + "\"fileWriteEvents\": " + jfr.fileWriteCount() + ",\n"
                    + next + "\"fileWriteBytes\": " + jfr.fileWriteBytes() + ",\n"
                    + next + "\"fileWriteNanos\": " + jfr.fileWriteNanos() + ",\n"
                    + next + "\"monitorEnterEvents\": " + jfr.monitorEnterCount() + ",\n"
                    + next + "\"monitorEnterNanos\": " + jfr.monitorEnterNanos() + ",\n"
                    + next + "\"threadParkEvents\": " + jfr.threadParkCount() + ",\n"
                    + next + "\"threadParkNanos\": " + jfr.threadParkNanos() + ",\n"
                    + next + "\"gcPauseEvents\": " + jfr.gcPauseCount() + ",\n"
                    + next + "\"gcPauseNanos\": " + jfr.gcPauseNanos() + "\n"
                    + indent + "}";
        }

        private static long percentile(long[] sorted, double percentile) {
            if (sorted.length == 0) {
                return 0L;
            }
            int index = (int) Math.ceil(percentile * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        }

        private static double micros(double nanos) {
            return nanos / 1_000.0d;
        }

        private static String decimal(double value) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
    }

}
