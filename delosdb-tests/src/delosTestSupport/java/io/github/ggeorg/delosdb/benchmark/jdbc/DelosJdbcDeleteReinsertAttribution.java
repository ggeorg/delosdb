/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.derbyTesting.functionTests.tests.delos.DelosDeleteReinsertPageTopologyTestSupport;

/** Provider-neutral delete/reinsert phase-attribution benchmark driver. */
public final class DelosJdbcDeleteReinsertAttribution {
    private static final long SEED = 0x5DE10DBL;

    /** Whether the inserted row reuses the deleted primary key. */
    public enum KeyMode {
        SAME_KEY,
        DIFFERENT_KEY
    }

    /** Whether delete and insert share one transaction boundary. */
    public enum TransactionBoundary {
        ONE_TRANSACTION(1),
        TWO_TRANSACTIONS(2);

        private final int transactionsPerCycle;

        TransactionBoundary(int transactionsPerCycle) {
            this.transactionsPerCycle = transactionsPerCycle;
        }

        int transactionsPerCycle() {
            return transactionsPerCycle;
        }
    }

    private DelosJdbcDeleteReinsertAttribution() {
    }

    public static List<DelosDeleteReinsertAttributionMeasurement> run(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            int cyclesPerIteration,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) throws Exception {
        Options options = new Options(
                databaseRoot,
                reportDirectory,
                List.copyOf(rowCounts),
                cyclesPerIteration,
                payloadSize,
                fixtureCommitBatchSize,
                warmups,
                iterations,
                runs);
        options.validate();
        DelosBenchmarkSupport.prepareOutput(options.databaseRoot(), options.reportDirectory());

        List<DelosDeleteReinsertAttributionMeasurement> measurements = new ArrayList<>();
        List<PageTopologyMeasurement> topology = new ArrayList<>();
        Map<SemanticKey, Long> expectedSemantics = new HashMap<>();
        for (int run = 1; run <= options.runs(); run++) {
            for (int rows : options.rowCounts()) {
                DelosBenchmarkConfig config = new DelosBenchmarkConfig(
                        rows,
                        options.payloadSize(),
                        SEED,
                        Math.min(options.fixtureCommitBatchSize(), rows));
                for (DelosBenchmarkProvider provider : providersForRun(run)) {
                    for (ScenarioSpec spec : specsForRun(run)) {
                        ScenarioMeasurement observed = measure(
                                options, config, provider, spec, run);
                        measurements.add(observed.measurement());
                        topology.addAll(observed.topology());
                        requireStableSemantics(
                                expectedSemantics, observed.measurement(), spec);
                    }
                }
            }
        }

        measurements.sort(Comparator
                .comparingInt(DelosDeleteReinsertAttributionMeasurement::rowCount)
                .thenComparing(DelosDeleteReinsertAttributionMeasurement::provider)
                .thenComparing(DelosDeleteReinsertAttributionMeasurement::keyMode)
                .thenComparing(DelosDeleteReinsertAttributionMeasurement::transactionBoundary)
                .thenComparing(DelosDeleteReinsertAttributionMeasurement::outcome)
                .thenComparingInt(DelosDeleteReinsertAttributionMeasurement::run));
        DelosDeleteReinsertReports.write(
                options.reportDirectory(),
                options.rowCounts(),
                options.cyclesPerIteration(),
                options.warmups(),
                options.iterations(),
                options.runs(),
                measurements,
                topology);
        return List.copyOf(measurements);
    }

