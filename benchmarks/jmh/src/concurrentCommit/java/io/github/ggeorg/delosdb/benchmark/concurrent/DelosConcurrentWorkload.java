/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.concurrent;

import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Operation;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Scenario;
import static io.github.ggeorg.delosdb.benchmark.concurrent.DelosConcurrentScenario.Topology;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Coordinated writer and reader execution for one public-JDBC scenario. */
final class DelosConcurrentWorkload {
    private static final long INSERT_WARMUP_BASE = 1_000_000_000L;
    private static final long INSERT_MEASUREMENT_BASE = 2_000_000_000L;

    private DelosConcurrentWorkload() {
    }

    static RoundResult runRound(
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int transactionsPerWriter,
            int readsPerReader,
            long insertBase) throws Exception {
        int workers = scenario.writers() + scenario.readers();
        long[][] writerTransactionLatencies = new long[scenario.writers()][transactionsPerWriter];
        long[][] commitLatencies = new long[scenario.writers()][transactionsPerWriter];
        long[][] readLatencies = new long[scenario.readers()][readsPerReader];
        long[] writerFinished = new long[scenario.writers()];
        long[] readerFinished = new long[scenario.readers()];
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>(workers);
        try {
            submitWriters(
                    futures,
                    executor,
                    environment,
                    scenario,
                    transactionsPerWriter,
                    insertBase,
                    writerTransactionLatencies,
                    commitLatencies,
                    writerFinished,
                    ready,
                    start,
                    failure);
            submitReaders(
                    futures,
                    executor,
                    environment,
                    scenario,
                    readsPerReader,
                    readLatencies,
                    readerFinished,
                    ready,
                    start,
                    failure);
            awaitReady(ready, failure, scenario);
            long started = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            long elapsed = System.nanoTime() - started;
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                throw new IllegalStateException("concurrency worker failed for " + scenario, workerFailure);
            }
            return new RoundResult(
                    flatten(writerTransactionLatencies),
                    flatten(commitLatencies),
                    flatten(readLatencies),
                    elapsed,
                    elapsedFrom(started, writerFinished),
                    elapsedFrom(started, readerFinished));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(30L, TimeUnit.SECONDS);
        }
    }

    private static void submitWriters(
            List<Future<?>> futures,
            ExecutorService executor,
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int transactionsPerWriter,
            long insertBase,
            long[][] writerTransactionLatencies,
            long[][] commitLatencies,
            long[] writerFinished,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> failure) {
        for (int writer = 0; writer < scenario.writers(); writer++) {
            int writerId = writer;
            futures.add(executor.submit(() -> runWriter(
                    environment,
                    scenario,
                    writerId,
                    transactionsPerWriter,
                    insertBase,
                    writerTransactionLatencies[writerId],
                    commitLatencies[writerId],
                    writerFinished,
                    ready,
                    start,
                    failure)));
        }
    }

    private static void submitReaders(
            List<Future<?>> futures,
            ExecutorService executor,
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int readsPerReader,
            long[][] readLatencies,
            long[] readerFinished,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> failure) {
        for (int reader = 0; reader < scenario.readers(); reader++) {
            int readerId = reader;
            futures.add(executor.submit(() -> runReader(
                    environment,
                    scenario,
                    readerId,
                    readsPerReader,
                    readLatencies[readerId],
                    readerFinished,
                    ready,
                    start,
                    failure)));
        }
    }

    private static void awaitReady(
            CountDownLatch ready,
            AtomicReference<Throwable> failure,
            Scenario scenario) throws InterruptedException {
        if (!ready.await(30L, TimeUnit.SECONDS)) {
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                throw new IllegalStateException(
                        "concurrency worker failed during setup for " + scenario,
                        workerFailure);
            }
            throw new IllegalStateException("workers did not become ready for " + scenario);
        }
        Throwable setupFailure = failure.get();
        if (setupFailure != null) {
            throw new IllegalStateException(
                    "concurrency worker failed during setup for " + scenario,
                    setupFailure);
        }
    }

    private static void runWriter(
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int writerId,
            int transactionCount,
            long insertBase,
            long[] transactionLatencies,
            long[] commitLatencies,
            long[] finished,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> firstFailure) {
        boolean setupSignalled = false;
        try (Connection connection = DriverManager.getConnection(environment.writerJdbcUrl(writerId))) {
            connection.setAutoCommit(false);
            String table = environment.writerTableName(writerId);
            String sql = scenario.operation() == Operation.INSERT
                    ? "insert into " + table + " (id, owner_id, value, payload) values (?, ?, ?, ?)"
                    : "update " + table + " set value = ?, payload = ? where id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ready.countDown();
                setupSignalled = true;
                start.await();
                for (int transaction = 0; transaction < transactionCount; transaction++) {
                    throwIfFailed(firstFailure);
                    long transactionStarted = System.nanoTime();
                    prepareBatch(statement, scenario, writerId, transaction, insertBase);
                    int[] counts = statement.executeBatch();
                    DelosConcurrentScenarioEnvironment.requireUpdatedRows(
                            counts,
                            scenario.rowsPerTransaction());
                    long commitStarted = System.nanoTime();
                    connection.commit();
                    long completed = System.nanoTime();
                    commitLatencies[transaction] = completed - commitStarted;
                    transactionLatencies[transaction] = completed - transactionStarted;
                }
                finished[writerId] = System.nanoTime();
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

    private static void runReader(
            DelosConcurrentScenarioEnvironment environment,
            Scenario scenario,
            int readerId,
            int readCount,
            long[] latencies,
            long[] finished,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Throwable> firstFailure) {
        boolean setupSignalled = false;
        try (Connection connection = DriverManager.getConnection(environment.readerJdbcUrl(readerId))) {
            DelosConcurrentReaderProbe probe = DelosConcurrentReaderProbe.open(
                    connection,
                    environment,
                    scenario,
                    readerId);
            try (probe) {
                ready.countDown();
                setupSignalled = true;
                start.await();
                for (int read = 0; read < readCount; read++) {
                    throwIfFailed(firstFailure);
                    long readStarted = System.nanoTime();
                    probe.executeAndVerify();
                    latencies[read] = System.nanoTime() - readStarted;
                }
                probe.complete();
                finished[readerId] = System.nanoTime();
            } catch (Throwable failure) {
                rollbackSuppressing(connection, failure);
                firstFailure.compareAndSet(null, failure);
                throw unchecked("reader " + readerId + " failed", failure);
            }
        } catch (Throwable failure) {
            firstFailure.compareAndSet(null, failure);
            if (!setupSignalled) {
                ready.countDown();
            }
            throw unchecked("reader " + readerId + " failed", failure);
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
                statement.setInt(2, DelosConcurrentScenarioEnvironment.insertOwner(writerId));
                statement.setInt(3, transaction);
                statement.setString(4, DelosConcurrentScenarioEnvironment.writerPayload(
                        writerId,
                        transaction,
                        row));
            } else {
                statement.setInt(1, transaction + 1);
                statement.setString(2, DelosConcurrentScenarioEnvironment.writerPayload(
                        writerId,
                        transaction,
                        row));
                statement.setLong(3, updateId(scenario, writerId, row));
            }
            statement.addBatch();
        }
    }

    private static long updateId(Scenario scenario, int writerId, int row) {
        int partition = scenario.topology() == Topology.SAME_TABLE ? writerId : 0;
        return DelosConcurrentScenarioEnvironment.fixtureId(partition, row);
    }

    private static long insertId(long phaseBase, int writerId, int transaction, int rows, int row) {
        return phaseBase
                + writerId * 10_000_000L
                + transaction * (long) rows
                + row;
    }

    private static long[] flatten(long[][] values) {
        return Arrays.stream(values).flatMapToLong(Arrays::stream).toArray();
    }

    private static long elapsedFrom(long started, long[] finished) {
        long latest = 0L;
        for (long value : finished) {
            latest = Math.max(latest, value);
        }
        return latest == 0L ? 0L : latest - started;
    }

    private static void throwIfFailed(AtomicReference<Throwable> firstFailure) {
        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw unchecked("peer worker failed", failure);
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

    record RoundResult(
            long[] writerTransactionLatenciesNanos,
            long[] commitLatenciesNanos,
            long[] readLatenciesNanos,
            long elapsedNanos,
            long writerElapsedNanos,
            long readerElapsedNanos) {
        RoundResult {
            writerTransactionLatenciesNanos = writerTransactionLatenciesNanos.clone();
            commitLatenciesNanos = commitLatenciesNanos.clone();
            readLatenciesNanos = readLatenciesNanos.clone();
            if (elapsedNanos <= 0L) {
                throw new IllegalArgumentException("elapsedNanos must be positive");
            }
            if (writerElapsedNanos < 0L || readerElapsedNanos < 0L) {
                throw new IllegalArgumentException("group elapsed times must not be negative");
            }
        }
    }

    record SemanticDigest(long rowCount, long checksum) {
        String checksumHex() {
            return String.format("%016x", checksum);
        }
    }

    static Path createScenarioRoot(Path databaseRoot, Scenario scenario) throws IOException {
        return Files.createTempDirectory(databaseRoot, scenario.fileStem() + '-');
    }

    static void deleteScenarioRoot(Path root) throws IOException {
        deleteRecursively(root);
    }

    static long warmupInsertBase() {
        return INSERT_WARMUP_BASE;
    }

    static long measurementInsertBase() {
        return INSERT_MEASUREMENT_BASE;
    }
}
