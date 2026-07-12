/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Stable SQL/JDBC operations exposed to benchmark drivers. */
public enum DelosBenchmarkOperation {
    PRIMARY_KEY_LOOKUP(DelosBenchmarkTransactionKind.READ),
    SECONDARY_EQUALITY_LOOKUP(DelosBenchmarkTransactionKind.READ),
    COMPOSITE_RANGE_SCAN(DelosBenchmarkTransactionKind.READ),
    FULL_SCAN(DelosBenchmarkTransactionKind.READ),
    AGGREGATE(DelosBenchmarkTransactionKind.READ),
    INDEXED_UPDATE(DelosBenchmarkTransactionKind.WRITE),
    DELETE_REINSERT(DelosBenchmarkTransactionKind.WRITE);

    private final DelosBenchmarkTransactionKind transactionKind;

    DelosBenchmarkOperation(DelosBenchmarkTransactionKind transactionKind) {
        this.transactionKind = transactionKind;
    }

    public DelosBenchmarkTransactionKind transactionKind() {
        return transactionKind;
    }
}
