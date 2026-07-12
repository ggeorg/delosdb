/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkProvider;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionOutcome;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionWorkload;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcBenchmarkTransactions;

public final class JdbcBenchmarkTransactionsTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.benchmark.transactions.";

    public void testProviderNeutralExplicitTransactionBaseline() throws Exception {
        Path databaseRoot = Path.of(requiredProperty(PREFIX + "databaseRoot"));
        Path reportDirectory = Path.of(requiredProperty(PREFIX + "reportDirectory"));
        List<Integer> rows = integerListProperty(PREFIX + "rows", "1000");
        List<Integer> readWidths = integerListProperty(PREFIX + "readWidths", "1,10");
        List<Integer> writeWidths = integerListProperty(PREFIX + "writeWidths", "1,10");
        int transactionsPerInterval = integerProperty(PREFIX + "cycles", 10);
        int iterations = integerProperty(PREFIX + "iterations", 1);
        int runs = integerProperty(PREFIX + "runs", 2);

        List<DelosBenchmarkTransactionMeasurement> measurements =
                DelosJdbcBenchmarkTransactions.run(
                        databaseRoot,
                        reportDirectory,
                        rows,
                        readWidths,
                        writeWidths,
                        transactionsPerInterval,
                        integerProperty(PREFIX + "payload", 128),
                        integerProperty(PREFIX + "fixtureBatch", 100),
                        integerProperty(PREFIX + "warmups", 1),
                        iterations,
                        runs);

        int specsPerProviderAndRun = 2
                + 2 * readWidths.size()
                + 2 * writeWidths.size()
                + 2;
        int expectedMeasurements = rows.size()
                * DelosBenchmarkProvider.values().length
                * specsPerProviderAndRun
                * runs;
        assertEquals("each provider/row/transaction-shape/run combination should be measured",
                expectedMeasurements, measurements.size());

        Set<MeasurementKey> keys = new HashSet<>();
        Map<SemanticKey, Long> semantics = new HashMap<>();
        for (DelosBenchmarkTransactionMeasurement measurement : measurements) {
            assertTrue("measurement keys should be unique", keys.add(new MeasurementKey(
                    measurement.rowCount(),
                    measurement.provider(),
                    measurement.workload(),
                    measurement.outcome(),
                    measurement.operationsPerTransaction(),
                    measurement.run())));
            assertEquals("configured transactions per interval",
                    transactionsPerInterval, measurement.transactionsPerInterval());
            assertEquals("measured transaction count",
                    (long) transactionsPerInterval * iterations,
                    measurement.measuredTransactions());
            assertEquals("measured operation count",
                    measurement.measuredTransactions() * measurement.operationsPerTransaction(),
                    measurement.measuredOperations());
            assertTrue("elapsed time should be positive", measurement.elapsedNanos() > 0L);
            assertTrue("transaction throughput should be positive",
                    measurement.transactionsPerSecond() > 0.0);
            assertTrue("average transaction latency should be positive",
                    measurement.averageTransactionLatencyNanos() > 0.0);

            switch (measurement.workload()) {
                case EMPTY -> assertEquals("empty transaction width", 0,
                        measurement.operationsPerTransaction());
                case PRIMARY_KEY_READ -> assertTrue("configured read width",
                        readWidths.contains(measurement.operationsPerTransaction()));
                case INDEXED_UPDATE -> assertTrue("configured indexed-update width",
                        writeWidths.contains(measurement.operationsPerTransaction()));
                case DELETE_REINSERT -> assertEquals("delete/reinsert width", 1,
                        measurement.operationsPerTransaction());
                default -> fail("Unexpected transaction workload " + measurement.workload());
            }

            SemanticKey semanticKey = new SemanticKey(
                    measurement.rowCount(),
                    measurement.workload(),
                    measurement.outcome(),
                    measurement.operationsPerTransaction());
            Long prior = semantics.putIfAbsent(semanticKey, measurement.semanticFingerprint());
            if (prior != null) {
                assertEquals("heap/MVCC and repeated runs should preserve transaction semantics",
                        prior.longValue(), measurement.semanticFingerprint());
            }
        }

        Path csv = reportDirectory.resolve("transaction-results.csv");
        Path json = reportDirectory.resolve("transaction-results.json");
        Path summary = reportDirectory.resolve("transaction-summary.txt");
        assertTrue(Files.size(csv) > 0L);
        assertTrue(Files.size(json) > 0L);
        assertTrue(Files.size(summary) > 0L);
        assertTrue(Files.readString(csv).startsWith(
                "provider,workload,outcome,operationsPerTransaction,transactionsPerInterval,"));
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"transactionsPerSecond\":"));
        assertTrue(jsonText.contains("\"averageTransactionLatencyNanos\":"));
        assertTrue(jsonText.contains("\"semanticFingerprint\":"));
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains(
                "Measured interval: operation execution plus transaction end"));
        assertTrue(summaryText.contains(
                "Semantic verification/restoration outside timing interval: true"));
        assertTrue(summaryText.contains(
                "Provider and transaction-shape order alternates by run: true"));
    }

    private static List<Integer> integerListProperty(String key, String fallback) {
        return Arrays.stream(property(key, fallback).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .distinct()
                .toList();
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + key);
        }
        return value;
    }

    private static String property(String key, String fallback) {
        return System.getProperty(key, fallback);
    }

    private static int integerProperty(String key, int fallback) {
        return Integer.parseInt(property(key, Integer.toString(fallback)));
    }

    private record MeasurementKey(
            int rowCount,
            DelosBenchmarkProvider provider,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction,
            int run) {
    }

    private record SemanticKey(
            int rowCount,
            DelosBenchmarkTransactionWorkload workload,
            DelosBenchmarkTransactionOutcome outcome,
            int operationsPerTransaction) {
    }
}
