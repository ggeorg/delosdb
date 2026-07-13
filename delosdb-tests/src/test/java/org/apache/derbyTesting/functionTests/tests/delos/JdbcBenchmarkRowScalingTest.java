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

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkOperation;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkProvider;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkRowScalingMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkStatementMode;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionKind;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcBenchmarkRowScaling;

public final class JdbcBenchmarkRowScalingTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.benchmark.rowScaling.";

    public void testProviderNeutralJdbcRowCountScaling() throws Exception {
        Path databaseRoot = Path.of(JdbcBenchmarkTestProperties.required(PREFIX + "databaseRoot"));
        Path reportDirectory = Path.of(JdbcBenchmarkTestProperties.required(PREFIX + "reportDirectory"));
        List<Integer> rows = JdbcBenchmarkTestProperties.integerList(PREFIX + "rows", "100,1000");
        List<DelosBenchmarkOperation> operations = JdbcBenchmarkTestProperties.operationList(
                PREFIX + "operations",
                "PRIMARY_KEY_LOOKUP,SECONDARY_EQUALITY_LOOKUP,COMPOSITE_RANGE_SCAN,FULL_SCAN,AGGREGATE");
        long targetRowsPerInterval = JdbcBenchmarkTestProperties.longValue(PREFIX + "rowBudget", 100_000L);
        int maxOperationsPerInterval = JdbcBenchmarkTestProperties.integer(PREFIX + "maxOperations", 100);
        int iterations = JdbcBenchmarkTestProperties.integer(PREFIX + "iterations", 1);
        int runs = JdbcBenchmarkTestProperties.integer(PREFIX + "runs", 2);

        List<DelosBenchmarkRowScalingMeasurement> measurements =
                DelosJdbcBenchmarkRowScaling.run(
                        databaseRoot,
                        reportDirectory,
                        rows,
                        operations,
                        JdbcBenchmarkTestProperties.integer(PREFIX + "payload", 128),
                        JdbcBenchmarkTestProperties.integer(PREFIX + "fixtureBatch", 10_000),
                        targetRowsPerInterval,
                        maxOperationsPerInterval,
                        JdbcBenchmarkTestProperties.integer(PREFIX + "warmups", 1),
                        iterations,
                        runs);

        int expectedMeasurements = rows.size()
                * operations.size()
                * DelosBenchmarkProvider.values().length
                * runs;
        assertEquals("each provider/row/operation/run combination should be measured",
                expectedMeasurements, measurements.size());

        Set<MeasurementKey> keys = new HashSet<>();
        Map<SemanticKey, SemanticValue> semantics = new HashMap<>();
        for (DelosBenchmarkRowScalingMeasurement measurement : measurements) {
            assertTrue("measurement keys should be unique", keys.add(new MeasurementKey(
                    measurement.rowCount(),
                    measurement.provider(),
                    measurement.operation(),
                    measurement.run())));
            assertEquals("row scaling uses reused prepared statements",
                    DelosBenchmarkStatementMode.REUSED_ACROSS_TRANSACTIONS,
                    measurement.statementMode());
            assertEquals("row scaling measures reads only",
                    DelosBenchmarkTransactionKind.READ,
                    measurement.transactionKind());
            assertTrue("configured row count should be reported",
                    rows.contains(measurement.rowCount()));
            assertTrue("configured operation should be reported",
                    operations.contains(measurement.operation()));
            int expectedOperationsPerInterval = operationsPerInterval(
                    targetRowsPerInterval,
                    maxOperationsPerInterval,
                    measurement.rowCount());
            assertEquals("adaptive operation count",
                    expectedOperationsPerInterval,
                    measurement.operationsPerInterval());
            assertEquals("measured operations should include every iteration",
                    (long) expectedOperationsPerInterval * iterations,
                    measurement.measuredOperations());
            assertTrue("fixture preparation should be timed", measurement.fixturePrepareNanos() > 0L);
            assertTrue("fixture database should occupy storage",
                    measurement.databaseBytesAfterFixture() > 0L);
            assertTrue("elapsed time should be positive", measurement.elapsedNanos() > 0L);
            assertTrue("throughput should be positive", measurement.throughputPerSecond() > 0.0);
            assertTrue("average latency should be positive", measurement.averageLatencyNanos() > 0.0);
            assertTrue("normalized latency should be positive",
                    measurement.averageLatencyPerConfiguredRowNanos() > 0.0);
            assertTrue("semantic row count should be positive", measurement.semanticRowCount() > 0L);

            SemanticKey semanticKey = new SemanticKey(
                    measurement.rowCount(),
                    measurement.operation(),
                    measurement.operationsPerInterval());
            SemanticValue semanticValue = new SemanticValue(
                    measurement.semanticRowCount(),
                    measurement.semanticChecksum(),
                    measurement.batchFingerprint());
            SemanticValue prior = semantics.putIfAbsent(semanticKey, semanticValue);
            if (prior != null) {
                assertEquals("heap/MVCC and repeated runs should preserve row-scaling semantics",
                        prior, semanticValue);
            }
        }

        Path csv = reportDirectory.resolve("row-scaling-results.csv");
        Path json = reportDirectory.resolve("row-scaling-results.json");
        Path summary = reportDirectory.resolve("row-scaling-summary.txt");
        assertTrue(Files.size(csv) > 0L);
        assertTrue(Files.size(json) > 0L);
        assertTrue(Files.size(summary) > 0L);
        assertTrue(Files.readString(csv).startsWith(
                "provider,operation,statementMode,transactionKind,rowCount,payloadSize,"));
        String jsonText = Files.readString(json);
        assertTrue(jsonText.contains("\"fixturePrepareNanos\":"));
        assertTrue(jsonText.contains("\"operationsPerInterval\":"));
        assertTrue(jsonText.contains("\"averageLatencyPerConfiguredRowNanos\":"));
        String summaryText = Files.readString(summary);
        assertTrue(summaryText.contains(
                "Adaptive operation count: max(1, min(max operations, target rows / row count))"));
        assertTrue(summaryText.contains("Rollback outside timing interval: true"));
        assertTrue(summaryText.contains("Fixture preparation outside timing interval: true"));
        assertTrue(summaryText.contains(
                "Row, provider, and operation order alternate by run: true"));
    }

    private static int operationsPerInterval(
            long targetRowsPerInterval,
            int maxOperationsPerInterval,
            int rowCount) {
        long byBudget = targetRowsPerInterval / rowCount;
        return Math.toIntExact(Math.max(1L, Math.min(maxOperationsPerInterval, byBudget)));
    }

    private record MeasurementKey(
            int rowCount,
            DelosBenchmarkProvider provider,
            DelosBenchmarkOperation operation,
            int run) {
    }

    private record SemanticKey(
            int rowCount,
            DelosBenchmarkOperation operation,
            int operationsPerInterval) {
    }

    private record SemanticValue(
            long rowCount,
            long checksum,
            long fingerprint) {
    }
}
