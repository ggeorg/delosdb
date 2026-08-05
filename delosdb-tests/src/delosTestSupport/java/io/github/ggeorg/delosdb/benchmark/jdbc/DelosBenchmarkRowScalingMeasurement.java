/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** One adaptive JDBC row-count scaling measurement. */
public record DelosBenchmarkRowScalingMeasurement(
        DelosBenchmarkProvider provider,
        DelosBenchmarkOperation operation,
        DelosBenchmarkStatementMode statementMode,
        DelosBenchmarkTransactionKind transactionKind,
        int rowCount,
        int payloadSize,
        int fixtureCommitBatchSize,
        long fixturePrepareNanos,
        long databaseBytesAfterFixture,
        long targetRowsPerInterval,
        int maxOperationsPerInterval,
        int operationsPerInterval,
        int warmups,
        int iterations,
        long measuredOperations,
        long elapsedNanos,
        double throughputPerSecond,
        double averageLatencyNanos,
        double averageLatencyPerConfiguredRowNanos,
        long semanticRowCount,
        long semanticChecksum,
        long batchFingerprint,
        int run) {
}
