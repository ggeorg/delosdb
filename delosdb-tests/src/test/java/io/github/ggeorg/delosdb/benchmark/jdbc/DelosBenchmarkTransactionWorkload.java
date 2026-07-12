/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Stable full-transaction workloads exposed by the JDBC transaction benchmark. */
public enum DelosBenchmarkTransactionWorkload {
    EMPTY,
    PRIMARY_KEY_READ,
    INDEXED_UPDATE,
    DELETE_REINSERT
}
