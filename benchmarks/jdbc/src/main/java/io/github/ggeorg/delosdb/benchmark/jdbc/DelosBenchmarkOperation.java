/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Stable SQL/JDBC operations exposed to benchmark drivers. */
public enum DelosBenchmarkOperation {
    PRIMARY_KEY_LOOKUP,
    SECONDARY_EQUALITY_LOOKUP,
    COMPOSITE_RANGE_SCAN,
    FULL_SCAN,
    AGGREGATE,
    INDEXED_UPDATE,
    DELETE_REINSERT
}
