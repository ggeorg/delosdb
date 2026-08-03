/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0.
 */
package io.github.ggeorg.delosdb.benchmark.jmh;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

/**
 * JMH execution layer for the stable DelosDB JDBC benchmark surface.
 *
 * <p>All database setup, semantic verification, statement preparation, and
 * cleanup live in {@link DelosJdbcJmhState}. Benchmark methods contain only
 * the public JDBC operation whose cost is being measured.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DelosJdbcJmhBenchmark {
    @Benchmark
    public long primaryKeyLookup(DelosJdbcJmhState state) throws SQLException {
        return state.primaryKeyLookup();
    }

    @Benchmark
    public long primaryKeyCoveredLookup(DelosJdbcJmhState state) throws SQLException {
        return state.primaryKeyCoveredLookup();
    }

    @Benchmark
    public long secondaryEqualityLookup(DelosJdbcJmhState state) throws SQLException {
        return state.secondaryEqualityLookup();
    }

    @Benchmark
    public long secondaryEqualityCoveredLookup(DelosJdbcJmhState state) throws SQLException {
        return state.secondaryEqualityCoveredLookup();
    }

    @Benchmark
    public long secondaryEqualityCoveredCount(DelosJdbcJmhState state) throws SQLException {
        return state.secondaryEqualityCoveredCount();
    }

    @Benchmark
    public long secondaryEqualityPayloadLookup(DelosJdbcJmhState state) throws SQLException {
        return state.secondaryEqualityPayloadLookup();
    }

    @Benchmark
    public long secondaryEqualityFullRowLookup(DelosJdbcJmhState state) throws SQLException {
        return state.secondaryEqualityFullRowLookup();
    }

    @Benchmark
    public long compositeRangeScan(DelosJdbcJmhState state) throws SQLException {
        return state.compositeRangeScan();
    }

    @Benchmark
    public long compositeRangeCoveredScan(DelosJdbcJmhState state) throws SQLException {
        return state.compositeRangeCoveredScan();
    }

    @Benchmark
    public long fullScan(DelosJdbcJmhState state) throws SQLException {
        return state.fullScan();
    }

    @Benchmark
    public long aggregate(DelosJdbcJmhState state) throws SQLException {
        return state.aggregate();
    }

    @Benchmark
    public long readTransactionCommit(DelosJdbcJmhState state) throws SQLException {
        return state.readTransactionCommit();
    }

    @Benchmark
    public long readTransactionRollback(DelosJdbcJmhState state) throws SQLException {
        return state.readTransactionRollback();
    }
}
