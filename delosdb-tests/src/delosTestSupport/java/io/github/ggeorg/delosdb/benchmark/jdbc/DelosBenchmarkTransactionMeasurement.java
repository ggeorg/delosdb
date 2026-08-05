/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** One batched full-transaction throughput measurement. */
public record DelosBenchmarkTransactionMeasurement(
        DelosBenchmarkProvider provider,
        DelosBenchmarkTransactionWorkload workload,
        DelosBenchmarkTransactionOutcome outcome,
        int operationsPerTransaction,
        int transactionsPerInterval,
        int rowCount,
        int payloadSize,
        int fixtureCommitBatchSize,
        int warmups,
        int iterations,
        long measuredTransactions,
        long measuredOperations,
        long elapsedNanos,
        double transactionsPerSecond,
        double averageTransactionLatencyNanos,
        long semanticFingerprint,
        int run) {
}
