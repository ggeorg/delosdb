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

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkBatchMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkOperation;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkProvider;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkStatementMode;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionKind;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcBenchmarkBatchScaling;

public final class JdbcBenchmarkBatchScalingTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.benchmark.batchScaling.";

    public void testProviderNeutralJdbcExecutionBatchScaling() throws Exception {
        Path databaseRoot = Path.of(requiredProperty(PREFIX + "databaseRoot"));
        Path reportDirectory = Path.of(requiredProperty(PREFIX + "reportDirectory"));
        List<Integer> rows = integerListProperty(PREFIX + "rows", "1000");
        List<Integer> batchSizes = integerListProperty(PREFIX + "sizes", "100,1000,10000");
        List<DelosBenchmarkOperation> operations = operationListProperty(
                PREFIX + "operations",
                "PRIMARY_KEY_LOOKUP,SECONDARY_EQUALITY_LOOKUP,COMPOSITE_RANGE_SCAN,FULL_SCAN,AGGREGATE");
        int iterations = integerProperty(PREFIX + "iterations", 1);
        int runs = integerProperty(PREFIX + "runs", 2);

        List<DelosBenchmarkBatchMeasurement> measurements = DelosJdbcBenchmarkBatchScaling.run(
                databaseRoot,
                reportDirectory,
                rows,
                batchSizes,
                operations,
                integerProperty(PREFIX + "payload", 128),
                integerProperty(PREFIX + "fixtureBatch", 100),
                integerProperty(PREFIX + "warmups", 1),
                iterations,
                runs);

        int expectedMeasurements = rows.size()
                * batchSizes.size()
                * operations.size()
                * DelosBenchmarkProvider.values().length
                * runs;
        assertEquals("each provider/row/operation/batch/run combination should be measured",
                expectedMeasurements, measurements.size());

        Set<MeasurementKey> keys = new HashSet<>();
        Map<SemanticKey, SemanticValue> semantics = new HashMap<>();
        for (DelosBenchmarkBatchMeasurement measurement : measurements) {
            assertTrue("measurement keys should be unique", keys.add(new MeasurementKey(
                    measurement.rowCount(),
                    measurement.provider(),
                    measurement.operation(),
                    measurement.batchSize(),
                    measurement.run())));
            assertEquals("batch scaling uses reused prepared statements",
                    DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS,
                    measurement.statementMode());
            assertEquals("batch scaling currently measures reads only",
                    DelosBenchmarkTransactionKind.READ,
                    measurement.transactionKind());
            assertTrue("configured batch size should be reported",
                    batchSizes.contains(measurement.batchSize()));
            assertTrue("configured operation should be reported",
                    operations.contains(measurement.operation()));
            assertEquals("measured operations should include every iteration",
                    (long) measurement.batchSize() * iterations,
                    measurement.measuredOperations());
            assertTrue("elapsed time should be positive", measurement.elapsedNanos() > 0L);
            assertTrue("throughput should be positive", measurement.throughputPerSecond() > 0.0);
            assertTrue("average latency should be positive", measurement.averageLatencyNanos() > 0.0);
            assertTrue("semantic row count should be positive", measurement.semanticRowCount() > 0L);

            SemanticKey semanticKey = new SemanticKey(
                    measurement.rowCount(),
                    measurement.operation(),
                    measurement.batchSize());
            SemanticValue semanticValue = new SemanticValue(
                    measurement.semanticRowCount(),
                    measurement.semanticChecksum(),
                    measurement.batchFingerprint());
            SemanticValue prior = semantics.putIfAbsent(semanticKey, semanticValue);
            if (prior != null) {
                assertEquals("heap/MVCC and repeated runs should preserve batch semantics",
                        prior, semanticValue);
            }
        }

        Path csv = reportDirectory.resolve("batch-scaling-results.csv");
        Path json = reportDirectory.resolve("batch-scaling-results.json");
        Path summary = reportDirectory.resolve("batch-scaling-summary.txt");
        assertTrue(Files.size(csv) > 0L);
        assertTrue(Files.size(json) > 0L);
        assertTrue(Files.size(summary) > 0L);
        assertTrue(Files.readString(csv).startsWith(
                "provider,operation,statementMode,transactionKind,batchSize,rowCount,"));
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"batchSize\":"));
        assertTrue(jsonText.contains("\"measuredOperations\":"));
        assertTrue(jsonText.contains("\"batchFingerprint\":"));
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains("Rollback outside timing interval: true"));
        assertTrue(summaryText.contains("Batch-size order alternates by run: true"));
    }

    private static List<Integer> integerListProperty(String key, String fallback) {
        return Arrays.stream(property(key, fallback).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .distinct()
                .toList();
    }

    private static List<DelosBenchmarkOperation> operationListProperty(String key, String fallback) {
        return Arrays.stream(property(key, fallback).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(DelosBenchmarkOperation::valueOf)
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
            DelosBenchmarkOperation operation,
            int batchSize,
            int run) {
    }

    private record SemanticKey(
            int rowCount,
            DelosBenchmarkOperation operation,
            int batchSize) {
    }

    private record SemanticValue(
            long rowCount,
            long checksum,
            long fingerprint) {
    }
}
