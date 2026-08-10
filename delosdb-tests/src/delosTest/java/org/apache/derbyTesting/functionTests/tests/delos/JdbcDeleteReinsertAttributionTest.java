/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkProvider;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionOutcome;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosDeleteReinsertAttributionMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcDeleteReinsertAttribution;

public final class JdbcDeleteReinsertAttributionTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.benchmark.deleteReinsert.";

    public void testProviderNeutralDeleteReinsertAttribution() throws Exception {
        Path databaseRoot = Path.of(JdbcBenchmarkTestProperties.required(PREFIX + "databaseRoot"));
        Path reportDirectory = Path.of(JdbcBenchmarkTestProperties.required(PREFIX + "reportDirectory"));
        List<Integer> rows = JdbcBenchmarkTestProperties.integerList(PREFIX + "rows", "1000");
        int cycles = JdbcBenchmarkTestProperties.integer(PREFIX + "cycles", 3);
        int iterations = JdbcBenchmarkTestProperties.integer(PREFIX + "iterations", 3);
        int runs = JdbcBenchmarkTestProperties.integer(PREFIX + "runs", 2);

        List<DelosDeleteReinsertAttributionMeasurement> measurements =
                DelosJdbcDeleteReinsertAttribution.run(
                        databaseRoot,
                        reportDirectory,
                        rows,
                        cycles,
                        JdbcBenchmarkTestProperties.integer(PREFIX + "payload", 128),
                        JdbcBenchmarkTestProperties.integer(PREFIX + "fixtureBatch", 100),
                        JdbcBenchmarkTestProperties.integer(PREFIX + "warmups", 1),
                        iterations,
                        runs);

        int expectedMeasurements = rows.size()
                * DelosBenchmarkProvider.values().length
                * DelosJdbcDeleteReinsertAttribution.KeyMode.values().length
                * DelosJdbcDeleteReinsertAttribution.TransactionBoundary.values().length
                * DelosBenchmarkTransactionOutcome.values().length
                * runs;
        assertEquals("every provider/delete-reinsert shape/run should be measured",
                expectedMeasurements, measurements.size());

        Set<MeasurementKey> keys = new HashSet<>();
        Map<SemanticKey, Long> semantics = new HashMap<>();
        for (DelosDeleteReinsertAttributionMeasurement measurement : measurements) {
            assertTrue("measurement keys should be unique", keys.add(new MeasurementKey(
                    measurement.rowCount(),
                    measurement.provider(),
                    measurement.keyMode(),
                    measurement.transactionBoundary(),
                    measurement.outcome(),
                    measurement.run())));
            assertEquals("configured cycles per iteration",
                    cycles, measurement.cyclesPerIteration());
            assertEquals("measured cycle count",
                    (long) cycles * iterations, measurement.measuredCycles());
            assertEquals("transactions per cycle",
                    measurement.transactionBoundary()
                            == DelosJdbcDeleteReinsertAttribution.TransactionBoundary.ONE_TRANSACTION
                                    ? 1 : 2,
                    measurement.transactionsPerCycle());
            assertTrue("source-read time should be positive", measurement.sourceReadNanos() > 0L);
            assertTrue("delete time should be positive", measurement.deleteExecuteNanos() > 0L);
            assertTrue("insert time should be positive", measurement.insertExecuteNanos() > 0L);
            assertTrue("final transaction-end time should be positive",
                    measurement.finalTransactionEndNanos() > 0L);
            if (measurement.transactionBoundary()
                    == DelosJdbcDeleteReinsertAttribution.TransactionBoundary.ONE_TRANSACTION) {
                assertEquals("one-transaction shape has no intermediate transaction end",
                        0L, measurement.deleteTransactionEndNanos());
            } else {
                assertTrue("two-transaction shape measures the delete transaction end",
                        measurement.deleteTransactionEndNanos() > 0L);
            }
            assertEquals("phase sum should equal total timed time",
                    measurement.sourceReadNanos()
                            + measurement.deleteExecuteNanos()
                            + measurement.deleteTransactionEndNanos()
                            + measurement.insertExecuteNanos()
                            + measurement.finalTransactionEndNanos(),
                    measurement.totalTimedNanos());
            assertTrue("average cycle latency should be positive",
                    measurement.averageCycleNanos() > 0.0d);
            assertTrue("page-read operations should be non-negative",
                    measurement.pageReadOperations() >= 0L);
            assertTrue("page-write operations should be non-negative",
                    measurement.pageWriteOperations() >= 0L);
            assertTrue("force operations should be non-negative",
                    measurement.contentOnlyForceOperations() >= 0L
                            && measurement.metadataForceOperations() >= 0L);

            SemanticKey semanticKey = new SemanticKey(
                    measurement.rowCount(),
                    measurement.keyMode(),
                    measurement.transactionBoundary(),
                    measurement.outcome());
            Long prior = semantics.putIfAbsent(semanticKey, measurement.semanticFingerprint());
            if (prior != null) {
                assertEquals("heap/MVCC and repeated runs should preserve delete/reinsert semantics",
                        prior.longValue(), measurement.semanticFingerprint());
            }
        }

        Path csv = reportDirectory.resolve("delete-reinsert-results.csv");
        Path json = reportDirectory.resolve("delete-reinsert-results.json");
        Path summary = reportDirectory.resolve("delete-reinsert-summary.txt");
        assertTrue(Files.size(csv) > 0L);
        assertTrue(Files.size(json) > 0L);
        assertTrue(Files.size(summary) > 0L);
        assertTrue(Files.readString(csv).startsWith(
                "provider,keyMode,transactionBoundary,outcome,cyclesPerIteration,"));
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"sourceReadNanos\":"));
        assertTrue(jsonText.contains("\"deleteExecuteNanos\":"));
        assertTrue(jsonText.contains("\"insertExecuteNanos\":"));
        assertTrue(jsonText.contains("\"contentOnlyForceOperations\":"));
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains(
                "Semantic verification/restoration outside timed phases: true"));
        assertTrue(summaryText.contains(
                "TWO_TRANSACTIONS + ROLLBACK semantics: delete commits, insert rolls back"));
        assertTrue(summaryText.contains(
                "Phase timers are diagnostic attribution, not an S0 threshold"));
        assertTrue(summaryText.contains(
                "MVCC identity reservation block size: "
                        + System.getProperty(
                                "delosdb.mvcc.rawStoreIdentityReservationBlockSize")));
    }

    private record MeasurementKey(
            int rowCount,
            DelosBenchmarkProvider provider,
            DelosJdbcDeleteReinsertAttribution.KeyMode keyMode,
            DelosJdbcDeleteReinsertAttribution.TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome,
            int run) {
    }

    private record SemanticKey(
            int rowCount,
            DelosJdbcDeleteReinsertAttribution.KeyMode keyMode,
            DelosJdbcDeleteReinsertAttribution.TransactionBoundary transactionBoundary,
            DelosBenchmarkTransactionOutcome outcome) {
    }
}