    private static ScenarioMeasurement measure(
            Options options,
            DelosBenchmarkConfig config,
            DelosBenchmarkProvider provider,
            ScenarioSpec spec,
            int run) throws Exception {
        Path database = Path.of(options.databaseRoot() + "-" + provider.id()
                + "-" + config.rowCount()
                + "-" + spec.keyMode().name().toLowerCase(Locale.ROOT)
                + "-" + spec.transactionBoundary().name().toLowerCase(Locale.ROOT)
                + "-" + spec.outcome().name().toLowerCase(Locale.ROOT)
                + "-run" + run);
        return DelosBenchmarkSupport.withFreshEmbeddedDatabase(database, connection -> {
            DelosJdbcBenchmarkScenario scenario =
                    new DelosJdbcBenchmarkScenario(connection, provider, config);
            scenario.prepare();
            try (DelosDeleteReinsertWorkload workload = new DelosDeleteReinsertWorkload(
                    connection,
                    provider,
                    database,
                    scenario.tableName(),
                    config.rowCount(),
                    spec.keyMode(),
                    spec.transactionBoundary(),
                    spec.outcome())) {
                for (int warmup = 0; warmup < options.warmups(); warmup++) {
                    for (int cycle = 0; cycle < options.cyclesPerIteration(); cycle++) {
                        workload.execute(false);
                    }
                }

                PhaseTotals totals = new PhaseTotals();
                long semanticFingerprint = 1L;
                for (int iteration = 0; iteration < options.iterations(); iteration++) {
                    for (int cycle = 0; cycle < options.cyclesPerIteration(); cycle++) {
                        DelosDeleteReinsertWorkload.CycleObservation observation =
                                workload.execute(true);
                        totals.add(observation);
                        semanticFingerprint = mix(
                                semanticFingerprint, observation.semanticFingerprint());
                    }
                }
                DelosDeleteReinsertAttributionMeasurement measurement = totals.measurement(
                        options, config, provider, spec, semanticFingerprint, run);
                DelosDeleteReinsertPageTopologyTestSupport.Layout layout =
                        DelosDeleteReinsertPageTopologyTestSupport.inspect(
                                connection,
                                scenario.tableName(),
                                provider == DelosBenchmarkProvider.MVCC);
                List<PageTopologyMeasurement> topology = new ArrayList<>();
                for (DelosDeleteReinsertWorkload.PageTopologyObservation phase
                        : workload.capturePageTopology(layout)) {
                    for (DelosDeleteReinsertWorkload.RoleTopology role : phase.roles()) {
                        topology.add(new PageTopologyMeasurement(
                                provider,
                                spec.keyMode(),
                                spec.transactionBoundary(),
                                spec.outcome(),
                                config.rowCount(),
                                run,
                                phase.phase(),
                                role.role(),
                                role.pageWrites(),
                                role.distinctPages(),
                                role.repeatedWrites(),
                                role.pageWriteBytes()));
                    }
                }
                return new ScenarioMeasurement(measurement, List.copyOf(topology));
            }
        });
    }

    private static void requireStableSemantics(
            Map<SemanticKey, Long> expectedSemantics,
            DelosDeleteReinsertAttributionMeasurement measurement,
            ScenarioSpec spec) {
        SemanticKey key = new SemanticKey(
                measurement.rowCount(),
                spec.keyMode(),
                spec.transactionBoundary(),
                spec.outcome());
        Long prior = expectedSemantics.putIfAbsent(key, measurement.semanticFingerprint());
        if (prior != null && prior.longValue() != measurement.semanticFingerprint()) {
            throw new IllegalStateException(
                    "Non-reproducible delete/reinsert semantics for " + key
                            + ": expected=" + prior
                            + ", actual=" + measurement.semanticFingerprint()
                            + ", provider=" + measurement.provider()
                            + ", run=" + measurement.run());
        }
    }

