/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DrdaProtocolEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Interval;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.MeasuredSpec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Measurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.OracleEvidence;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Spec;
import io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.Target;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.addDrdaProtocolEvidence;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.btreePointReadPathDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.cacheEntryDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.captureF04ServerPlanCostMatrix;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.heapAuthorityDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.heapPageReadImageDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.hotStateDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.lockEntryDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.lockWaitDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.mvccPhysicalLayoutDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.mvccSnapshotLeaseDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.pageLatchDiagnosticsEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetBTreePointReadPathDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetCacheEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetHeapPageReadImageDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetHotStateDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetLockEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetLockWaitDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetMvccSnapshotLeaseDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.resetPageLatchDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotBTreePointReadPathDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotCacheEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotDetailedPageLatchContentionByPage;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotHeapPageReadImageDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotHotStateDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotLockEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotLockWaitDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotMvccSnapshotLeaseDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotPageLatchContentionByPage;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.snapshotPageLatchDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeBTreePointReadPathDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeCacheEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeDerbyConglomerateMap;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeDetailedPageLatchContentionByPage;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeHeapPageReadImageDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeHotStateDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeLockEntryDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeLockWaitDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeMvccPhysicalLayout;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeMvccSnapshotLeaseDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writePageLatchContentionByPage;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writePageLatchDiagnostics;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkCaseDiagnostics.writeSqliteRuntimeMetadata;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.drdaProtocolEvidenceEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.f04ServerPlanCostMatrixEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkConfiguration.sqlSemanticOracleEnabled;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.configureRangeBulkFetchDefault;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkFixtures.prepareTables;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.DRDA_PROTOCOL_COUNTER_COUNT;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkModel.csvSafe;
import static io.github.ggeorg.delosdb.benchmark.jdbc.BenchmarkWorkloadRunner.throwFailure;

/** Warmup/measurement budgets, semantic verification and per-case cleanup. */
final class BenchmarkCaseMeasurement {
    private BenchmarkCaseMeasurement() {
    }

