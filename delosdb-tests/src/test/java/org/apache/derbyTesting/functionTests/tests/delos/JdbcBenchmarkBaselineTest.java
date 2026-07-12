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
import java.util.List;

import io.github.ggeorg.delosdb.benchmark.jdbc.DelosBenchmarkMeasurement;
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

        List<DelosBenchmarkMeasurement> measurements = DelosJdbcBenchmarkBaseline.run(
                databaseRoot,
                reportDirectory,
                rows,
                integerProperty(PREFIX + "payload", 128),
                integerProperty(PREFIX + "batch", 100),
                integerProperty(PREFIX + "warmups", 2),
                integerProperty(PREFIX + "iterations", 5),
                integerProperty(PREFIX + "runs", 2));

        assertFalse("baseline should produce measurements", measurements.isEmpty());
        assertTrue(Files.size(reportDirectory.resolve("benchmark-results.json")) > 0L);
        assertTrue(Files.size(reportDirectory.resolve("benchmark-results.csv")) > 0L);
        assertTrue(Files.size(reportDirectory.resolve("benchmark-summary.txt")) > 0L);
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
}
