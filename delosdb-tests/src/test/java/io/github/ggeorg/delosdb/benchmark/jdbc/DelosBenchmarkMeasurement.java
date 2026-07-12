/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** One reproducible JDBC benchmark measurement. */
public record DelosBenchmarkMeasurement(
        DelosBenchmarkProvider provider,
        DelosBenchmarkOperation operation,
        int rowCount,
        int payloadSize,
        int commitBatchSize,
        int warmups,
        int iterations,
        long elapsedNanos,
        double operationsPerSecond,
        double averageLatencyNanos,
        long semanticRowCount,
        long checksum,
        int run) {
}
