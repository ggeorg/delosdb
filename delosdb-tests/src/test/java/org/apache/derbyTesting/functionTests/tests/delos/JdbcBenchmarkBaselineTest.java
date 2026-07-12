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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkMeasurement;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkMeasurementUnit;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkOperation;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkPhase;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkProvider;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkSampleScope;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkTransactionKind;
import io.github.ggeorg.delosdb.benchmark.jdbc.DelosJdbcBenchmarkBaseline;

public final class JdbcBenchmarkBaselineTest extends MvccSqlTestSupport {
    private static final String PREFIX = "delosdb.benchmark.";

    public void testProviderNeutralJdbcPerformanceBaseline() throws Exception {
        Path databaseRoot = Path.of(requiredProperty(PREFIX + "databaseRoot"));
        Path reportDirectory = Path.of(requiredProperty(PREFIX + "reportDirectory"));
        List<Integer> rows = Arrays.stream(property(PREFIX + "rows", "100,1000,10000,100000").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .toList();
        int readOperationsPerTransaction = integerProperty(PREFIX + "readOperationsPerTransaction", 10);
        int iterations = integerProperty(PREFIX + "iterations", 5);
        int runs = integerProperty(PREFIX + "runs", 2);

        List<DelosBenchmarkMeasurement> measurements = DelosJdbcBenchmarkBaseline.run(
                databaseRoot,
                reportDirectory,
                rows,
                integerProperty(PREFIX + "payload", 128),
                integerProperty(PREFIX + "batch", 100),
                readOperationsPerTransaction,
                integerProperty(PREFIX + "warmups", 2),
                iterations,
                runs);

        int readOperationCount = 0;
        int writeOperationCount = 0;
        for (DelosBenchmarkOperation operation : DelosBenchmarkOperation.values()) {
            if (operation.transactionKind() == DelosBenchmarkTransactionKind.READ) {
                readOperationCount++;
            } else {
                writeOperationCount++;
            }
        }
        int measurementsPerProviderAndRowAndRun = readOperationCount * 6 + writeOperationCount * 4;
        int expectedMeasurements = rows.size()
                * DelosBenchmarkProvider.values().length
                * measurementsPerProviderAndRowAndRun
                * runs;
        assertEquals("read operations should expose first/repeated/transaction-end measurements",
                expectedMeasurements, measurements.size());

        Set<MeasurementKey> keys = new HashSet<>();
        for (DelosBenchmarkMeasurement measurement : measurements) {
            assertTrue("measurement keys should be unique", keys.add(new MeasurementKey(
                    measurement.rowCount(),
                    measurement.provider(),
                    measurement.operation(),
                    measurement.phase(),
                    measurement.sampleScope(),
                    measurement.run())));
            assertEquals("operation and measurement transaction kinds should agree",
                    measurement.operation().transactionKind(), measurement.transactionKind());
            assertTrue("phase elapsed time should be positive", measurement.elapsedNanos() > 0L);
            assertTrue("measured unit count should be positive", measurement.measuredUnits() > 0L);
            assertTrue("phase throughput should be positive", measurement.throughputPerSecond() > 0.0);
            assertTrue("phase average latency should be positive", measurement.averageLatencyNanos() > 0.0);

            if (measurement.transactionKind() == DelosBenchmarkTransactionKind.READ) {
                assertEquals("read transaction width", readOperationsPerTransaction,
                        measurement.operationsPerTransaction());
            } else {
                assertEquals("write benchmarks remain one operation per transaction", 1,
                        measurement.operationsPerTransaction());
            }

            switch (measurement.sampleScope()) {
                case FIRST_OPERATION -> {
                    assertEquals("first samples use operation units",
                            DelosBenchmarkMeasurementUnit.OPERATION, measurement.measurementUnit());
                    assertTrue("first samples should measure prepare or execute",
                            measurement.phase() == DelosBenchmarkPhase.PREPARE
                                    || measurement.phase() == DelosBenchmarkPhase.EXECUTE);
                    assertEquals("one first operation per measured transaction",
                            iterations, measurement.measuredUnits());
                }
                case REPEATED_OPERATIONS -> {
                    assertEquals("only read transactions have repeated samples",
                            DelosBenchmarkTransactionKind.READ, measurement.transactionKind());
                    assertEquals("repeated samples use operation units",
                            DelosBenchmarkMeasurementUnit.OPERATION, measurement.measurementUnit());
                    assertTrue("repeated samples should measure prepare or execute",
                            measurement.phase() == DelosBenchmarkPhase.PREPARE
                                    || measurement.phase() == DelosBenchmarkPhase.EXECUTE);
                    assertEquals("repeated operation sample count",
                            (long) iterations * (readOperationsPerTransaction - 1), measurement.measuredUnits());
                }
                case TRANSACTION_END -> {
                    assertEquals("transaction-end samples use transaction units",
                            DelosBenchmarkMeasurementUnit.TRANSACTION, measurement.measurementUnit());
                    assertTrue("transaction-end samples should measure commit or rollback",
                            measurement.phase() == DelosBenchmarkPhase.COMMIT
                                    || measurement.phase() == DelosBenchmarkPhase.ROLLBACK);
                    assertEquals("one transaction end per measured transaction",
                            iterations, measurement.measuredUnits());
                }
                default -> fail("Unexpected benchmark sample scope " + measurement.sampleScope());
            }
        }

        assertTrue(Files.size(reportDirectory.resolve("benchmark-results.json")) > 0L);
        assertTrue(Files.size(reportDirectory.resolve("benchmark-results.csv")) > 0L);
        assertTrue(Files.size(reportDirectory.resolve("benchmark-summary.txt")) > 0L);
        assertTrue(Files.readString(reportDirectory.resolve("benchmark-results.csv"))
                .startsWith("provider,operation,transactionKind,phase,sampleScope,measurementUnit,"));
        String json = Files.readString(reportDirectory.resolve("benchmark-results.json"));
        assertTrue(json.contains("\"transactionKind\":"));
        assertTrue(json.contains("\"sampleScope\":"));
        assertTrue(json.contains("\"operationsPerTransaction\":"));
        assertTrue(json.contains("\"measuredUnits\":"));
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
            DelosBenchmarkPhase phase,
            DelosBenchmarkSampleScope sampleScope,
            int run) {
    }
}
