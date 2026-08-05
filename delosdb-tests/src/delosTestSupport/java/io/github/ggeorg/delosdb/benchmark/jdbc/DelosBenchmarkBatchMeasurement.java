/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** One execution-batch scaling measurement. */
public record DelosBenchmarkBatchMeasurement(
        DelosBenchmarkProvider provider,
        DelosBenchmarkOperation operation,
        DelosBenchmarkStatementMode statementMode,
        DelosBenchmarkTransactionKind transactionKind,
        int batchSize,
        int rowCount,
        int payloadSize,
        int fixtureCommitBatchSize,
        int warmups,
        int iterations,
        long measuredOperations,
        long elapsedNanos,
        double throughputPerSecond,
        double averageLatencyNanos,
        long semanticRowCount,
        long semanticChecksum,
        long batchFingerprint,
        int run) {
}
