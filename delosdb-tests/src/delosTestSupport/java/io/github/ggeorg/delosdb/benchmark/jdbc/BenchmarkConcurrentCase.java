/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DeleteRow;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Interval;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OrderEntryType;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Verification;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Workload;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import java.util.concurrent.TimeoutException;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.beginDrdaProtocolTimingWindow;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.endDrdaProtocolTimingWindow;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.connect;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseMeasurement.preserve;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.PREFIX;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f08MultiRowInsertControlEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_CUSTOMERS;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_DELIVERY_BASE;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_NEW_BASE;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_NEW_LINE_COUNT;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.ORDER_ROLLBACK_BASE;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankBranchTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankHistoryTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.bankTellerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.fixtureQuantities;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderCustomerId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderCustomerTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderEntryType;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderLineTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.orderWarehouseTableName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.rangeCoveringIndexName;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.mix;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadRunner.throwFailure;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankAccountId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankBranchId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankDelta;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bankTellerId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.bindFitnessInsert;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.closeStatement;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.deleteReinsertIds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.executeFitnessRead;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.expectedFitnessRows;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.fitnessInsertSql;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.fitnessReadSql;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.isRetryableConflict;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.mutationIds;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderNewLineAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderNewTotalAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderPaymentAmount;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderStockId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.orderWarehouseId;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.quantity;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.rangeFingerprint;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.rangeIdFingerprint;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.readDeleteRow;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadSql.readOperationIds;

/** Owned concurrent SQL clients, interval coordination and resource cleanup; verifier is borrowed. */
final class BenchmarkConcurrentCase implements AutoCloseable {
    private final BenchmarkConfiguration options;
    private final Spec spec;
    private final Connection verifier;
    private final String table;
    private final int rowCount;
    private final int transactionsPerClient;
    private final int[] mutationIds;
    private final int[] mutationBaseline;
    private final int[] deleteReinsertIds;
    private final DelosSqlSemanticOracle.Result deleteReinsertBaseline;
    private final int insertFirstId;
    private final int insertLastId;
    private final List<Client> clients;
    private final Connection longReaderConnection;
    private final PreparedStatement longReaderScan;
    private final int longReaderStart;
    private final int longReaderEndExclusive;
    private final DelosSqlSemanticOracle.Result longReaderBaseline;
    private final long[] setupDrdaProtocolEvidence;
    private final ExecutorService executor;

    private final BenchmarkCaseVerification verification;