    static Connection connect(BenchmarkConfiguration options, Path database) throws SQLException {
        String url = options.target().jdbcUrl(database, options);
        Connection connection = options.remoteUser().isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, options.remoteUser(), options.remotePassword());
        if (options.target().isContainer() || options.target() == Target.SQLITE) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        return connection;
    }

    static MeasuredSpec measureSpec(BenchmarkConfiguration options, DelosBenchmarkConfig config, Spec spec)
            throws Exception {
        String specId = spec.workload().name().toLowerCase(Locale.ROOT)
                + "-c" + spec.clients() + "-w" + spec.operationsPerTransaction();
        Path database = options.databaseRoot().resolve(options.target().id())
                .resolve("run-" + options.run()).resolve("rows-" + config.rowCount()).resolve(specId);

        if (!options.target().isContainer()) {
            deleteRecursively(database);
            Files.createDirectories(database.getParent());
            if (options.target() == Target.H2 || options.target() == Target.SQLITE) {
                Files.createDirectories(database);
            }
        }

        Throwable failure = null;
        Measurement measurement = null;
        DrdaProtocolEvidence protocolEvidence = null;
        DelosSqlSemanticOracle.Result sqlOracleResult = null;
        try (Connection verifier = connect(options, database)) {
            DatabaseMetaData metadata = verifier.getMetaData();
            String product = csvSafe(metadata.getDatabaseProductName());
            String productVersion = csvSafe(metadata.getDatabaseProductVersion());
            String driverVersion = csvSafe(metadata.getDriverVersion());
            List<String> tables = prepareTables(verifier, options, spec, config);
            if (f04ServerPlanCostMatrixEnabled()) {
                captureF04ServerPlanCostMatrix(verifier, options, spec, tables.get(0));
            }
            configureRangeBulkFetchDefault(verifier, options, spec);
            if (options.target() == Target.SQLITE) {
                writeSqliteRuntimeMetadata(verifier, options, spec, config, tables);
            }
            if (heapAuthorityDiagnosticsEnabled() && options.target() == Target.DELOS_HEAP) {
                writeDerbyConglomerateMap(verifier, options, spec, config, tables);
            }
            if (mvccPhysicalLayoutDiagnosticsEnabled() && options.target() == Target.DELOS_MVCC) {
                writeMvccPhysicalLayout(verifier, options, spec, config, tables);
            }
            try (BenchmarkConcurrentCase concurrentCase = new BenchmarkConcurrentCase(
                    options, spec, database, verifier, tables, config.rowCount())) {
                Long expectedSemantic = null;
                long warmupElapsed = 0L;
                int warmupIterations = 0;
                long minimumWarmupNanos = options.minimumWarmupSeconds() <= 0.0
                        ? 0L
                        : Math.max(1L, (long) Math.ceil(
                                options.minimumWarmupSeconds() * 1_000_000_000.0));
                if (minimumWarmupNanos == 0L) {
                    for (int warmup = 0; warmup < options.warmups(); warmup++) {
                        Interval interval = concurrentCase.runInterval(false);
                        expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                                "warmup " + warmup);
                        warmupElapsed = Math.addExact(warmupElapsed, interval.elapsedNanos());
                        warmupIterations++;
                    }
                } else {
                    while (warmupIterations < options.warmups() || warmupElapsed < minimumWarmupNanos) {
                        if (warmupIterations >= options.maximumWarmupIterations()) {
                            throw new IllegalStateException(
                                    "Unable to reach minimum warmup duration "
                                            + options.minimumWarmupSeconds() + "s within "
                                            + options.maximumWarmupIterations() + " intervals: " + spec);
                        }
                        Interval interval = concurrentCase.runInterval(false);
                        expectedSemantic = sameSemantic(expectedSemantic, interval.semanticFingerprint(), spec,
                                "warmup " + warmupIterations);
                        warmupElapsed = Math.addExact(warmupElapsed, interval.elapsedNanos());
                        warmupIterations++;
                    }
                }
                if (pageLatchDiagnosticsEnabled()) {
                    resetPageLatchDiagnostics();
                }
                if (heapPageReadImageDiagnosticsEnabled()) {
                    resetHeapPageReadImageDiagnostics();
                }
                if (btreePointReadPathDiagnosticsEnabled()) {
                    resetBTreePointReadPathDiagnostics();
                }
                if (lockEntryDiagnosticsEnabled()) {
                    resetLockEntryDiagnostics();
                }
                if (lockWaitDiagnosticsEnabled()) {
                    resetLockWaitDiagnostics();
                }
                if (cacheEntryDiagnosticsEnabled()) {
                    resetCacheEntryDiagnostics();
                }
                if (hotStateDiagnosticsEnabled()) {
                    resetHotStateDiagnostics();
                }
                if (mvccSnapshotLeaseDiagnosticsEnabled()) {
                    resetMvccSnapshotLeaseDiagnostics();
                }
                long elapsed = 0L;
                long retryableRollbacks = 0L;
                Long measuredSemantic = expectedSemantic;
                int measuredIterations = 0;
                long[] measuredProtocolEvidence = drdaProtocolEvidenceEnabled()
                        ? new long[DRDA_PROTOCOL_COUNTER_COUNT]
                        : null;
                long minimumMeasuredNanos = options.minimumMeasuredSeconds() <= 0.0
                        ? 0L
                        : Math.max(1L, (long) Math.ceil(
                                options.minimumMeasuredSeconds() * 1_000_000_000.0));
                if (minimumMeasuredNanos == 0L) {
                    for (int iteration = 0; iteration < options.iterations(); iteration++) {
                        boolean captureOracle = sqlSemanticOracleEnabled()
                                && iteration == options.iterations() - 1;
                        Interval interval = concurrentCase.runInterval(captureOracle);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "measured iteration " + iteration);
                        measuredIterations++;
                        if (captureOracle) {
                            sqlOracleResult = Objects.requireNonNull(
                                    interval.sqlOracleResult(),
                                    "Phase 0A SQL oracle result");
                        }
                    }
                } else {
                    while (measuredIterations < options.iterations() || elapsed < minimumMeasuredNanos) {
                        if (measuredIterations >= options.maximumMeasuredIterations()) {
                            throw new IllegalStateException(
                                    "Unable to reach minimum measured duration "
                                            + options.minimumMeasuredSeconds() + "s within "
                                            + options.maximumMeasuredIterations() + " intervals: " + spec);
                        }
                        Interval interval = concurrentCase.runInterval(false);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "measured iteration " + measuredIterations);
                        measuredIterations++;
                    }
                    if (sqlSemanticOracleEnabled()) {
                        if (measuredIterations >= options.maximumMeasuredIterations()) {
                            throw new IllegalStateException(
                                    "No interval remains for Phase 0A SQL oracle capture after adaptive measurement: "
                                            + spec);
                        }
                        Interval interval = concurrentCase.runInterval(true);
                        addDrdaProtocolEvidence(measuredProtocolEvidence, interval.drdaProtocolEvidence());
                        elapsed = Math.addExact(elapsed, interval.elapsedNanos());
                        retryableRollbacks = Math.addExact(retryableRollbacks, interval.retryableRollbacks());
                        measuredSemantic = sameSemantic(measuredSemantic, interval.semanticFingerprint(), spec,
                                "oracle-bearing measured iteration " + measuredIterations);
                        measuredIterations++;
                        sqlOracleResult = Objects.requireNonNull(
                                interval.sqlOracleResult(),
                                "Phase 0A SQL oracle result");
                    }
                }
                long[] pageLatchDiagnostics = pageLatchDiagnosticsEnabled()
                        ? snapshotPageLatchDiagnostics()
                        : null;
                long[] heapPageReadImageDiagnostics = heapPageReadImageDiagnosticsEnabled()
                        ? snapshotHeapPageReadImageDiagnostics()
                        : null;
                String[] pageLatchContentionByPage = pageLatchDiagnosticsEnabled()
                        ? snapshotPageLatchContentionByPage()
                        : null;
                String[] detailedPageLatchContentionByPage = heapAuthorityDiagnosticsEnabled()
                        ? snapshotDetailedPageLatchContentionByPage()
                        : null;
                long[] btreePointReadPathDiagnostics = btreePointReadPathDiagnosticsEnabled()
                        ? snapshotBTreePointReadPathDiagnostics()
                        : null;
                String[] lockEntryDiagnostics = lockEntryDiagnosticsEnabled()
                        ? snapshotLockEntryDiagnostics()
                        : null;
                String[] lockWaitDiagnostics = lockWaitDiagnosticsEnabled()
                        ? snapshotLockWaitDiagnostics()
                        : null;
                String[] cacheEntryDiagnostics = cacheEntryDiagnosticsEnabled()
                        ? snapshotCacheEntryDiagnostics()
                        : null;
                String[] hotStateDiagnostics = hotStateDiagnosticsEnabled()
                        ? snapshotHotStateDiagnostics()
                        : null;
                long[] mvccSnapshotLeaseDiagnostics = mvccSnapshotLeaseDiagnosticsEnabled()
                        ? snapshotMvccSnapshotLeaseDiagnostics()
                        : null;
                int transactionsPerClient = options.transactionsPerClient(spec, config.rowCount());
                long measuredTransactions = Math.multiplyExact(
                        Math.multiplyExact((long) spec.clients(), transactionsPerClient),
                        measuredIterations);
                long measuredOperations = Math.multiplyExact(
                        measuredTransactions, spec.operationsPerTransaction());
                if (pageLatchDiagnostics != null) {
                    writePageLatchDiagnostics(
                            options, spec, config, measuredOperations, pageLatchDiagnostics);
                    writePageLatchContentionByPage(
                            options, spec, config, pageLatchContentionByPage);
                    if (detailedPageLatchContentionByPage != null) {
                        writeDetailedPageLatchContentionByPage(
                                options, spec, config, measuredOperations,
                                detailedPageLatchContentionByPage);
                    }
                }
                if (heapPageReadImageDiagnostics != null) {
                    writeHeapPageReadImageDiagnostics(
                            options, spec, config, measuredOperations,
                            heapPageReadImageDiagnostics);
                }
                if (btreePointReadPathDiagnostics != null) {
                    writeBTreePointReadPathDiagnostics(
                            options, spec, config, measuredOperations, btreePointReadPathDiagnostics);
                }
                if (lockEntryDiagnostics != null) {
                    writeLockEntryDiagnostics(
                            options, spec, config, measuredOperations, lockEntryDiagnostics);
                }
                if (lockWaitDiagnostics != null) {
                    writeLockWaitDiagnostics(
                            options, spec, config, measuredOperations, lockWaitDiagnostics);
                }
                if (cacheEntryDiagnostics != null) {
                    writeCacheEntryDiagnostics(
                            options, spec, config, measuredOperations, cacheEntryDiagnostics);
                }
                if (hotStateDiagnostics != null) {
                    writeHotStateDiagnostics(
                            options, spec, config, measuredOperations, hotStateDiagnostics);
                }
                if (mvccSnapshotLeaseDiagnostics != null) {
                    writeMvccSnapshotLeaseDiagnostics(
                            options, spec, config, measuredTransactions,
                            mvccSnapshotLeaseDiagnostics);
                }
                protocolEvidence = drdaProtocolEvidenceEnabled()
                        ? DrdaProtocolEvidence.from(
                                options, spec, config, product, productVersion, driverVersion,
                                measuredTransactions, measuredOperations, elapsed,
                                concurrentCase.setupDrdaProtocolEvidence(), measuredProtocolEvidence)
                        : null;
                measurement = new Measurement(
                        options.target().id(), product, productVersion, driverVersion,
                        spec.workload(), spec.clients(), spec.operationsPerTransaction(),
                        transactionsPerClient, config.rowCount(), config.payloadSize(),
                        config.commitBatchSize(), warmupIterations, warmupElapsed, measuredIterations,
                        measuredTransactions, measuredOperations, retryableRollbacks, elapsed,
                        measuredTransactions * 1_000_000_000.0 / elapsed,
                        measuredOperations * 1_000_000_000.0 / elapsed,
                        (double) elapsed / measuredTransactions,
                        Objects.requireNonNull(measuredSemantic), options.run());
            }
        } catch (Throwable operationFailure) {
            failure = operationFailure;
        }

        if (options.target().isEmbeddedDerby() && Files.exists(database)) {
            failure = shutdownDerby(database, failure);
        }
        if (!options.target().isContainer()) {
            try {
                deleteRecursively(database);
            } catch (Throwable cleanupFailure) {
                failure = preserve(failure, cleanupFailure);
            }
        }
        if (failure != null) {
            throwFailure(failure);
        }
        OracleEvidence evidence = sqlOracleResult == null ? null : new OracleEvidence(
                options.target().id(),
                spec.workload(),
                spec.clients(),
                spec.operationsPerTransaction(),
                config.rowCount(),
                sqlOracleResult.kind(),
                sqlOracleResult.count(),
                sqlOracleResult.fingerprint(),
                options.run());
        return new MeasuredSpec(measurement, evidence, protocolEvidence);
    }

    static Long sameSemantic(Long expected, long actual, Spec spec, String stage) {
        if (expected != null && expected.longValue() != actual) {
            throw new IllegalStateException("Concurrent semantic drift for " + spec + " during " + stage
                    + ": expected=" + expected + ", actual=" + actual);
        }
        return actual;
    }

    static Throwable shutdownDerby(Path database, Throwable primary) {
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

    static Throwable preserve(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Collections.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }
}