    private static List<ScenarioSpec> specsForRun(int run) {
        List<ScenarioSpec> specs = new ArrayList<>();
        for (KeyMode keyMode : KeyMode.values()) {
            for (TransactionBoundary boundary : TransactionBoundary.values()) {
                for (DelosBenchmarkTransactionOutcome outcome
                        : DelosBenchmarkTransactionOutcome.values()) {
                    specs.add(new ScenarioSpec(keyMode, boundary, outcome));
                }
            }
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

    private static long mix(long fingerprint, long value) {
        return 31L * fingerprint + value;
    }

    private static final class PhaseTotals {
        private long sourceReadNanos;
        private long deleteExecuteNanos;
        private long deleteTransactionEndNanos;
        private long insertExecuteNanos;
        private long finalTransactionEndNanos;
        private long pageReadOperations;
        private long pageReadBytes;
        private long pageWriteOperations;
        private long pageWriteBytes;
        private long contentOnlyForceOperations;
        private long metadataForceOperations;

        private void add(DelosDeleteReinsertWorkload.CycleObservation observation) {
            sourceReadNanos += observation.sourceReadNanos();
            deleteExecuteNanos += observation.deleteExecuteNanos();
            deleteTransactionEndNanos += observation.deleteTransactionEndNanos();
            insertExecuteNanos += observation.insertExecuteNanos();
            finalTransactionEndNanos += observation.finalTransactionEndNanos();
            DelosDeleteReinsertWorkload.IoDelta io = observation.ioDelta();
            pageReadOperations += io.pageReadOperations();
            pageReadBytes += io.pageReadBytes();
            pageWriteOperations += io.pageWriteOperations();
            pageWriteBytes += io.pageWriteBytes();
            contentOnlyForceOperations += io.contentOnlyForceOperations();
            metadataForceOperations += io.metadataForceOperations();
        }

        private DelosDeleteReinsertAttributionMeasurement measurement(
                Options options,
                DelosBenchmarkConfig config,
                DelosBenchmarkProvider provider,
                ScenarioSpec spec,
                long semanticFingerprint,
                int run) {
            long measuredCycles = Math.multiplyExact(
                    (long) options.cyclesPerIteration(), options.iterations());
            long totalTimedNanos = sourceReadNanos
                    + deleteExecuteNanos
                    + deleteTransactionEndNanos
                    + insertExecuteNanos
                    + finalTransactionEndNanos;
            return new DelosDeleteReinsertAttributionMeasurement(
                    provider,
                    spec.keyMode(),
                    spec.transactionBoundary(),
                    spec.outcome(),
                    options.cyclesPerIteration(),
                    config.rowCount(),
                    config.payloadSize(),
                    config.commitBatchSize(),
                    options.warmups(),
                    options.iterations(),
                    measuredCycles,
                    spec.transactionBoundary().transactionsPerCycle(),
                    sourceReadNanos,
                    deleteExecuteNanos,
                    deleteTransactionEndNanos,
                    insertExecuteNanos,
                    finalTransactionEndNanos,
                    totalTimedNanos,
                    (double) totalTimedNanos / measuredCycles,
                    pageReadOperations,
                    pageReadBytes,
                    pageWriteOperations,
                    pageWriteBytes,
                    contentOnlyForceOperations,
                    metadataForceOperations,
                    semanticFingerprint,
                    run);
        }
    }

    record PageTopologyMeasurement(
            DelosBenchmarkProvider provider,
            KeyMode keyMode,
            TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome,
            int rowCount,
            int run,
            String phase,
            DelosDeleteReinsertPageTopologyTestSupport.Role role,
            long pageWrites,
            long distinctPages,
            long repeatedWrites,
            long pageWriteBytes) {
    }

    private record ScenarioMeasurement(
            DelosDeleteReinsertAttributionMeasurement measurement,
            List<PageTopologyMeasurement> topology) {
    }

    private record ScenarioSpec(
            KeyMode keyMode,
            TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome) {
    }

    private record SemanticKey(
            int rowCount,
            KeyMode keyMode,
            TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome) {
    }

    private record Options(
            Path databaseRoot,
            Path reportDirectory,
            List<Integer> rowCounts,
            int cyclesPerIteration,
            int payloadSize,
            int fixtureCommitBatchSize,
            int warmups,
            int iterations,
            int runs) {
        private void validate() {
            if (databaseRoot == null || reportDirectory == null) {
                throw new IllegalArgumentException("databaseRoot and reportDirectory are required");
            }
            if (rowCounts == null || rowCounts.isEmpty()
                    || rowCounts.stream().anyMatch(value -> value == null || value < 100)) {
                throw new IllegalArgumentException(
                        "rowCounts must contain values of at least 100");
            }
            if (cyclesPerIteration < 1 || cyclesPerIteration > 10_000) {
                throw new IllegalArgumentException(
                        "cyclesPerIteration must be between 1 and 10000");
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
    }
}
