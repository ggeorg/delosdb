/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.V1BaselineCaptureTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.iapi.store.types.DelosDatabaseCommitTimingSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in production-closeout capture lane for the corrected Phase 8 v1 baseline.
 *
 * <p>The test writes raw JSON and CSV evidence. It deliberately asserts only
 * semantic correctness and bounded completion; timing values are evidence and
 * never correctness thresholds.</p>
 */
public final class V1BaselineCaptureTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.v1Baseline.";
    private static final int SCHEMA_VERSION = 2;

    public void testCaptureProductionCloseoutV1Baseline() throws Exception {
        Options options = Options.fromSystemProperties();
        deleteRecursively(options.databaseRoot());
        deleteRecursively(options.reportDirectory());
        Files.createDirectories(options.databaseRoot());
        Files.createDirectories(options.reportDirectory());

        List<WriterMeasurement> writerMeasurements = new ArrayList<>();
        for (Provider provider : Provider.values()) {
            for (Topology topology : Topology.values()) {
                for (Workload workload : Workload.values()) {
                    for (int writers : options.writerCounts()) {
                        writerMeasurements.add(runWriterCase(
                                options, provider, topology, workload, writers));
                    }
                }
            }
        }

        List<LifecycleMeasurement> lifecycleMeasurements = new ArrayList<>();
        for (Provider provider : Provider.values()) {
            lifecycleMeasurements.add(runLifecycleCase(options, provider));
        }

        BackupMeasurement backupMeasurement = runBackupStallCase(options);
        OverheadMeasurement overheadMeasurement = runDefaultOverheadCase(options);
        DecisionPublicationMeasurement decisionPublicationMeasurement =
                runDecisionPublicationCase(options);

        String semanticDigest = semanticDigest(
                writerMeasurements,
                lifecycleMeasurements,
                backupMeasurement,
                overheadMeasurement,
                decisionPublicationMeasurement);
        writeCsv(options.reportDirectory().resolve("v1-baseline-writer-matrix.csv"), writerMeasurements);
        writeJson(
                options.reportDirectory().resolve("v1-baseline-operational-results.json"),
                options,
                writerMeasurements,
                lifecycleMeasurements,
                backupMeasurement,
                overheadMeasurement,
                decisionPublicationMeasurement,
                semanticDigest);
        writeSummary(
                options.reportDirectory().resolve("v1-baseline-summary.txt"),
                options,
                writerMeasurements,
                lifecycleMeasurements,
                backupMeasurement,
                overheadMeasurement,
                decisionPublicationMeasurement,
                semanticDigest);

        assertEquals("writer matrix cell count",
                Provider.values().length
                        * Topology.values().length
                        * Workload.values().length
                        * options.writerCounts().size(),
                writerMeasurements.size());
        assertTrue("baseline semantic digest must be SHA-256",
                semanticDigest.matches("[0-9a-f]{64}"));
    }

    private static WriterMeasurement runWriterCase(
            Options options,
            Provider provider,
            Topology topology,
            Workload workload,
            int writers) throws Exception {
        String caseId = provider.id + "-" + topology.id + "-" + workload.id + "-w" + writers;
        Path caseRoot = options.databaseRoot().resolve("writer-matrix").resolve(caseId);
        deleteRecursively(caseRoot);
        Files.createDirectories(caseRoot);

        CaseLayout layout = createCaseLayout(caseRoot, provider, topology, workload, writers);
        List<Long> commitLatencies = Collections.synchronizedList(new ArrayList<>());
        List<Long> transactionLatencies = Collections.synchronizedList(new ArrayList<>());
        ResourceSampler sampler = new ResourceSampler();
        sampler.start();
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            runWriters(options, layout, workload, writers, commitLatencies, transactionLatencies);
        } catch (Throwable t) {
            failure = t;
        }
        long elapsed = System.nanoTime() - started;
        ResourceHighWater highWater = sampler.stopAndSnapshot();
        if (failure != null) {
            throwFailure(failure);
        }

        List<String> canonicalState = canonicalState(layout);
        String stateDigest = sha256(canonicalState);
        long expectedRows = expectedRows(options, workload, writers);
        long actualRows = canonicalState.stream()
                .mapToLong(V1BaselineCaptureTest::rowCountFromCanonicalState)
                .sum();
        assertEquals("row count for " + caseId, expectedRows, actualRows);

        shutdownLayout(layout);
        long physicalBytes = directoryBytes(caseRoot);
        return new WriterMeasurement(
                provider.id,
                topology.id,
                workload.id,
                writers,
                options.transactionsPerWriter(),
                options.rowsPerTransaction(),
                elapsed,
                percentile(commitLatencies, 50),
                percentile(commitLatencies, 95),
                average(commitLatencies),
                max(commitLatencies),
                percentile(transactionLatencies, 50),
                percentile(transactionLatencies, 95),
                average(transactionLatencies),
                expectedRows,
                physicalBytes,
                highWater.heapBytes(),
                highWater.threadCount(),
                stateDigest);
    }

    private static CaseLayout createCaseLayout(
            Path caseRoot,
            Provider provider,
            Topology topology,
            Workload workload,
            int writers) throws Exception {
        List<DatabaseLayout> databases = new ArrayList<>();
        if (topology == Topology.DIFFERENT_DATABASES) {
            for (int writer = 0; writer < writers; writer++) {
                Path database = caseRoot.resolve("db-" + writer);
                databases.add(createDatabase(database, provider, List.of("T0")));
            }
        } else {
            List<String> tables = new ArrayList<>();
            int tableCount = topology == Topology.SAME_TABLE ? 1 : writers;
            for (int table = 0; table < tableCount; table++) {
                tables.add("T" + table);
            }
            databases.add(createDatabase(caseRoot.resolve("db"), provider, tables));
        }
        CaseLayout layout = new CaseLayout(provider, topology, databases);
        if (workload == Workload.MIXED_INSERT_UPDATE) {
            seedMixedRows(layout, writers);
        }
        return layout;
    }

    private static DatabaseLayout createDatabase(
            Path database,
            Provider provider,
            List<String> tables) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath() + ";create=true")) {
            connection.setAutoCommit(false);
            for (String table : tables) {
                executeUpdate(connection,
                        "create table " + table
                                + " (id bigint primary key, owner_id int not null, value bigint not null)"
                                + provider.createSuffix);
            }
            connection.commit();
        }
        return new DatabaseLayout(database, tables);
    }

    private static void seedMixedRows(CaseLayout layout, int writers) throws Exception {
        Map<Path, Connection> connections = new LinkedHashMap<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                DatabaseTarget target = target(layout, writer);
                Connection connection = connections.get(target.database());
                if (connection == null) {
                    connection = DriverManager.getConnection(
                            "jdbc:derby:" + target.database().toAbsolutePath());
                    connection.setAutoCommit(false);
                    connections.put(target.database(), connection);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into " + target.table() + " values (?, ?, 0)")) {
                    statement.setLong(1, seedId(writer));
                    statement.setInt(2, writer);
                    statement.executeUpdate();
                }
            }
            for (Connection connection : connections.values()) {
                connection.commit();
            }
        } finally {
            Throwable failure = null;
            for (Connection connection : connections.values()) {
                try {
                    connection.close();
                } catch (Throwable closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throwFailure(failure);
            }
        }
    }

    private static void runWriters(
            Options options,
            CaseLayout layout,
            Workload workload,
            int writers,
            List<Long> commitLatencies,
            List<Long> transactionLatencies) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                final int writerId = writer;
                futures.add(executor.submit(() -> {
                    DatabaseTarget target = target(layout, writerId);
                    try (Connection connection = DriverManager.getConnection(
                            "jdbc:derby:" + target.database().toAbsolutePath())) {
                        connection.setAutoCommit(false);
                        ready.countDown();
                        if (!start.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("writer start barrier timed out");
                        }
                        for (int transaction = 0;
                             transaction < options.transactionsPerWriter();
                             transaction++) {
                            long transactionStarted = System.nanoTime();
                            applyWorkload(
                                    connection,
                                    target.table(),
                                    workload,
                                    writerId,
                                    transaction,
                                    options.rowsPerTransaction());
                            long commitStarted = System.nanoTime();
                            connection.commit();
                            commitLatencies.add(System.nanoTime() - commitStarted);
                            transactionLatencies.add(System.nanoTime() - transactionStarted);
                        }
                    }
                    return null;
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("writer readiness barrier timed out");
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(options.caseTimeoutSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static void applyWorkload(
            Connection connection,
            String table,
            Workload workload,
            int writer,
            int transaction,
            int rowsPerTransaction) throws SQLException {
        switch (workload) {
        case SINGLE_ROW_INSERT:
            insertRows(connection, table, writer, transaction, 1);
            break;
        case MULTI_ROW_INSERT:
            insertRows(connection, table, writer, transaction, rowsPerTransaction);
            break;
        case MIXED_INSERT_UPDATE:
            try (PreparedStatement update = connection.prepareStatement(
                    "update " + table + " set value = value + 1 where id = ?")) {
                update.setLong(1, seedId(writer));
                if (update.executeUpdate() != 1) {
                    throw new SQLException("mixed workload seed row was not updated");
                }
            }
            insertRows(connection, table, writer, transaction, Math.max(1, rowsPerTransaction - 1));
            break;
        default:
            throw new IllegalStateException("unknown workload " + workload);
        }
    }

    private static void insertRows(
            Connection connection,
            String table,
            int writer,
            int transaction,
            int rows) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?)")) {
            for (int row = 0; row < rows; row++) {
                long id = insertedId(writer, transaction, row);
                insert.setLong(1, id);
                insert.setInt(2, writer);
                insert.setLong(3, transaction + row + 1L);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            if (counts.length != rows) {
                throw new SQLException("unexpected insert batch count");
            }
        }
    }

    private static List<String> canonicalState(CaseLayout layout) throws Exception {
        List<String> state = new ArrayList<>();
        for (DatabaseLayout database : layout.databases()) {
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + database.database().toAbsolutePath())) {
                for (String table : database.tables()) {
                    try (Statement statement = connection.createStatement();
                         ResultSet rs = statement.executeQuery(
                                 "select count(*), coalesce(sum(id), 0), coalesce(sum(value), 0) from " + table)) {
                        assertTrue("aggregate row expected", rs.next());
                        state.add(database.database().getFileName() + "/" + table
                                + "|" + rs.getLong(1)
                                + "|" + rs.getLong(2)
                                + "|" + rs.getLong(3));
                    }
                }
            }
        }
        state.sort(String::compareTo);
        return state;
    }

    private static LifecycleMeasurement runLifecycleCase(Options options, Provider provider)
            throws Exception {
        Path database = options.databaseRoot().resolve("lifecycle").resolve(provider.id);
        deleteRecursively(database);
        Files.createDirectories(database.getParent());
        runCrashWorker(provider, database, options.lifecycleRows());

        long recoveryStarted = System.nanoTime();
        String recoveryDigest;
        long recoveryQueryNanos;
        try (Connection connection = openDatabase(database.toAbsolutePath().toString(), false)) {
            long queryStarted = System.nanoTime();
            recoveryDigest = aggregateDigest(connection, "T");
            recoveryQueryNanos = System.nanoTime() - queryStarted;
        }
        long recoveryOpenNanos = System.nanoTime() - recoveryStarted - recoveryQueryNanos;
        shutdownDatabase(database.toAbsolutePath().toString());

        long startupStarted = System.nanoTime();
        String startupDigest;
        long startupQueryNanos;
        try (Connection connection = openDatabase(database.toAbsolutePath().toString(), false)) {
            long queryStarted = System.nanoTime();
            startupDigest = aggregateDigest(connection, "T");
            startupQueryNanos = System.nanoTime() - queryStarted;
        }
        long cleanStartupNanos = System.nanoTime() - startupStarted - startupQueryNanos;
        assertEquals("recovery and clean-start state must match", recoveryDigest, startupDigest);
        shutdownDatabase(database.toAbsolutePath().toString());

        return new LifecycleMeasurement(
                provider.id,
                recoveryOpenNanos,
                recoveryQueryNanos,
                cleanStartupNanos,
                startupQueryNanos,
                directoryBytes(database),
                startupDigest);
    }

    private static void runCrashWorker(Provider provider, Path database, int rows) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                CrashSeedWorker.class.getName(),
                provider.name(),
                database.toAbsolutePath().toString(),
                Integer.toString(rows));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals("crash seed worker failed:\n" + output, 0, exitCode);
    }

    private static BackupMeasurement runBackupStallCase(Options options) throws Exception {
        Path database = options.databaseRoot().resolve("backup-stall").resolve("db");
        Path backupRoot = options.databaseRoot().resolve("backup-stall").resolve("backup");
        deleteRecursively(database.getParent());
        Files.createDirectories(database.getParent());

        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath() + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table H (id bigint primary key, value bigint not null)");
            executeUpdate(connection,
                    "create table M (id bigint primary key, value bigint not null) using delos_mvcc");
            connection.commit();
        }

        List<Long> commitLatencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> writer = executor.submit(() -> {
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:derby:" + database.toAbsolutePath())) {
                connection.setAutoCommit(false);
                writerStarted.countDown();
                for (int transaction = 0;
                     transaction < options.backupWriterTransactions();
                     transaction++) {
                    long id = transaction + 1L;
                    try (PreparedStatement heap = connection.prepareStatement(
                            "insert into H values (?, ?)");
                         PreparedStatement mvcc = connection.prepareStatement(
                            "insert into M values (?, ?)")) {
                        heap.setLong(1, id);
                        heap.setLong(2, id * 2L);
                        heap.executeUpdate();
                        mvcc.setLong(1, id);
                        mvcc.setLong(2, id * 3L);
                        mvcc.executeUpdate();
                    }
                    long commitStarted = System.nanoTime();
                    connection.commit();
                    commitLatencies.add(System.nanoTime() - commitStarted);
                    committed.incrementAndGet();
                    java.util.concurrent.locks.LockSupport.parkNanos(
                            TimeUnit.MILLISECONDS.toNanos(1L));
                }
            }
            return null;
        });

        assertTrue("backup writer did not start", writerStarted.await(30, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (committed.get() < options.backupStartAfterCommits() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse("backup writer finished before backup began", writer.isDone());
        long backupStarted = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath());
             PreparedStatement statement = connection.prepareStatement(
                     "call syscs_util.syscs_backup_database(?)")) {
            statement.setString(1, backupRoot.toAbsolutePath().toString());
            statement.execute();
        }
        long backupNanos = System.nanoTime() - backupStarted;
        writer.get(options.caseTimeoutSeconds(), TimeUnit.SECONDS);
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath())) {
            assertAggregate(connection, "H", options.backupWriterTransactions());
            assertAggregate(connection, "M", options.backupWriterTransactions());
        }
        shutdownDatabase(database.toAbsolutePath().toString());

        return new BackupMeasurement(
                backupNanos,
                percentile(commitLatencies, 50),
                percentile(commitLatencies, 95),
                max(commitLatencies),
                committed.get(),
                directoryBytes(backupRoot),
                sha256(List.of("committed=" + committed.get())));
    }

    private static OverheadMeasurement runDefaultOverheadCase(Options options) throws Exception {
        Path database = options.databaseRoot().resolve("default-overhead").resolve("db");
        deleteRecursively(database.getParent());
        Files.createDirectories(database.getParent());
        long defaultNanos;
        long profilingNanos;
        String digest;
        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath() + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table T (id int primary key, value int) using delos_mvcc");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into T values (?, ?)")) {
                for (int id = 1; id <= options.overheadRows(); id++) {
                    insert.setInt(1, id);
                    insert.setInt(2, id * 2);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            defaultNanos = timedQueries(connection, options.overheadQueries());
            executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
            profilingNanos = timedQueries(connection, options.overheadQueries());
            executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(0)");
            digest = aggregateDigest(connection, "T");
            connection.rollback();
        }
        shutdownDatabase(database.toAbsolutePath().toString());
        return new OverheadMeasurement(
                defaultNanos,
                profilingNanos,
                ratio(profilingNanos, defaultNanos),
                "test-only-unreachable",
                digest);
    }

    private static long timedQueries(Connection connection, int queries) throws SQLException {
        long started = System.nanoTime();
        try (PreparedStatement statement = connection.prepareStatement(
                "select sum(value) from T where id between ? and ?")) {
            for (int query = 0; query < queries; query++) {
                int start = 1 + (query % 50);
                statement.setInt(1, start);
                statement.setInt(2, start + 49);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("overhead query returned no aggregate");
                    }
                    rs.getLong(1);
                }
            }
        }
        return System.nanoTime() - started;
    }

    private static String aggregateDigest(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "select count(*), coalesce(sum(id), 0), coalesce(sum(value), 0) from " + table)) {
            assertTrue("aggregate row expected", rs.next());
            return sha256(List.of(rs.getLong(1) + "|" + rs.getLong(2) + "|" + rs.getLong(3)));
        }
    }

    private static void assertAggregate(Connection connection, String table, long expectedRows)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rs.next());
            assertEquals(expectedRows, rs.getLong(1));
        }
    }

    private static DatabaseTarget target(CaseLayout layout, int writer) {
        if (layout.topology() == Topology.DIFFERENT_DATABASES) {
            return new DatabaseTarget(layout.databases().get(writer).database(), "T0");
        }
        DatabaseLayout database = layout.databases().get(0);
        String table = layout.topology() == Topology.SAME_TABLE ? "T0" : "T" + writer;
        return new DatabaseTarget(database.database(), table);
    }

    private static long expectedRows(Options options, Workload workload, int writers) {
        int insertsPerTransaction = switch (workload) {
        case SINGLE_ROW_INSERT -> 1;
        case MULTI_ROW_INSERT -> options.rowsPerTransaction();
        case MIXED_INSERT_UPDATE -> Math.max(1, options.rowsPerTransaction() - 1);
        };
        long seedRows = workload == Workload.MIXED_INSERT_UPDATE ? writers : 0L;
        return seedRows
                + (long) writers * options.transactionsPerWriter() * insertsPerTransaction;
    }

    private static long seedId(int writer) {
        return writer + 1L;
    }

    private static long insertedId(int writer, int transaction, int row) {
        return 1_000_000_000L
                + writer * 10_000_000L
                + transaction * 10_000L
                + row;
    }

    private static long rowCountFromCanonicalState(String value) {
        String[] parts = value.split("\\|");
        return Long.parseLong(parts[1]);
    }

    private static void shutdownLayout(CaseLayout layout) throws SQLException {
        for (DatabaseLayout database : layout.databases()) {
            shutdownDatabase(database.database().toAbsolutePath().toString());
        }
    }

    private static long percentile(List<Long> values, int percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil((percentile / 100.0d) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long average(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (long value : values) {
            total = Math.addExact(total, value);
        }
        return total / values.size();
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0d : ((double) numerator) / denominator;
    }

    private static long directoryBytes(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            throw new UncheckedIoException(e);
                        }
                    })
                    .sum();
        } catch (UncheckedIoException e) {
            throw e.cause;
        }
    }

    private static DecisionPublicationMeasurement runDecisionPublicationCase(
            Options options) throws Exception {
        Path database = options.databaseRoot().resolve("decision-publication").resolve("db");
        deleteRecursively(database.getParent());
        Files.createDirectories(database.getParent());

        DelosStorageDiagnostics diagnostics = mvccDiagnostics(database);
        String digest;
        DelosDatabaseCommitTimingSnapshot timing;
        try (Connection connection = DriverManager.getConnection(
                "jdbc:derby:" + database.toAbsolutePath() + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table H (id int primary key, value int not null)");
            executeUpdate(connection,
                    "create table M (id int primary key, value int not null) using delos_mvcc");
            connection.commit();
            diagnostics.resetDatabaseCommitTimingForTesting();

            for (int transaction = 1; transaction <= options.decisionTransactions(); transaction++) {
                try (PreparedStatement heap = connection.prepareStatement(
                        "insert into H values (?, ?)");
                     PreparedStatement mvcc = connection.prepareStatement(
                        "insert into M values (?, ?)")) {
                    heap.setInt(1, transaction);
                    heap.setInt(2, transaction * 2);
                    heap.executeUpdate();
                    mvcc.setInt(1, transaction);
                    mvcc.setInt(2, transaction * 3);
                    mvcc.executeUpdate();
                }
                connection.commit();
            }

            timing = diagnostics.databaseCommitTimingSnapshotForTesting();
            assertEquals("raw decision-force sample count",
                    options.decisionTransactions(), timing.rawDecisionForceSamples());
            assertEquals("participant-publication sample count",
                    options.decisionTransactions(), timing.participantPublicationSamples());
            assertTrue("raw decision force must record elapsed time",
                    timing.rawDecisionForceTotalNanos() > 0L);
            assertTrue("participant publication must record elapsed time",
                    timing.participantPublicationTotalNanos() > 0L);

            List<String> canonical = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "select h.id, h.value, m.value from H h join M m on h.id = m.id order by h.id")) {
                while (rows.next()) {
                    canonical.add(rows.getInt(1) + "|" + rows.getInt(2) + "|" + rows.getInt(3));
                }
            }
            assertEquals("mixed decision row count", options.decisionTransactions(), canonical.size());
            digest = sha256(canonical);
        }
        shutdownDatabase(database.toAbsolutePath().toString());

        return new DecisionPublicationMeasurement(
                options.decisionTransactions(),
                timing.rawDecisionForceSamples(),
                timing.rawDecisionForceAverageNanos(),
                timing.rawDecisionForceMaxNanos(),
                timing.participantPublicationSamples(),
                timing.participantPublicationAverageNanos(),
                timing.participantPublicationMaxNanos(),
                digest);
    }

    private static String semanticDigest(
            List<WriterMeasurement> writerMeasurements,
            List<LifecycleMeasurement> lifecycleMeasurements,
            BackupMeasurement backupMeasurement,
            OverheadMeasurement overheadMeasurement,
            DecisionPublicationMeasurement decisionPublicationMeasurement) {
        List<String> canonical = new ArrayList<>();
        writerMeasurements.stream()
                .sorted(Comparator.comparing(WriterMeasurement::key))
                .forEach(value -> canonical.add(value.key() + "=" + value.semanticDigest()));
        lifecycleMeasurements.stream()
                .sorted(Comparator.comparing(LifecycleMeasurement::provider))
                .forEach(value -> canonical.add("lifecycle/" + value.provider()
                        + "=" + value.semanticDigest()));
        canonical.add("backup=" + backupMeasurement.semanticDigest());
        canonical.add("overhead=" + overheadMeasurement.semanticDigest());
        canonical.add("decision-publication="
                + decisionPublicationMeasurement.semanticDigest());
        return sha256(canonical);
    }

    private static String sha256(List<String> canonicalValues) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : canonicalValues) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeCsv(Path path, List<WriterMeasurement> values) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("provider,topology,workload,writers,transactionsPerWriter,rowsPerTransaction,")
                .append("elapsedNanos,commitP50Nanos,commitP95Nanos,commitAverageNanos,commitMaxNanos,")
                .append("transactionP50Nanos,transactionP95Nanos,transactionAverageNanos,")
                .append("logicalRows,physicalBytes,heapHighWaterBytes,threadHighWater,semanticDigest\n");
        for (WriterMeasurement value : values) {
            out.append(value.provider()).append(',')
                    .append(value.topology()).append(',')
                    .append(value.workload()).append(',')
                    .append(value.writers()).append(',')
                    .append(value.transactionsPerWriter()).append(',')
                    .append(value.rowsPerTransaction()).append(',')
                    .append(value.elapsedNanos()).append(',')
                    .append(value.commitP50Nanos()).append(',')
                    .append(value.commitP95Nanos()).append(',')
                    .append(value.commitAverageNanos()).append(',')
                    .append(value.commitMaxNanos()).append(',')
                    .append(value.transactionP50Nanos()).append(',')
                    .append(value.transactionP95Nanos()).append(',')
                    .append(value.transactionAverageNanos()).append(',')
                    .append(value.logicalRows()).append(',')
                    .append(value.physicalBytes()).append(',')
                    .append(value.heapHighWaterBytes()).append(',')
                    .append(value.threadHighWater()).append(',')
                    .append(value.semanticDigest()).append('\n');
        }
        Files.writeString(path, out, StandardCharsets.UTF_8);
    }

    private static void writeJson(
            Path path,
            Options options,
            List<WriterMeasurement> writers,
            List<LifecycleMeasurement> lifecycle,
            BackupMeasurement backup,
            OverheadMeasurement overhead,
            DecisionPublicationMeasurement decisionPublication,
            String semanticDigest) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("{\n")
                .append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n")
                .append("  \"captureId\": \"").append(json(options.captureId())).append("\",\n")
                .append("  \"environment\": {\n")
                .append("    \"javaVersion\": \"").append(json(System.getProperty("java.version"))).append("\",\n")
                .append("    \"javaVendor\": \"").append(json(System.getProperty("java.vendor"))).append("\",\n")
                .append("    \"vmName\": \"").append(json(System.getProperty("java.vm.name"))).append("\",\n")
                .append("    \"osName\": \"").append(json(System.getProperty("os.name"))).append("\",\n")
                .append("    \"osVersion\": \"").append(json(System.getProperty("os.version"))).append("\",\n")
                .append("    \"osArch\": \"").append(json(System.getProperty("os.arch"))).append("\",\n")
                .append("    \"processors\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n")
                .append("    \"maxHeapBytes\": ").append(Runtime.getRuntime().maxMemory()).append("\n")
                .append("  },\n")
                .append("  \"configuration\": {\n")
                .append("    \"writerCounts\": ").append(intArray(options.writerCounts())).append(",\n")
                .append("    \"transactionsPerWriter\": ").append(options.transactionsPerWriter()).append(",\n")
                .append("    \"rowsPerTransaction\": ").append(options.rowsPerTransaction()).append(",\n")
                .append("    \"lifecycleRows\": ").append(options.lifecycleRows()).append(",\n")
                .append("    \"decisionTransactions\": ")
                .append(options.decisionTransactions()).append("\n")
                .append("  },\n")
                .append("  \"semanticDigest\": \"").append(semanticDigest).append("\",\n")
                .append("  \"writerMatrix\": [\n");
        for (int index = 0; index < writers.size(); index++) {
            WriterMeasurement value = writers.get(index);
            out.append("    {")
                    .append("\"provider\":\"").append(value.provider()).append("\",")
                    .append("\"topology\":\"").append(value.topology()).append("\",")
                    .append("\"workload\":\"").append(value.workload()).append("\",")
                    .append("\"writers\":").append(value.writers()).append(',')
                    .append("\"elapsedNanos\":").append(value.elapsedNanos()).append(',')
                    .append("\"commitP50Nanos\":").append(value.commitP50Nanos()).append(',')
                    .append("\"commitP95Nanos\":").append(value.commitP95Nanos()).append(',')
                    .append("\"commitAverageNanos\":").append(value.commitAverageNanos()).append(',')
                    .append("\"commitMaxNanos\":").append(value.commitMaxNanos()).append(',')
                    .append("\"transactionP50Nanos\":").append(value.transactionP50Nanos()).append(',')
                    .append("\"transactionP95Nanos\":").append(value.transactionP95Nanos()).append(',')
                    .append("\"transactionAverageNanos\":").append(value.transactionAverageNanos()).append(',')
                    .append("\"logicalRows\":").append(value.logicalRows()).append(',')
                    .append("\"physicalBytes\":").append(value.physicalBytes()).append(',')
                    .append("\"heapHighWaterBytes\":").append(value.heapHighWaterBytes()).append(',')
                    .append("\"threadHighWater\":").append(value.threadHighWater()).append(',')
                    .append("\"semanticDigest\":\"").append(value.semanticDigest()).append("\"")
                    .append('}');
            out.append(index + 1 == writers.size() ? "\n" : ",\n");
        }
        out.append("  ],\n  \"lifecycle\": [\n");
        for (int index = 0; index < lifecycle.size(); index++) {
            LifecycleMeasurement value = lifecycle.get(index);
            out.append("    {")
                    .append("\"provider\":\"").append(value.provider()).append("\",")
                    .append("\"recoveryOpenNanos\":").append(value.recoveryOpenNanos()).append(',')
                    .append("\"recoveryQueryNanos\":").append(value.recoveryQueryNanos()).append(',')
                    .append("\"cleanStartupNanos\":").append(value.cleanStartupNanos()).append(',')
                    .append("\"cleanQueryNanos\":").append(value.cleanQueryNanos()).append(',')
                    .append("\"physicalBytes\":").append(value.physicalBytes()).append(',')
                    .append("\"semanticDigest\":\"").append(value.semanticDigest()).append("\"")
                    .append('}');
            out.append(index + 1 == lifecycle.size() ? "\n" : ",\n");
        }
        out.append("  ],\n")
                .append("  \"backup\": {")
                .append("\"durationNanos\":").append(backup.durationNanos()).append(',')
                .append("\"writerCommitP50Nanos\":").append(backup.writerCommitP50Nanos()).append(',')
                .append("\"writerCommitP95Nanos\":").append(backup.writerCommitP95Nanos()).append(',')
                .append("\"writerCommitMaxNanos\":").append(backup.writerCommitMaxNanos()).append(',')
                .append("\"committedTransactions\":").append(backup.committedTransactions()).append(',')
                .append("\"backupBytes\":").append(backup.backupBytes()).append(',')
                .append("\"semanticDigest\":\"").append(backup.semanticDigest()).append("\"},\n")
                .append("  \"defaultOverhead\": {")
                .append("\"profilingDisabledNanos\":").append(overhead.profilingDisabledNanos()).append(',')
                .append("\"profilingEnabledNanos\":").append(overhead.profilingEnabledNanos()).append(',')
                .append("\"enabledToDisabledRatio\":")
                .append(String.format(Locale.ROOT, "%.6f", overhead.enabledToDisabledRatio())).append(',')
                .append("\"failureControls\":\"").append(overhead.failureControls()).append("\",")
                .append("\"semanticDigest\":\"").append(overhead.semanticDigest()).append("\"},\n")
                .append("  \"decisionPublication\": {")
                .append("\"transactions\":").append(decisionPublication.transactions()).append(',')
                .append("\"rawDecisionForceSamples\":")
                .append(decisionPublication.rawDecisionForceSamples()).append(',')
                .append("\"rawDecisionForceAverageNanos\":")
                .append(decisionPublication.rawDecisionForceAverageNanos()).append(',')
                .append("\"rawDecisionForceMaxNanos\":")
                .append(decisionPublication.rawDecisionForceMaxNanos()).append(',')
                .append("\"participantPublicationSamples\":")
                .append(decisionPublication.participantPublicationSamples()).append(',')
                .append("\"participantPublicationAverageNanos\":")
                .append(decisionPublication.participantPublicationAverageNanos()).append(',')
                .append("\"participantPublicationMaxNanos\":")
                .append(decisionPublication.participantPublicationMaxNanos()).append(',')
                .append("\"semanticDigest\":\"")
                .append(decisionPublication.semanticDigest()).append("\"}\n")
                .append("}\n");
        Files.writeString(path, out, StandardCharsets.UTF_8);
    }

    private static void writeSummary(
            Path path,
            Options options,
            List<WriterMeasurement> writers,
            List<LifecycleMeasurement> lifecycle,
            BackupMeasurement backup,
            OverheadMeasurement overhead,
            DecisionPublicationMeasurement decisionPublication,
            String semanticDigest) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("DelosDB v1 production-closeout baseline capture\n")
                .append("schema: ").append(SCHEMA_VERSION).append('\n')
                .append("capture: ").append(options.captureId()).append('\n')
                .append("JDK: ").append(System.getProperty("java.version")).append('\n')
                .append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(' ')
                .append(System.getProperty("os.arch")).append('\n')
                .append("writer matrix cells: ").append(writers.size()).append('\n')
                .append("lifecycle cells: ").append(lifecycle.size()).append('\n')
                .append("backup duration nanos: ").append(backup.durationNanos()).append('\n')
                .append("backup writer max commit nanos: ").append(backup.writerCommitMaxNanos()).append('\n')
                .append("profiling enabled/disabled ratio: ")
                .append(String.format(Locale.ROOT, "%.6f", overhead.enabledToDisabledRatio())).append('\n')
                .append("raw decision-force average nanos: ")
                .append(decisionPublication.rawDecisionForceAverageNanos()).append('\n')
                .append("raw decision-force max nanos: ")
                .append(decisionPublication.rawDecisionForceMaxNanos()).append('\n')
                .append("participant publication average nanos: ")
                .append(decisionPublication.participantPublicationAverageNanos()).append('\n')
                .append("participant publication max nanos: ")
                .append(decisionPublication.participantPublicationMaxNanos()).append('\n')
                .append("semantic digest: ").append(semanticDigest).append('\n');
        Files.writeString(path, out, StandardCharsets.UTF_8);
    }

    private static String intArray(List<Integer> values) {
        return values.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path candidate : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("unexpected baseline failure", failure);
    }

    private enum Provider {
        HEAP("heap", ""),
        MVCC("delos_mvcc", " using delos_mvcc");

        private final String id;
        private final String createSuffix;

        Provider(String id, String createSuffix) {
            this.id = id;
            this.createSuffix = createSuffix;
        }
    }

    private enum Topology {
        SAME_TABLE("same-table"),
        DIFFERENT_TABLES("different-tables"),
        DIFFERENT_DATABASES("different-databases");

        private final String id;

        Topology(String id) {
            this.id = id;
        }
    }

    private enum Workload {
        SINGLE_ROW_INSERT("single-row-insert"),
        MULTI_ROW_INSERT("multi-row-insert"),
        MIXED_INSERT_UPDATE("mixed-insert-update");

        private final String id;

        Workload(String id) {
            this.id = id;
        }
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            String captureId,
            List<Integer> writerCounts,
            int transactionsPerWriter,
            int rowsPerTransaction,
            int lifecycleRows,
            int backupWriterTransactions,
            int backupStartAfterCommits,
            int overheadRows,
            int overheadQueries,
            int decisionTransactions,
            long caseTimeoutSeconds) {

        private static String required(String key) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required system property " + key);
            }
            return value;
        }

        private static int integer(String key, int fallback) {
            return Integer.parseInt(System.getProperty(key, Integer.toString(fallback)));
        }

        private static long longValue(String key, long fallback) {
            return Long.parseLong(System.getProperty(key, Long.toString(fallback)));
        }

        private static List<Integer> integerList(String key, String fallback) {
            return java.util.Arrays.stream(System.getProperty(key, fallback).split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Integer::parseInt)
                    .distinct()
                    .toList();
        }
        static Options fromSystemProperties() {
            List<Integer> writers = integerList(PREFIX + "writers", "1,2,4,8,16");
            if (!writers.equals(List.of(1, 2, 4, 8, 16))) {
                throw new IllegalArgumentException(
                        "The accepted v1 writer matrix must be exactly 1,2,4,8,16");
            }
            return new Options(
                    Path.of(required(PREFIX + "databaseRoot")),
                    Path.of(required(PREFIX + "reportDirectory")),
                    System.getProperty(PREFIX + "captureId", "phase8-v1-production-closeout"),
                    writers,
                    integer(PREFIX + "transactionsPerWriter", 8),
                    integer(PREFIX + "rowsPerTransaction", 4),
                    integer(PREFIX + "lifecycleRows", 1000),
                    integer(PREFIX + "backupWriterTransactions", 40),
                    integer(PREFIX + "backupStartAfterCommits", 5),
                    integer(PREFIX + "overheadRows", 1000),
                    integer(PREFIX + "overheadQueries", 500),
                    integer(PREFIX + "decisionTransactions", 32),
                    longValue(PREFIX + "caseTimeoutSeconds", 180L));
        }
    }

    private record DatabaseLayout(Path database, List<String> tables) {
        DatabaseLayout {
            Objects.requireNonNull(database, "database");
            tables = List.copyOf(tables);
        }
    }

    private record CaseLayout(Provider provider, Topology topology, List<DatabaseLayout> databases) {
        CaseLayout {
            databases = List.copyOf(databases);
        }
    }

    private record DatabaseTarget(Path database, String table) {
    }

    private record WriterMeasurement(
            String provider,
            String topology,
            String workload,
            int writers,
            int transactionsPerWriter,
            int rowsPerTransaction,
            long elapsedNanos,
            long commitP50Nanos,
            long commitP95Nanos,
            long commitAverageNanos,
            long commitMaxNanos,
            long transactionP50Nanos,
            long transactionP95Nanos,
            long transactionAverageNanos,
            long logicalRows,
            long physicalBytes,
            long heapHighWaterBytes,
            int threadHighWater,
            String semanticDigest) {
        String key() {
            return provider + "/" + topology + "/" + workload + "/" + writers;
        }
    }

    private record LifecycleMeasurement(
            String provider,
            long recoveryOpenNanos,
            long recoveryQueryNanos,
            long cleanStartupNanos,
            long cleanQueryNanos,
            long physicalBytes,
            String semanticDigest) {
    }

    private record BackupMeasurement(
            long durationNanos,
            long writerCommitP50Nanos,
            long writerCommitP95Nanos,
            long writerCommitMaxNanos,
            int committedTransactions,
            long backupBytes,
            String semanticDigest) {
    }

    private record OverheadMeasurement(
            long profilingDisabledNanos,
            long profilingEnabledNanos,
            double enabledToDisabledRatio,
            String failureControls,
            String semanticDigest) {
    }

    private record DecisionPublicationMeasurement(
            int transactions,
            long rawDecisionForceSamples,
            long rawDecisionForceAverageNanos,
            long rawDecisionForceMaxNanos,
            long participantPublicationSamples,
            long participantPublicationAverageNanos,
            long participantPublicationMaxNanos,
            String semanticDigest) {
    }

    private record ResourceHighWater(long heapBytes, int threadCount) {
    }

    private static final class ResourceSampler {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong heapHighWater = new AtomicLong();
        private final AtomicInteger threadHighWater = new AtomicInteger();
        private Thread sampler;

        void start() {
            running.set(true);
            sampler = new Thread(() -> {
                while (running.get()) {
                    heapHighWater.accumulateAndGet(
                            memory.getHeapMemoryUsage().getUsed(), Math::max);
                    threadHighWater.accumulateAndGet(threads.getThreadCount(), Math::max);
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "delos-v1-baseline-resource-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        ResourceHighWater stopAndSnapshot() throws InterruptedException {
            running.set(false);
            sampler.join(5_000L);
            heapHighWater.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
            threadHighWater.accumulateAndGet(threads.getThreadCount(), Math::max);
            return new ResourceHighWater(heapHighWater.get(), threadHighWater.get());
        }
    }

    private static final class UncheckedIoException extends RuntimeException {
        private final IOException cause;

        private UncheckedIoException(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    /** Child process used to leave a committed database without clean shutdown. */
    public static final class CrashSeedWorker {
        public static void main(String[] args) {
            try {
                if (args.length != 3) {
                    throw new IllegalArgumentException("expected provider, database, and row count");
                }
                Provider provider = Provider.valueOf(args[0]);
                Path database = Path.of(args[1]);
                int rows = Integer.parseInt(args[2]);
                try (Connection connection = DriverManager.getConnection(
                        "jdbc:derby:" + database.toAbsolutePath() + ";create=true")) {
                    connection.setAutoCommit(false);
                    executeUpdate(connection,
                            "create table T (id int primary key, value int not null)"
                                    + provider.createSuffix);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into T values (?, ?)")) {
                        for (int id = 1; id <= rows; id++) {
                            insert.setInt(1, id);
                            insert.setInt(2, id * 2);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    connection.commit();
                }
                Runtime.getRuntime().halt(0);
            } catch (Throwable failure) {
                failure.printStackTrace(System.err);
                Runtime.getRuntime().halt(2);
            }
        }
    }
}
