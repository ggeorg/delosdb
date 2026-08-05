/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** One provider-neutral delete/reinsert phase-attribution measurement. */
public record DelosDeleteReinsertAttributionMeasurement(
        DelosBenchmarkProvider provider,
        DelosJdbcDeleteReinsertAttribution.KeyMode keyMode,
        DelosJdbcDeleteReinsertAttribution.TransactionBoundary transactionBoundary,
        DelosBenchmarkTransactionOutcome outcome,
        int cyclesPerIteration,
        int rowCount,
        int payloadSize,
        int fixtureCommitBatchSize,
        int warmups,
        int iterations,
        long measuredCycles,
        int transactionsPerCycle,
        long sourceReadNanos,
        long deleteExecuteNanos,
        long deleteTransactionEndNanos,
        long insertExecuteNanos,
        long finalTransactionEndNanos,
        long totalTimedNanos,
        double averageCycleNanos,
        long pageReadOperations,
        long pageReadBytes,
        long pageWriteOperations,
        long pageWriteBytes,
        long contentOnlyForceOperations,
        long metadataForceOperations,
        long semanticFingerprint,
        int run) {
}