    BenchmarkConcurrentCase(
            BenchmarkConfiguration options,
            Spec spec,
            Path database,
            Connection verifier,
            List<String> tables,
            int rowCount) throws SQLException {
        this.options = options;
        this.spec = spec;
        this.verifier = verifier;
        this.table = tables.get(0);
        this.rowCount = rowCount;
        this.transactionsPerClient = options.transactionsPerClient(spec, rowCount);
        if (spec.workload().isFreshIndexedUpdate()) {
            long requiredRows = Math.multiplyExact(
                    Math.multiplyExact((long) spec.clients(), this.transactionsPerClient),
                    spec.operationsPerTransaction());
            if (requiredRows > rowCount) {
                throw new IllegalArgumentException(
                        "Fresh indexed UPDATE requires at least " + requiredRows
                                + " fixture rows, configured=" + rowCount + ", spec=" + spec);
            }
        }
        this.mutationIds = mutationIds(spec, rowCount);
        this.mutationBaseline = new int[mutationIds.length];
        for (int index = 0; index < mutationIds.length; index++) {
            mutationBaseline[index] = quantity(verifier, this.table, mutationIds[index]);
        }
        this.deleteReinsertIds = spec.workload().isDeleteReinsert()
                ? deleteReinsertIds(spec, rowCount)
                : new int[0];
        this.deleteReinsertBaseline = spec.workload().isDeleteReinsert()
                ? BenchmarkCaseVerification.authoritativeDeleteReinsertState(verifier, this.table, deleteReinsertIds)
                : null;
        long insertedRows = spec.workload().isInsert()
                ? Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), this.transactionsPerClient),
                        spec.operationsPerTransaction())
                : 0L;
        this.insertFirstId = spec.workload().isInsert() ? Math.addExact(rowCount, 1) : 0;
        this.insertLastId = spec.workload().isInsert()
                ? Math.toIntExact(Math.addExact((long) rowCount, insertedRows))
                : 0;
        this.longReaderStart = spec.workload().isLongReaderWriter()
                ? 1 + rowCount / 4
                : 0;
        this.longReaderEndExclusive = spec.workload().isLongReaderWriter()
                ? 1 + (3 * rowCount) / 4
                : 0;
        this.longReaderBaseline = spec.workload().isLongReaderWriter()
                ? BenchmarkCaseVerification.authoritativeLongReaderState(
                        verifier, this.table, longReaderStart, longReaderEndExclusive)
                : null;
        int[] fixtureQuantities = spec.workload().usesFixtureQuantities() ? fixtureQuantities(rowCount) : null;
        verifier.rollback();
        if (drdaProtocolEvidenceEnabled()) {
            resetDrdaProtocolEvidence();
        }
        this.clients = new ArrayList<>(spec.clients());
        try {
            for (int client = 0; client < spec.clients(); client++) {
                Connection connection = BenchmarkCaseMeasurement.connect(options, database);
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(spec.workload().isolationLevel());
                if (Boolean.getBoolean(PREFIX + "closeCursorsAtCommit")) {
                    connection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
                }
                int updateId = 0;
                int[] readIds = null;
                int[] expectedReadQuantities = null;
                int rangeStart = 0;
                int rangeEndExclusive = 0;
                int expectedRangeRows = 0;
                long expectedRangeFingerprint = 0L;
                DeleteRow[] deleteRows = null;
                int expectedFitnessRows = 0;
                int insertBaseId = 0;
                if (spec.workload().isPrimaryKeyRead()) {
                    int operations = Math.multiplyExact(
                            transactionsPerClient, spec.operationsPerTransaction());
                    readIds = readOperationIds(spec, rowCount, client, operations);
                    expectedReadQuantities = new int[readIds.length];
                    for (int operation = 0; operation < readIds.length; operation++) {
                        expectedReadQuantities[operation] = fixtureQuantities[readIds[operation]];
                    }
                } else if (spec.workload().isRangeScan()) {
                    expectedRangeRows = spec.workload().rangeRows(rowCount);
                    int startCount = rowCount - expectedRangeRows + 1;
                    rangeStart = 1 + (int) (((long) client * startCount) / spec.clients());
                    rangeEndExclusive = rangeStart + expectedRangeRows;
                    expectedRangeFingerprint = spec.workload().isIndexOnlyRangeScan()
                            ? rangeIdFingerprint(rangeStart, rangeEndExclusive)
                            : rangeFingerprint(fixtureQuantities, rangeStart, rangeEndExclusive);
                } else if (spec.workload().isFitnessRead()) {
                    expectedFitnessRows = expectedFitnessRows(spec.workload(), rowCount);
                } else if (spec.workload().isInsert()) {
                    int insertsPerClient = Math.multiplyExact(
                            transactionsPerClient, spec.operationsPerTransaction());
                    insertBaseId = Math.addExact(rowCount + 1, Math.multiplyExact(client, insertsPerClient));
                } else if (spec.workload().isDeleteReinsert()) {
                    int width = spec.operationsPerTransaction();
                    deleteRows = new DeleteRow[width];
                    int offset = client * width;
                    for (int operation = 0; operation < width; operation++) {
                        deleteRows[operation] = readDeleteRow(
                                connection, this.table, deleteReinsertIds[offset + operation]);
                    }
                } else if (spec.workload().isMixedReaderWriter()) {
                    updateId = spec.workload() == Workload.MIXED_80R20W
                            ? mutationIds[client]
                            : mutationIds[client % mutationIds.length];
                } else if (spec.workload().isIndexedUpdate()
                        && !spec.workload().isFreshIndexedUpdate()) {
                    boolean disjoint = spec.workload() == Workload.DISJOINT_INDEXED_UPDATE
                            || spec.workload() == Workload.LONG_READER_DISJOINT_WRITER;
                    int targetIndex = disjoint ? client : 0;
                    updateId = mutationIds[targetIndex];
                }
                String clientTable = spec.workload().usesPrivateTablePerClient()
                        ? tables.get(client)
                        : this.table;
                clients.add(new Client(
                        connection, clientTable, spec, client, transactionsPerClient, rowCount,
                        updateId, readIds, expectedReadQuantities,
                        rangeStart, rangeEndExclusive, expectedRangeRows, expectedRangeFingerprint,
                        deleteRows, expectedFitnessRows, insertBaseId, options.payload(),
                        options.target(), options.h2RangeFetchSize()));
            }
        } catch (SQLException failure) {
            closeClients(failure);
            throw failure;
        }
        Connection localLongReaderConnection = null;
        PreparedStatement localLongReaderScan = null;
        try {
            if (spec.workload().isLongReaderWriter()) {
                localLongReaderConnection = BenchmarkCaseMeasurement.connect(options, database);
                localLongReaderConnection.setAutoCommit(false);
                localLongReaderConnection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                localLongReaderScan = localLongReaderConnection.prepareStatement(
                        "select id, quantity from " + this.table
                                + " where id >= ? and id < ? order by id");
            }
        } catch (SQLException failure) {
            if (localLongReaderConnection != null) {
                try {
                    localLongReaderConnection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            closeClients(failure);
            throw failure;
        }
        this.longReaderConnection = localLongReaderConnection;
        this.longReaderScan = localLongReaderScan;
        this.setupDrdaProtocolEvidence = snapshotDrdaProtocolEvidence();
        this.verification = new BenchmarkCaseVerification(
                options, spec, verifier, table, rowCount, transactionsPerClient,
                mutationIds, mutationBaseline, deleteReinsertIds, deleteReinsertBaseline,
                insertFirstId, insertLastId);
        this.executor = Executors.newFixedThreadPool(
                spec.clients(), Thread.ofPlatform().daemon().name("delos-bench-client-", 0).factory());
    }

    long[] setupDrdaProtocolEvidence() {
        return setupDrdaProtocolEvidence;
    }

    Interval runInterval(boolean captureSqlOracle) throws Exception {
        if (spec.workload().isLongReaderWriter()) {
            return runLongReaderWriterInterval(captureSqlOracle);
        }
        CountDownLatch ready = new CountDownLatch(spec.clients());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClientRun>> futures = new ArrayList<>(spec.clients());
        for (Client client : clients) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrency start barrier timed out");
                }
                return client.runTransactions(transactionsPerClient, spec.operationsPerTransaction());
            }));
        }
        if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
            start.countDown();
            cancelFutures(futures);
            throw new IllegalStateException("concurrency readiness barrier timed out");
        }
        if (drdaProtocolEvidenceEnabled()) {
            resetDrdaProtocolEvidence();
            beginDrdaProtocolTimingWindow();
        }
        RawStoreOccupancyBenchmark.reset();
        long started = System.nanoTime();
        RawStoreStabilityDiagnostics.markStart(started);
        long deadline = started + TimeUnit.SECONDS.toNanos(options.caseTimeoutSeconds());
        long executionFingerprint = 1L;
        long retryableRollbacks = 0L;
        Throwable failure = null;
        long elapsed;
        try {
            start.countDown();
            for (Future<ClientRun> future : futures) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        throw new TimeoutException("concurrency interval deadline exceeded");
                    }
                    ClientRun clientRun = future.get(remaining, TimeUnit.NANOSECONDS);
                    executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                    retryableRollbacks = Math.addExact(retryableRollbacks, clientRun.retryableRollbacks());
                } catch (TimeoutException timeout) {
                    cancelFutures(futures);
                    throw new IllegalStateException("concurrency interval exceeded "
                            + options.caseTimeoutSeconds() + " seconds: " + spec, timeout);
                } catch (Throwable clientFailure) {
                    failure = preserve(failure, clientFailure);
                }
            }
            elapsed = System.nanoTime() - started;
        } finally {
            if (drdaProtocolEvidenceEnabled()) {
                endDrdaProtocolTimingWindow();
            }
        }
        if (failure != null) {
            cancelFutures(futures);
            throwFailure(failure);
        }
        RawStoreOccupancyBenchmark.write(options.reportDirectory(), options.target().id(),
                spec.clients(), options.run(), transactionsPerClient,
                spec.operationsPerTransaction(), elapsed);
        long[] protocolEvidence = snapshotDrdaProtocolEvidence();
        Verification verification = this.verification.verifyAndRestore(captureSqlOracle);
        return new Interval(
                elapsed,
                mix(executionFingerprint, verification.legacyFingerprint()),
                retryableRollbacks,
                verification.sqlOracleResult(),
                protocolEvidence);
    }

    private Interval runLongReaderWriterInterval(boolean captureSqlOracle) throws Exception {
        if (longReaderConnection == null || longReaderScan == null || longReaderBaseline == null) {
            throw new IllegalStateException("Long-reader/writer case is not initialized: " + spec);
        }
        try {
            longReaderConnection.rollback();
            longReaderScan.setInt(1, longReaderStart);
            longReaderScan.setInt(2, longReaderEndExclusive);

            CountDownLatch ready = new CountDownLatch(spec.clients());
            CountDownLatch startWriters = new CountDownLatch(1);
            List<Future<ClientRun>> futures = new ArrayList<>(spec.clients());
            for (Client client : clients) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!startWriters.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("long-reader writer start barrier timed out");
                    }
                    return client.runTransactions(transactionsPerClient, spec.operationsPerTransaction());
                }));
            }
            if (!ready.await(options.caseTimeoutSeconds(), TimeUnit.SECONDS)) {
                startWriters.countDown();
                cancelFutures(futures);
                throw new IllegalStateException("long-reader writer readiness barrier timed out");
            }

            if (drdaProtocolEvidenceEnabled()) {
                resetDrdaProtocolEvidence();
                beginDrdaProtocolTimingWindow();
            }
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(options.caseTimeoutSeconds());
            DelosSqlSemanticOracle.Result readerSnapshot;
            long executionFingerprint = 1L;
            long retryableRollbacks = 0L;
            Throwable failure = null;
            int completedBeforeReaderRelease;
            long elapsed;
            try {
                try (ResultSet resultSet = longReaderScan.executeQuery()) {
                    readerSnapshot = DelosSqlSemanticOracle.query(
                            resultSet, DelosSqlSemanticOracle.RowOrder.ORDERED);
                }
                if (readerSnapshot.count() != longReaderBaseline.count()
                        || !readerSnapshot.fingerprint().equals(longReaderBaseline.fingerprint())) {
                    throw new IllegalStateException(
                            "Long-reader snapshot drift for " + spec
                                    + ": expected=" + longReaderBaseline
                                    + ", actual=" + readerSnapshot);
                }

                startWriters.countDown();
                Thread.sleep(25L);
                completedBeforeReaderRelease = 0;
                for (Future<ClientRun> future : futures) {
                    if (future.isDone()) {
                        completedBeforeReaderRelease++;
                    }
                }
                longReaderConnection.rollback();

                executionFingerprint = mix(executionFingerprint, readerSnapshot.fingerprint().hashCode());
                for (Future<ClientRun> future : futures) {
                    try {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) {
                            throw new TimeoutException("long-reader/writer interval deadline exceeded");
                        }
                        ClientRun clientRun = future.get(remaining, TimeUnit.NANOSECONDS);
                        executionFingerprint = mix(executionFingerprint, clientRun.fingerprint());
                        retryableRollbacks = Math.addExact(
                                retryableRollbacks, clientRun.retryableRollbacks());
                    } catch (TimeoutException timeout) {
                        cancelFutures(futures);
                        throw new IllegalStateException(
                                "long-reader/writer interval exceeded "
                                        + options.caseTimeoutSeconds() + " seconds: " + spec,
                                timeout);
                    } catch (Throwable clientFailure) {
                        failure = preserve(failure, clientFailure);
                    }
                }
                elapsed = System.nanoTime() - started;
            } finally {
                try {
                    longReaderConnection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure = preserve(failure, rollbackFailure);
                }
                if (drdaProtocolEvidenceEnabled()) {
                    endDrdaProtocolTimingWindow();
                }
            }
            if (failure != null) {
                cancelFutures(futures);
                throwFailure(failure);
            }

            long[] protocolEvidence = snapshotDrdaProtocolEvidence();
            Verification verification = this.verification.verifyAndRestore(captureSqlOracle);
            DelosSqlSemanticOracle.Result oracle = null;
            if (captureSqlOracle) {
                Map<String, DelosSqlSemanticOracle.Result> components = new LinkedHashMap<>();
                components.put("reader-snapshot", readerSnapshot);
                components.put("writer-mutation", Objects.requireNonNull(
                        verification.sqlOracleResult(), "writer mutation oracle"));
                oracle = DelosSqlSemanticOracle.composite(
                        "FITNESS_LONG_READER_WRITER", components);
            }
            System.out.printf(
                    Locale.ROOT,
                    "F12_PROGRESS run=%d target=%s workload=%s writersCompletedBeforeReaderRelease=%d/%d holdMillis=25%n",
                    options.run(), options.target().id(), spec.workload().name(),
                    completedBeforeReaderRelease, spec.clients());
            return new Interval(
                    elapsed,
                    mix(executionFingerprint, verification.legacyFingerprint()),
                    retryableRollbacks,
                    oracle,
                    protocolEvidence);
        } catch (Throwable failure) {
            try {
                longReaderConnection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throwFailure(failure);
            throw new AssertionError("unreachable");
        }
    }

    private static void cancelFutures(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            future.cancel(true);
        }
    }

    @Override
    public void close() throws Exception {
        executor.shutdownNow();
        if (executor.awaitTermination(10, TimeUnit.SECONDS)) {
            SQLException failure = null;
            if (longReaderScan != null) {
                try {
                    longReaderScan.close();
                } catch (SQLException closeFailure) {
                    failure = closeFailure;
                }
            }
            if (longReaderConnection != null) {
                try {
                    if (!longReaderConnection.getAutoCommit()) {
                        longReaderConnection.rollback();
                    }
                    longReaderConnection.close();
                } catch (SQLException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            try {
                closeClients(failure);
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        } else {
            System.err.println("Benchmark client tasks did not terminate after cancellation; "
                    + "leaving JDBC connections to isolated worker-process teardown");
        }
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

    private static final class RealisticTransactionClient implements AutoCloseable {
        private final Connection connection;
        private final String table;
        private final Workload workload;
        private final int clientIndex;
        private final int transactionsPerClient;
        private final int clients;
        private final int rowCount;
        private final int customerCount;
        private final PreparedStatement bankUpdateAccount;
        private final PreparedStatement bankInsertHistory;
        private final PreparedStatement bankUpdateTeller;
        private final PreparedStatement bankUpdateBranch;
        private final PreparedStatement bankReadAccount;
        private final PreparedStatement oeInsertOrder;
        private final PreparedStatement oeInsertLine;
        private final PreparedStatement oeUpdateStock;
        private final PreparedStatement oeUpdateLastOrder;
        private final PreparedStatement oePaymentCustomer;
        private final PreparedStatement oePaymentWarehouse;
        private final PreparedStatement oeReadCustomer;
        private final PreparedStatement oeReadOrder;
        private final PreparedStatement oeDeliverOrder;
        private final PreparedStatement oeDeliverCustomer;
        private final PreparedStatement oeStockLevel;

        private RealisticTransactionClient(
                Connection connection,
                String table,
                Workload workload,
                int clientIndex,
                int transactionsPerClient,
                int clients,
                int rowCount) throws SQLException {
            this.connection = connection;
            this.table = table;
            this.workload = workload;
            this.clientIndex = clientIndex;
            this.transactionsPerClient = transactionsPerClient;
            this.clients = clients;
            this.rowCount = rowCount;
            this.customerCount = Math.min(ORDER_CUSTOMERS, rowCount);

            PreparedStatement localBankUpdateAccount = null;
            PreparedStatement localBankInsertHistory = null;
            PreparedStatement localBankUpdateTeller = null;
            PreparedStatement localBankUpdateBranch = null;
            PreparedStatement localBankReadAccount = null;
            PreparedStatement localOeInsertOrder = null;
            PreparedStatement localOeInsertLine = null;
            PreparedStatement localOeUpdateStock = null;
            PreparedStatement localOeUpdateLastOrder = null;
            PreparedStatement localOePaymentCustomer = null;
            PreparedStatement localOePaymentWarehouse = null;
            PreparedStatement localOeReadCustomer = null;
            PreparedStatement localOeReadOrder = null;
            PreparedStatement localOeDeliverOrder = null;
            PreparedStatement localOeDeliverCustomer = null;
            PreparedStatement localOeStockLevel = null;
            try {
                if (workload == Workload.BANK_TRANSACTION) {
                    localBankUpdateAccount = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + ? where id = ?");
                    localBankInsertHistory = connection.prepareStatement(
                            "insert into " + bankHistoryTableName(table)
                                    + " (tx_id, account_id, teller_id, branch_id, amount) values (?, ?, ?, ?, ?)");
                    localBankUpdateTeller = connection.prepareStatement(
                            "update " + bankTellerTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localBankUpdateBranch = connection.prepareStatement(
                            "update " + bankBranchTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localBankReadAccount = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else if (workload == Workload.ORDER_ENTRY_MIX) {
                    localOeInsertOrder = connection.prepareStatement(
                            "insert into " + orderTableName(table)
                                    + " (id, customer_id, status, amount) values (?, ?, 0, ?)");
                    localOeInsertLine = connection.prepareStatement(
                            "insert into " + orderLineTableName(table)
                                    + " (order_id, line_no, stock_id, quantity, amount) values (?, ?, ?, ?, ?)");
                    localOeUpdateStock = connection.prepareStatement(
                            "update " + table + " set quantity = quantity - ? where id = ?");
                    localOeUpdateLastOrder = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set last_order = ? where id = ?");
                    localOePaymentCustomer = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localOePaymentWarehouse = connection.prepareStatement(
                            "update " + orderWarehouseTableName(table)
                                    + " set balance = balance + ? where id = ?");
                    localOeReadCustomer = connection.prepareStatement(
                            "select balance, last_order from " + orderCustomerTableName(table)
                                    + " where id = ?");
                    localOeReadOrder = connection.prepareStatement(
                            "select status, amount from " + orderTableName(table) + " where id = ?");
                    localOeDeliverOrder = connection.prepareStatement(
                            "update " + orderTableName(table) + " set status = 1 where id = ? and status = 0");
                    localOeDeliverCustomer = connection.prepareStatement(
                            "update " + orderCustomerTableName(table)
                                    + " set balance = balance + 50 where id = ?");
                    localOeStockLevel = connection.prepareStatement(
                            "select count(*) from " + table + " where quantity < ?");
                } else {
                    throw new SQLException("Not a realistic transaction workload: " + workload);
                }
            } catch (SQLException failure) {
                PreparedStatement[] statements = {
                        localBankUpdateAccount, localBankInsertHistory, localBankUpdateTeller,
                        localBankUpdateBranch, localBankReadAccount, localOeInsertOrder,
                        localOeInsertLine, localOeUpdateStock, localOeUpdateLastOrder,
                        localOePaymentCustomer, localOePaymentWarehouse, localOeReadCustomer,
                        localOeReadOrder, localOeDeliverOrder, localOeDeliverCustomer,
                        localOeStockLevel};
                for (PreparedStatement statement : statements) {
                    closeStatement(statement, failure);
                }
                throw failure;
            }
            this.bankUpdateAccount = localBankUpdateAccount;
            this.bankInsertHistory = localBankInsertHistory;
            this.bankUpdateTeller = localBankUpdateTeller;
            this.bankUpdateBranch = localBankUpdateBranch;
            this.bankReadAccount = localBankReadAccount;
            this.oeInsertOrder = localOeInsertOrder;
            this.oeInsertLine = localOeInsertLine;
            this.oeUpdateStock = localOeUpdateStock;
            this.oeUpdateLastOrder = localOeUpdateLastOrder;
            this.oePaymentCustomer = localOePaymentCustomer;
            this.oePaymentWarehouse = localOePaymentWarehouse;
            this.oeReadCustomer = localOeReadCustomer;
            this.oeReadOrder = localOeReadOrder;
            this.oeDeliverOrder = localOeDeliverOrder;
            this.oeDeliverCustomer = localOeDeliverCustomer;
            this.oeStockLevel = localOeStockLevel;
        }

        private long execute(int transaction) throws SQLException {
            return workload == Workload.BANK_TRANSACTION
                    ? executeBankTransaction(transaction)
                    : executeOrderEntryTransaction(transaction);
        }

        private long executeBankTransaction(int transaction) throws SQLException {
            int globalOrdinal = clientIndex * transactionsPerClient + transaction;
            int accountId = bankAccountId(globalOrdinal, rowCount);
            int branchId = bankBranchId(accountId, rowCount);
            int tellerId = bankTellerId(branchId, globalOrdinal);
            int delta = bankDelta(globalOrdinal);
            int txId = globalOrdinal + 1;

            bankUpdateAccount.setInt(1, delta);
            bankUpdateAccount.setInt(2, accountId);
            requireOne(bankUpdateAccount.executeUpdate(), "bank account update", accountId);

            bankInsertHistory.setInt(1, txId);
            bankInsertHistory.setInt(2, accountId);
            bankInsertHistory.setInt(3, tellerId);
            bankInsertHistory.setInt(4, branchId);
            bankInsertHistory.setInt(5, delta);
            requireOne(bankInsertHistory.executeUpdate(), "bank history insert", txId);

            bankUpdateTeller.setInt(1, delta);
            bankUpdateTeller.setInt(2, tellerId);
            requireOne(bankUpdateTeller.executeUpdate(), "bank teller update", tellerId);

            bankUpdateBranch.setInt(1, delta);
            bankUpdateBranch.setInt(2, branchId);
            requireOne(bankUpdateBranch.executeUpdate(), "bank branch update", branchId);

            bankReadAccount.setInt(1, accountId);
            try (ResultSet resultSet = bankReadAccount.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Bank account read returned no row: id=" + accountId);
                }
                resultSet.getInt(1);
                if (resultSet.next()) {
                    throw new SQLException("Bank account read returned duplicate row: id=" + accountId);
                }
            }
            connection.commit();
            long fingerprint = mix(txId, accountId);
            fingerprint = mix(fingerprint, tellerId);
            fingerprint = mix(fingerprint, branchId);
            return mix(fingerprint, delta);
        }

        private long executeOrderEntryTransaction(int transaction) throws SQLException {
            int globalOrdinal = clientIndex * transactionsPerClient + transaction;
            int customerId = orderCustomerId(
                    clientIndex, transaction, clients, customerCount);
            int warehouseId = orderWarehouseId(clientIndex);
            OrderEntryType type = orderEntryType(transaction);
            long fingerprint = mix(globalOrdinal + 1L, type.ordinal());
            fingerprint = mix(fingerprint, customerId);
            switch (type) {
                case NEW_ORDER -> {
                    int orderId = ORDER_NEW_BASE + globalOrdinal;
                    int expectedTotalAmount = orderNewTotalAmount();
                    int totalAmount = 0;
                    oeInsertOrder.setInt(1, orderId);
                    oeInsertOrder.setInt(2, customerId);
                    oeInsertOrder.setInt(3, expectedTotalAmount);
                    requireOne(oeInsertOrder.executeUpdate(), "new-order header insert", orderId);
                    for (int line = 1; line <= ORDER_NEW_LINE_COUNT; line++) {
                        int stockId = orderStockId(globalOrdinal, line, rowCount);
                        int amount = orderNewLineAmount(line);
                        totalAmount += amount;
                        oeUpdateStock.setInt(1, 1);
                        oeUpdateStock.setInt(2, stockId);
                        requireOne(oeUpdateStock.executeUpdate(), "new-order stock update", stockId);
                        oeInsertLine.setInt(1, orderId);
                        oeInsertLine.setInt(2, line);
                        oeInsertLine.setInt(3, stockId);
                        oeInsertLine.setInt(4, 1);
                        oeInsertLine.setInt(5, amount);
                        requireOne(oeInsertLine.executeUpdate(), "new-order line insert", orderId);
                    }
                    if (totalAmount != expectedTotalAmount) {
                        throw new SQLException(
                                "Order Entry amount construction drift: expected="
                                        + expectedTotalAmount + ", actual=" + totalAmount);
                    }
                    oeUpdateLastOrder.setInt(1, orderId);
                    oeUpdateLastOrder.setInt(2, customerId);
                    requireOne(oeUpdateLastOrder.executeUpdate(), "new-order customer update", customerId);
                    connection.commit();
                    return mix(fingerprint, orderId);
                }
                case PAYMENT -> {
                    int amount = orderPaymentAmount(globalOrdinal);
                    oePaymentCustomer.setInt(1, amount);
                    oePaymentCustomer.setInt(2, customerId);
                    requireOne(oePaymentCustomer.executeUpdate(), "payment customer update", customerId);
                    oePaymentWarehouse.setInt(1, amount);
                    oePaymentWarehouse.setInt(2, warehouseId);
                    requireOne(oePaymentWarehouse.executeUpdate(), "payment warehouse update", warehouseId);
                    readCustomer(customerId);
                    connection.commit();
                    return mix(fingerprint, amount);
                }
                case ORDER_STATUS -> {
                    int lastOrder = readCustomer(customerId);
                    if (lastOrder != 0) {
                        oeReadOrder.setInt(1, lastOrder);
                        try (ResultSet resultSet = oeReadOrder.executeQuery()) {
                            if (!resultSet.next()) {
                                throw new SQLException("Order status could not find last order " + lastOrder);
                            }
                            resultSet.getInt(1);
                            resultSet.getInt(2);
                            if (resultSet.next()) {
                                throw new SQLException("Order status returned duplicate order " + lastOrder);
                            }
                        }
                    }
                    connection.commit();
                    return fingerprint;
                }
                case DELIVERY -> {
                    int orderId = ORDER_DELIVERY_BASE + globalOrdinal;
                    oeDeliverOrder.setInt(1, orderId);
                    requireOne(oeDeliverOrder.executeUpdate(), "delivery order update", orderId);
                    oeDeliverCustomer.setInt(1, customerId);
                    requireOne(oeDeliverCustomer.executeUpdate(), "delivery customer update", customerId);
                    connection.commit();
                    return mix(fingerprint, orderId);
                }
                case STOCK_LEVEL -> {
                    oeStockLevel.setInt(1, 20);
                    try (ResultSet resultSet = oeStockLevel.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException("Stock-level query returned no row");
                        }
                        resultSet.getLong(1);
                        if (resultSet.next()) {
                            throw new SQLException("Stock-level query returned multiple rows");
                        }
                    }
                    connection.commit();
                    return fingerprint;
                }
                case NEW_ORDER_ROLLBACK -> {
                    int orderId = ORDER_ROLLBACK_BASE + globalOrdinal;
                    int stockId = orderStockId(globalOrdinal, 1, rowCount);
                    oeInsertOrder.setInt(1, orderId);
                    oeInsertOrder.setInt(2, customerId);
                    oeInsertOrder.setInt(3, 11);
                    requireOne(oeInsertOrder.executeUpdate(), "rollback order insert", orderId);
                    oeUpdateStock.setInt(1, 1);
                    oeUpdateStock.setInt(2, stockId);
                    requireOne(oeUpdateStock.executeUpdate(), "rollback stock update", stockId);
                    connection.rollback();
                    return mix(fingerprint, orderId);
                }
            }
            throw new SQLException("Unhandled Order Entry type " + type);
        }

        private int readCustomer(int customerId) throws SQLException {
            oeReadCustomer.setInt(1, customerId);
            try (ResultSet resultSet = oeReadCustomer.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Order Entry customer read returned no row: id=" + customerId);
                }
                resultSet.getLong(1);
                int lastOrder = resultSet.getInt(2);
                if (resultSet.next()) {
                    throw new SQLException("Order Entry customer read returned duplicate row: id=" + customerId);
                }
                return lastOrder;
            }
        }

        private static void requireOne(int count, String operation, int id) throws SQLException {
            if (count != 1) {
                throw new SQLException(operation + " did not affect exactly one row: id=" + id
                        + ", count=" + count);
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            PreparedStatement[] statements = {
                    bankUpdateAccount, bankInsertHistory, bankUpdateTeller, bankUpdateBranch,
                    bankReadAccount, oeInsertOrder, oeInsertLine, oeUpdateStock, oeUpdateLastOrder,
                    oePaymentCustomer, oePaymentWarehouse, oeReadCustomer, oeReadOrder,
                    oeDeliverOrder, oeDeliverCustomer, oeStockLevel};
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
    }

    private static final class Client implements AutoCloseable {
        private final Connection connection;
        private final Workload workload;
        private final int clientIndex;
        private final int clientCount;
        private final int rowCount;
        private final int transactionsPerClient;
        private final int[] mixedBaselineQuantities;
        private final int updateId;
        private final int[] readIds;
        private final int[] expectedReadQuantities;
        private final int rangeStart;
        private final int rangeEndExclusive;
        private final int expectedRangeRows;
        private final long expectedRangeFingerprint;
        private final Target target;
        private final PreparedStatement read;
        private final PreparedStatement rangeRead;
        private final PreparedStatement values;
        private final PreparedStatement fitnessRead;
        private final PreparedStatement update;
        private final PreparedStatement deleteRow;
        private final PreparedStatement insertRow;
        private final DeleteRow[] deleteRows;
        private final int expectedFitnessRows;
        private final int insertBaseId;
        private final int payloadSize;
        private final RealisticTransactionClient realisticTransaction;

        private Client(
                Connection connection,
                String table,
                Spec spec,
                int clientIndex,
                int transactionsPerClient,
                int rowCount,
                int updateId,
                int[] readIds,
                int[] expectedReadQuantities,
                int rangeStart,
                int rangeEndExclusive,
                int expectedRangeRows,
                long expectedRangeFingerprint,
                DeleteRow[] deleteRows,
                int expectedFitnessRows,
                int insertBaseId,
                int payloadSize,
                Target target,
                int h2RangeFetchSize)
                throws SQLException {
            this.connection = connection;
            this.workload = spec.workload();
            this.clientIndex = clientIndex;
            this.clientCount = spec.clients();
            this.rowCount = rowCount;
            this.transactionsPerClient = transactionsPerClient;
            this.mixedBaselineQuantities = spec.workload().isMixedReaderWriter()
                    ? fixtureQuantities(rowCount)
                    : null;
            this.updateId = updateId;
            this.readIds = readIds;
            this.expectedReadQuantities = expectedReadQuantities;
            this.rangeStart = rangeStart;
            this.rangeEndExclusive = rangeEndExclusive;
            this.expectedRangeRows = expectedRangeRows;
            this.expectedRangeFingerprint = expectedRangeFingerprint;
            this.deleteRows = deleteRows == null ? new DeleteRow[0] : deleteRows.clone();
            this.expectedFitnessRows = expectedFitnessRows;
            this.insertBaseId = insertBaseId;
            this.payloadSize = payloadSize;
            this.target = target;
            PreparedStatement localRead = null;
            PreparedStatement localRangeRead = null;
            PreparedStatement localValues = null;
            PreparedStatement localFitnessRead = null;
            PreparedStatement localUpdate = null;
            PreparedStatement localDeleteRow = null;
            PreparedStatement localInsertRow = null;
            RealisticTransactionClient localRealisticTransaction = null;
            try {
                if (workload.isPrimaryKeyRead()) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                } else if (workload.isRangeScan()) {
                    String rangeSql;
                    if (workload.isIndexOnlyRangeScan()) {
                        rangeSql = "select id from " + table
                                + " where id >= ? and id < ? order by id";
                    } else if (workload.isCoveringRangeScan()
                            || (workload.isRowBearingComparisonRangeScan()
                                    && (target == Target.DELOS_HEAP
                                            || target == Target.UPSTREAM_DERBY))) {
                        rangeSql = "select id, quantity from " + table
                                + " --DERBY-PROPERTIES index=" + rangeCoveringIndexName(table) + "\n"
                                + " where id >= ? and id < ? order by id";
                    } else if (workload.isMvccNaturalOrderRangeScan()) {
                        rangeSql = "select id, quantity from " + table
                                + " where id >= ? and id < ?";
                    } else {
                        rangeSql = "select id, quantity from " + table
                                + " where id >= ? and id < ? order by id";
                    }
                    localRangeRead = connection.prepareStatement(rangeSql);
                    if (target == Target.H2_SERVER && h2RangeFetchSize > 0) {
                        localRangeRead.setFetchSize(h2RangeFetchSize);
                    }
                } else if (workload.isValues()) {
                    localValues = connection.prepareStatement("values (1)");
                } else if (workload.isFitnessRead()) {
                    localFitnessRead = connection.prepareStatement(fitnessReadSql(workload, table));
                } else if (workload.isMixedReaderWriter()) {
                    localRead = connection.prepareStatement(
                            "select quantity from " + table + " where id = ?");
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                } else if (workload.isRealisticTransaction()) {
                    localRealisticTransaction = new RealisticTransactionClient(
                            connection, table, workload, clientIndex, transactionsPerClient,
                            spec.clients(), rowCount);
                } else if (workload.isIndexedUpdate()) {
                    localUpdate = connection.prepareStatement(
                            "update " + table + " set quantity = quantity + 1 where id = ?");
                } else if (workload.isInsert()) {
                    int rowsPerStatement = f08MultiRowInsertControlEnabled()
                            && workload == Workload.INSERT_100 ? 100 : 1;
                    localInsertRow = connection.prepareStatement(
                            fitnessInsertSql(table, rowsPerStatement));
                } else if (workload.isDeleteReinsert()) {
                    localDeleteRow = connection.prepareStatement("delete from " + table + " where id = ?");
                    localInsertRow = connection.prepareStatement(
                            "insert into " + table
                                    + " (id, category, bucket, quantity, payload) values (?, ?, ?, ?, ?)");
                }
                this.read = localRead;
                this.rangeRead = localRangeRead;
                this.values = localValues;
                this.fitnessRead = localFitnessRead;
                this.update = localUpdate;
                this.deleteRow = localDeleteRow;
                this.insertRow = localInsertRow;
                this.realisticTransaction = localRealisticTransaction;
            } catch (SQLException failure) {
                if (localRealisticTransaction != null) {
                    try {
                        localRealisticTransaction.close();
                    } catch (SQLException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                closeStatement(localInsertRow, failure);
                closeStatement(localDeleteRow, failure);
                closeStatement(localUpdate, failure);
                closeStatement(localFitnessRead, failure);
                closeStatement(localValues, failure);
                closeStatement(localRangeRead, failure);
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
                        if (workload.isRealisticTransaction()) {
                            transactionFingerprint = Objects.requireNonNull(
                                    realisticTransaction, "realistic transaction client")
                                    .execute(transaction);
                            fingerprint = mix(fingerprint, transactionFingerprint);
                            break;
                        }
                        if (workload.isMixedReaderWriter()) {
                            transactionFingerprint = executeMixedTransaction(transaction);
                            connection.commit();
                            fingerprint = mix(fingerprint, transactionFingerprint);
                            break;
                        }
                        if (workload.isInsert()) {
                            transactionFingerprint = executeInsertTransaction(
                                    transaction, operationsPerTransaction);
                        }
                        for (int operation = 0;
                                !workload.isInsert() && operation < operationsPerTransaction;
                                operation++) {
                            int operationIndex = transaction * operationsPerTransaction + operation;
                            if (workload.isValues()) {
                                try (ResultSet resultSet = values.executeQuery()) {
                                    if (!resultSet.next() || resultSet.getInt(1) != 1 || resultSet.next()) {
                                        throw new SQLException("VALUES benchmark returned unexpected result");
                                    }
                                }
                                transactionFingerprint = mix(transactionFingerprint, 1);
                            } else if (workload.isPrimaryKeyRead()) {
                                int id = readIds[operationIndex];
                                int expectedReadQuantity = expectedReadQuantities[operationIndex];
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
                                transactionFingerprint = mix(transactionFingerprint, id);
                            } else if (workload.isRangeScan()) {
                                rangeRead.setInt(1, rangeStart);
                                rangeRead.setInt(2, rangeEndExclusive);
                                long rangeFingerprint = 1L;
                                int rows = 0;
                                try (ResultSet resultSet = rangeRead.executeQuery()) {
                                    while (resultSet.next()) {
                                        int id = resultSet.getInt(1);
                                        if (workload.isIndexOnlyRangeScan()) {
                                            rangeFingerprint = mix(rangeFingerprint, id);
                                        } else {
                                            int quantity = resultSet.getInt(2);
                                            rangeFingerprint = mix(mix(rangeFingerprint, id), quantity);
                                        }
                                        rows++;
                                    }
                                }
                                if (rows != expectedRangeRows
                                        || rangeFingerprint != expectedRangeFingerprint) {
                                    throw new SQLException("Concurrent range scan changed: expectedRows="
                                            + expectedRangeRows + ", actualRows=" + rows
                                            + ", expectedFingerprint=" + expectedRangeFingerprint
                                            + ", actualFingerprint=" + rangeFingerprint);
                                }
                                transactionFingerprint = mix(transactionFingerprint, rows);
                                transactionFingerprint = mix(transactionFingerprint, rangeFingerprint);
                            } else if (workload.isFitnessRead()) {
                                transactionFingerprint = mix(
                                        transactionFingerprint,
                                        executeFitnessRead(fitnessRead, workload, expectedFitnessRows));
                            } else if (workload.isDeleteReinsert()) {
                                DeleteRow row = deleteRows[operation % deleteRows.length];
                                deleteRow.setInt(1, row.id());
                                if (deleteRow.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Delete/reinsert delete did not affect one row: id=" + row.id());
                                }
                                insertRow.setInt(1, row.id());
                                insertRow.setInt(2, row.category());
                                insertRow.setInt(3, row.bucket());
                                insertRow.setInt(4, row.quantity());
                                insertRow.setString(5, row.payload());
                                if (insertRow.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Delete/reinsert insert did not affect one row: id=" + row.id());
                                }
                                transactionFingerprint = mix(transactionFingerprint, row.id());
                            } else if (workload.isFreshIndexedUpdate()) {
                                int updatesPerClient = Math.multiplyExact(
                                        transactionsPerClient, operationsPerTransaction);
                                int id = 1 + Math.addExact(
                                        Math.multiplyExact(clientIndex, updatesPerClient), operationIndex);
                                update.setInt(1, id);
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Fresh indexed update did not affect one row: id=" + id);
                                }
                                transactionFingerprint = mix(transactionFingerprint, id);
                            } else if (workload.isIndexedUpdate()) {
                                update.setInt(1, updateId);
                                if (update.executeUpdate() != 1) {
                                    throw new SQLException(
                                            "Concurrent update did not affect one row: id=" + updateId);
                                }
                                transactionFingerprint = mix(transactionFingerprint, updateId);
                            }
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
                        if (!isRetryableConflict(target, failure) || ++attempts >= 1000) {
                            throw failure;
                        }
                        retryableRollbacks++;
                    }
                }
            }
            return new ClientRun(fingerprint, retryableRollbacks);
        }

        private long executeMixedTransaction(int transaction) throws SQLException {
            if (workload == Workload.MIXED_80R20W) {
                if (transaction % 5 == 4) {
                    update.setInt(1, updateId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException(
                                "F11 80R20W update did not affect one row: id=" + updateId);
                    }
                    return mix(1L, updateId);
                }
                int readId = rowCount - clientIndex;
                int expected = mixedBaselineQuantities[readId];
                read.setInt(1, readId);
                try (ResultSet resultSet = read.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException("F11 80R20W read row missing: id=" + readId);
                    }
                    int actual = resultSet.getInt(1);
                    if (actual != expected || resultSet.next()) {
                        throw new SQLException(
                                "F11 80R20W stable read drift: id=" + readId
                                        + ", expected=" + expected + ", actual=" + actual);
                    }
                    return mix(mix(1L, readId), actual);
                }
            }

            if (workload != Workload.MIXED_50R50W_HOT) {
                throw new IllegalStateException("Not an F11 mixed workload: " + workload);
            }
            if ((transaction & 1) != 0) {
                update.setInt(1, updateId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException(
                            "F11 50R50W hot update did not affect one row: id=" + updateId);
                }
                return mix(1L, updateId);
            }

            int hotKeyCount = 4;
            int readId = 1 + Math.floorMod(clientIndex + transaction / 2, hotKeyCount);
            int baseline = mixedBaselineQuantities[readId];
            int writersPerKey = clientCount / hotKeyCount;
            int writesPerClient = transactionsPerClient / 2;
            int maximum = Math.addExact(baseline, Math.multiplyExact(writersPerKey, writesPerClient));
            read.setInt(1, readId);
            try (ResultSet resultSet = read.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("F11 50R50W hot read row missing: id=" + readId);
                }
                int actual = resultSet.getInt(1);
                if (actual < baseline || actual > maximum || resultSet.next()) {
                    throw new SQLException(
                            "F11 50R50W hot read outside legal committed bounds: id=" + readId
                                    + ", baseline=" + baseline + ", maximum=" + maximum
                                    + ", actual=" + actual);
                }
                // Do not mix the timing-dependent observed quantity into the semantic fingerprint.
                return mix(1L, readId);
            }
        }

        private long executeInsertTransaction(int transaction, int operationsPerTransaction)
                throws SQLException {
            long fingerprint = 1L;
            if (f08MultiRowInsertControlEnabled()) {
                if (workload != Workload.INSERT_100 || operationsPerTransaction != 100) {
                    throw new SQLException(
                            "F08 multi-row INSERT control requires INSERT_100 with 100 operations");
                }
                insertRow.clearParameters();
                for (int operation = 0; operation < operationsPerTransaction; operation++) {
                    int id = insertBaseId + transaction * operationsPerTransaction + operation;
                    bindFitnessInsert(
                            insertRow, operation * 5 + 1, id, payloadSize);
                    fingerprint = mix(fingerprint, id);
                }
                int count = insertRow.executeUpdate();
                if (count != operationsPerTransaction && count != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException(
                            "F08 multi-row INSERT returned unexpected update count: expected="
                                    + operationsPerTransaction + ", actual=" + count);
                }
                return fingerprint;
            }
            if (operationsPerTransaction == 1) {
                int id = insertBaseId + transaction;
                bindFitnessInsert(insertRow, id, payloadSize);
                if (insertRow.executeUpdate() != 1) {
                    throw new SQLException("INSERT-1 did not affect exactly one row: id=" + id);
                }
                return mix(fingerprint, id);
            }

            insertRow.clearBatch();
            for (int operation = 0; operation < operationsPerTransaction; operation++) {
                int id = insertBaseId + transaction * operationsPerTransaction + operation;
                bindFitnessInsert(insertRow, id, payloadSize);
                insertRow.addBatch();
                fingerprint = mix(fingerprint, id);
            }
            int[] counts = insertRow.executeBatch();
            if (counts.length != operationsPerTransaction) {
                throw new SQLException(
                        "INSERT batch count length drift: expected=" + operationsPerTransaction
                                + ", actual=" + counts.length);
            }
            for (int count : counts) {
                if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                    throw new SQLException("INSERT batch returned unexpected update count: " + count);
                }
            }
            return fingerprint;
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
            if (realisticTransaction != null) {
                try {
                    realisticTransaction.close();
                } catch (SQLException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            try {
                if (read != null) {
                    read.close();
                }
            } catch (SQLException closeFailure) {
                failure = closeFailure;
            }
            try {
                if (rangeRead != null) {
                    rangeRead.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (values != null) {
                    values.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (fitnessRead != null) {
                    fitnessRead.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
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
                if (deleteRow != null) {
                    deleteRow.close();
                }
            } catch (SQLException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                if (insertRow != null) {
                    insertRow.close();
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

    private record ClientRun(long fingerprint, long retryableRollbacks) {
    }
}
