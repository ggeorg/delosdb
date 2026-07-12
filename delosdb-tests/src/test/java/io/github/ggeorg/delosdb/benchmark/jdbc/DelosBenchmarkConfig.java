/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jdbc;

/** Reproducible fixture parameters shared by all JDBC benchmark drivers. */
public record DelosBenchmarkConfig(int rowCount, int payloadSize, long seed, int commitBatchSize) {
    public DelosBenchmarkConfig {
        if (rowCount < 100) {
            throw new IllegalArgumentException("rowCount must be at least 100");
        }
        if (payloadSize < 16 || payloadSize > 4096) {
            throw new IllegalArgumentException("payloadSize must be between 16 and 4096");
        }
        if (commitBatchSize < 1 || commitBatchSize > rowCount) {
            throw new IllegalArgumentException("commitBatchSize must be between 1 and rowCount");
        }
    }

    public static DelosBenchmarkConfig smoke() {
        return new DelosBenchmarkConfig(500, 128, 0x5DE10DBL, 50);
    }
}
